package aie.app;

import static org.assertj.core.api.Assertions.assertThat;

import aie.app.TestApi.StandInAnswers.Outcome;
import aie.http.api.model.Message;
import aie.http.api.model.MessageList;
import aie.http.api.model.Problem;
import aie.modules.conversation.api.Answers;
import aie.modules.conversation.api.Messages;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * GET /v1/conversations/{id}/messages (M2-13-3) on the real web stack, with stand-ins whose history is the questions and
 * answers each test asks for, in the order they were made, and whose writers have gone when a test says so. ApiRoleTest
 * reads the history on the real database.
 */
@Timeout(30)
class MessageHistoryTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TestApi.StandInAnswers ANSWERS = new TestApi.StandInAnswers();
    /** Conversations of browsers' end users, which a browser's request looks up before it does anything (M2-12-3). */
    private static final TestApi.StandInConversations CONVERSATIONS = new TestApi.StandInConversations();
    private static final String LIMIT_RULE = "limit must be a whole number from 1 to 100";
    private static final String CURSOR_RULE = "cursor must be a next_cursor of this conversation";

    private static ConfigurableApplicationContext context;
    private static int port;

    @BeforeAll
    static void start() {
        context = TestApi.start(TestApi.LOOKUP, new TestApi.StandInKeys(), new TestApi.StandInEndUsers(),
                CONVERSATIONS, ANSWERS, "--server.port=0");
        port = TestApi.port(context);
    }

    @AfterAll
    static void stop() {
        context.close();
    }

    @BeforeEach
    void forgetEarlierAnswers() {
        ANSWERS.calls.clear();
        ANSWERS.answers.clear();
        ANSWERS.questions.clear();
        ANSWERS.events.clear();
        ANSWERS.gone.clear();
        ANSWERS.order.clear();
        ANSWERS.conversations.clear();
        messages().leaseLooks.clear();
        messages().pageReads.clear();
    }

    private static TestApi.StandInMessages messages() {
        return (TestApi.StandInMessages) context.getBean(Messages.class);
    }

    /** Asks this many times in the conversation, each answer made the way the outcome says. */
    private static List<Messages.Asked> talk(String conversationId, int asks, Outcome outcome) {
        ANSWERS.outcome = outcome;
        List<Messages.Asked> asked = new ArrayList<>();
        for (int i = 0; i < asks; i++) {
            asked.add(((Answers.Start.Started) ANSWERS.ask(TestApi.OWNER, conversationId, "Hi")).asked());
        }
        return asked;
    }

    /** The IDs of these asks' messages, each question before its answer, as the history holds them. */
    private static List<String> ids(List<Messages.Asked> asked) {
        List<String> ids = new ArrayList<>();
        asked.forEach(each -> {
            ids.add(each.question().messageId());
            ids.add(each.answer().messageId());
        });
        return ids;
    }

    /** GET of conv_1's history with this query, with the test's server key and any further headers, in pairs. */
    private static HttpResponse<String> history(String query, String... headers) throws Exception {
        return historyOf("conv_1", query, headers);
    }

    private static HttpResponse<String> historyOf(String conversationId, String query, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/conversations/"
                        + conversationId + "/messages" + query))
                .header("Authorization", TestApi.AUTHORIZATION).timeout(Duration.ofSeconds(20));
        for (int i = 0; i < headers.length; i += 2) {
            request.setHeader(headers[i], headers[i + 1]);
        }
        return HttpClient.newHttpClient().send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static MessageList page(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/json"));
        assertThat(response.headers().firstValue("Request-Id")).isPresent();
        return JSON.readValue(response.body(), MessageList.class);
    }

    private static List<String> idsOf(MessageList page) {
        return page.getData().stream().map(Message::getId).toList();
    }

    private static Problem problem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).contains("application/problem+json");
        Problem problem = JSON.readValue(response.body(), Problem.class);
        assertThat(problem.getCode()).isEqualTo(code);
        return problem;
    }

    /** Without a limit a page holds the contract's 20; the next_cursor of the first leads to the rest. */
    @Test
    void aHistoryIsReadOldestFirstTwentyAtATime() throws Exception {
        List<String> all = ids(talk("conv_1", 11, Outcome.COMPLETED));

        MessageList first = page(history(""));
        HttpResponse<String> rest = history("?cursor=" + first.getNextCursor());

        assertThat(idsOf(first)).isEqualTo(all.subList(0, 20));
        assertThat(first.getNextCursor()).isEqualTo(all.get(19));
        assertThat(idsOf(page(rest))).isEqualTo(all.subList(20, 22));
        assertThat(page(rest).getNextCursor()).isNull();
        assertThat(rest.body()).contains("\"next_cursor\":null");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 5, 6, 100})
    void aPageHoldsAsManyAsTheLimitAsks(int limit) throws Exception {
        List<String> all = ids(talk("conv_1", 3, Outcome.COMPLETED));

        MessageList page = page(history("?limit=" + limit));

        assertThat(idsOf(page)).isEqualTo(all.subList(0, Math.min(limit, 6)));
        assertThat(page.getNextCursor()).isEqualTo(limit < 6 ? all.get(limit - 1) : null);
    }

    /** A limit is a whole number from 1 to 100; any other is refused before the history is read. */
    @ParameterizedTest
    @ValueSource(strings = {"0", "101", "-1", "2147483648", "abc", "1.5", "20x"})
    void aLimitOutsideOneToAHundredIsRefusedBeforeAnything(String limit) throws Exception {
        talk("conv_1", 1, Outcome.COMPLETED);

        assertThat(problem(history("?limit=" + limit), 400, "invalid_request").getDetail()).isEqualTo(LIMIT_RULE);
        assertThat(messages().pageReads).isEmpty();
    }

    /** An empty limit is none, so Spring gives the contract's 20. */
    @Test
    void anEmptyLimitIsTheDefault() throws Exception {
        List<String> all = ids(talk("conv_1", 11, Outcome.COMPLETED));

        assertThat(idsOf(page(history("?limit=")))).isEqualTo(all.subList(0, 20));
    }

    /** Pages of 2 walk the whole history; the last one is full, yet its next_cursor is null, since no more come. */
    @Test
    void theCursorsWalkTheWholeHistory() throws Exception {
        List<String> all = ids(talk("conv_1", 3, Outcome.COMPLETED));

        MessageList first = page(history("?limit=2"));
        MessageList second = page(history("?limit=2&cursor=" + first.getNextCursor()));
        MessageList last = page(history("?limit=2&cursor=" + second.getNextCursor()));

        assertThat(idsOf(first)).isEqualTo(all.subList(0, 2));
        assertThat(idsOf(second)).isEqualTo(all.subList(2, 4));
        assertThat(idsOf(last)).isEqualTo(all.subList(4, 6));
        assertThat(List.of(first.getNextCursor(), second.getNextCursor())).containsExactly(all.get(1), all.get(3));
        assertThat(last.getNextCursor()).isNull();
    }

    /** A cursor of another conversation, or of no message, cannot have come from this conversation's pages. */
    @Test
    void aCursorThisConversationDidNotGiveIsRefused() throws Exception {
        talk("conv_1", 2, Outcome.COMPLETED);
        String elsewhere = talk("conv_2", 1, Outcome.COMPLETED).getFirst().question().messageId();

        assertThat(problem(history("?cursor=" + elsewhere), 400, "invalid_request").getDetail()).isEqualTo(CURSOR_RULE);
        assertThat(problem(history("?cursor=msg_00000000000000000000000000"), 400, "invalid_request").getDetail())
                .isEqualTo(CURSOR_RULE);
    }

    /**
     * A cursor is written as a next_cursor is, a message ID, and an empty one is none of them; any other is refused before
     * the history is read.
     */
    @ParameterizedTest
    @ValueSource(strings = {"", "msg_", "msg_a-b", "message_1", "MSG_1", "msg_%E3%81%82"})
    void aCursorWrittenWrongIsRefusedBeforeAnything(String cursor) throws Exception {
        talk("conv_1", 1, Outcome.COMPLETED);

        assertThat(problem(history("?cursor=" + cursor), 400, "invalid_request").getDetail()).isEqualTo(CURSOR_RULE);
        assertThat(messages().pageReads).isEmpty();
    }

    @Test
    void aCursorHas64CharactersAtMost() throws Exception {
        talk("conv_1", 1, Outcome.COMPLETED);

        assertThat(problem(history("?cursor=msg_" + "0".repeat(61)), 400, "invalid_request").getDetail()).isEqualTo(CURSOR_RULE);
        assertThat(messages().pageReads).isEmpty();
        assertThat(problem(history("?cursor=msg_" + "0".repeat(60)), 400, "invalid_request").getDetail()).isEqualTo(CURSOR_RULE);
        assertThat(messages().pageReads).containsExactly("conv_1");
    }

    /** A conversation that is not there is missing whatever the cursor, so a cursor tells nothing about it. */
    @Test
    void anUnknownConversationIsNotFound() throws Exception {
        String position = talk("conv_1", 1, Outcome.COMPLETED).getFirst().question().messageId();

        HttpResponse<String> unknown = historyOf("conv_nothing", "");
        HttpResponse<String> withCursor = historyOf("conv_nothing", "?cursor=" + position);

        problem(unknown, 404, "not_found");
        assertThat(withCursor.statusCode()).isEqualTo(404);
        assertThat(withCursor.body()).isEqualTo(unknown.body());
    }

    @Test
    void aMalformedConversationIdIsRefused() throws Exception {
        assertThat(problem(historyOf("conv_a-b", ""), 400, "invalid_request").getDetail())
                .isEqualTo("conversation_id must be conv_ followed by 1 to 59 letters or digits");
        assertThat(messages().pageReads).isEmpty();
    }

    /** A conversation with no message yet is an empty last page, not a missing one. */
    @Test
    void anEmptyConversationIsAnEmptyLastPage() throws Exception {
        ANSWERS.conversations.add("conv_empty");

        HttpResponse<String> empty = historyOf("conv_empty", "");

        assertThat(idsOf(page(empty))).isEmpty();
        assertThat(empty.body()).contains("\"data\":[]", "\"next_cursor\":null");
    }

    /**
     * An answer whose writer is there reads in progress, with its text so far: its lease was looked at, it still runs,
     * so the page is read once. Only an answer in progress has a lease to look at.
     */
    @Test
    void answersInProgressReadAsTheyAreNow() throws Exception {
        talk("conv_1", 1, Outcome.COMPLETED);
        talk("conv_1", 1, Outcome.FAILED);
        String going = talk("conv_1", 1, Outcome.NEVER_ENDS).getFirst().answer().messageId();

        MessageList page = page(history(""));

        assertThat(page.getData()).extracting(Message::getStatus)
                .containsExactly("completed", "completed", "completed", "failed", "completed", "in_progress");
        assertThat(page.getData().getLast().getText()).isEqualTo("Hel");
        assertThat(messages().leaseLooks).containsExactly(going);
        assertThat(messages().pageReads).containsExactly("conv_1");
    }

    /**
     * M2-11-3: the writers of two answers on the page have gone, so the read ends both for them, and reads the page again,
     * once: both come failed with stream_interrupted.
     */
    @Test
    void theAnswersOfWritersThatHaveGoneAreEndedBeforeThePageIsRead() throws Exception {
        List<Messages.Asked> asked = talk("conv_1", 2, Outcome.NEVER_ENDS);
        talk("conv_1", 1, Outcome.COMPLETED);
        asked.forEach(each -> ANSWERS.gone.add(each.answer().messageId()));

        MessageList page = page(history(""));

        assertThat(page.getData()).extracting(Message::getStatus)
                .containsExactly("completed", "failed", "completed", "failed", "completed", "completed");
        assertThat(page.getData().get(1).getCode()).isEqualTo("stream_interrupted");
        assertThat(page.getData().get(3).getCode()).isEqualTo("stream_interrupted");
        assertThat(messages().leaseLooks).containsExactly(asked.get(0).answer().messageId(), asked.get(1).answer().messageId());
        assertThat(messages().pageReads).containsExactly("conv_1", "conv_1");
    }

    /** The history comes as JSON: a caller that takes none is turned away before it is read. */
    @ParameterizedTest
    @ValueSource(strings = {"application/problem+json", "text/html"})
    void aCallerThatTakesNoJsonIsTurnedAwayBeforeAnything(String accept) throws Exception {
        talk("conv_1", 1, Outcome.NEVER_ENDS);

        problem(history("", "Accept", accept), 406, "invalid_request");

        assertThat(messages().pageReads).isEmpty();
        assertThat(messages().leaseLooks).isEmpty();
    }

    /** A browser reads its own end user's history (M2-12-3), as a key does. */
    @Test
    void aTokenReadsTheHistoryOfItsOwnConversation() throws Exception {
        String conversation = CONVERSATIONS.start(TestApi.OWNER, "eu_browser1", null).conversationId();
        List<String> all = ids(talk(conversation, 1, Outcome.COMPLETED));

        MessageList page = page(historyOf(conversation, "", "Authorization", "Bearer " + TestApi.sessionToken("eu_browser1")));

        assertThat(idsOf(page)).isEqualTo(all);
    }

    /**
     * Another end user's history is missing to a browser, as an unknown conversation is. Nothing is read there, so the
     * answer of a writer that has gone is left for a reader that may reach it.
     */
    @Test
    void aTokenCannotReadAnotherEndUsersHistory() throws Exception {
        String conversation = CONVERSATIONS.start(TestApi.OWNER, "eu_browser1", null).conversationId();
        String going = talk(conversation, 1, Outcome.NEVER_ENDS).getFirst().answer().messageId();
        ANSWERS.gone.add(going);
        String token = "Bearer " + TestApi.sessionToken("eu_browser2");

        HttpResponse<String> other = historyOf(conversation, "", "Authorization", token);
        HttpResponse<String> unknown = historyOf("conv_nothing", "", "Authorization", token);

        problem(other, 404, "not_found");
        assertThat(other.body()).isEqualTo(unknown.body());
        assertThat(messages().pageReads).isEmpty();
        assertThat(messages().leaseLooks).isEmpty();
        assertThat(ANSWERS.answers.get(going).status()).isEqualTo(Messages.Status.IN_PROGRESS);
    }
}
