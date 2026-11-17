package aie.app;

import static org.assertj.core.api.Assertions.assertThat;

import aie.app.TestApi.StandInAnswers.Outcome;
import aie.http.api.model.Message;
import aie.http.api.model.Problem;
import aie.modules.conversation.api.Answers;
import aie.modules.conversation.api.Messages;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * GET /v1/conversations/{id}/messages/{message_id} (M2-13-1) on the real web stack, with stand-ins whose answers are
 * steered by each test, and whose writers have gone when a test says so. ApiRoleTest reads messages on the real database.
 */
@Timeout(30)
class MessageReadTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TestApi.StandInAnswers ANSWERS = new TestApi.StandInAnswers();
    /** Conversations of browsers' end users, which a browser's request looks up before it does anything (M2-12-3). */
    private static final TestApi.StandInConversations CONVERSATIONS = new TestApi.StandInConversations();
    private static final String MESSAGE_ID_RULE = "message_id must be msg_ followed by 1 to 60 letters or digits";

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
        messages().leaseLooks.clear();
    }

    private static TestApi.StandInMessages messages() {
        return (TestApi.StandInMessages) context.getBean(Messages.class);
    }

    /** An answer in conv_1, made the way the outcome says, and its question. */
    private static Messages.Asked ask(Outcome outcome) {
        ANSWERS.outcome = outcome;
        return ((Answers.Start.Started) ANSWERS.ask(TestApi.OWNER, "conv_1", "Hi")).asked();
    }

    /** An answer still being written in a conversation of this browser end user: its conversation, then its ID. */
    private static String[] goingFor(String endUserId) {
        String conversation = CONVERSATIONS.start(TestApi.OWNER, endUserId, null).conversationId();
        ANSWERS.outcome = Outcome.NEVER_ENDS;
        return new String[] {conversation,
                ((Answers.Start.Started) ANSWERS.ask(TestApi.OWNER, conversation, "Hi")).asked().answer().messageId()};
    }

    /** GET of the message in conv_1, with the test's server key and any further headers, in pairs. */
    private static HttpResponse<String> read(String messageId, String... headers) throws Exception {
        return readIn("conv_1", messageId, headers);
    }

    private static HttpResponse<String> readIn(String conversationId, String messageId, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/conversations/"
                        + conversationId + "/messages/" + messageId))
                .header("Authorization", TestApi.AUTHORIZATION).timeout(Duration.ofSeconds(20));
        for (int i = 0; i < headers.length; i += 2) {
            request.setHeader(headers[i], headers[i + 1]);
        }
        return HttpClient.newHttpClient().send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Message message(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/json"));
        assertThat(response.headers().firstValue("Request-Id")).isPresent();
        return JSON.readValue(response.body(), Message.class);
    }

    private static Problem problem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).contains("application/problem+json");
        Problem problem = JSON.readValue(response.body(), Problem.class);
        assertThat(problem.getCode()).isEqualTo(code);
        return problem;
    }

    /** What the end user said reads as it was stored: completed, and with nothing an answer has. */
    @Test
    void aUserMessageReadsAsTheEndUserSaidIt() throws Exception {
        Messages.Asked asked = ask(Outcome.COMPLETED);

        Message said = message(read(asked.question().messageId()));

        assertThat(said.getId()).isEqualTo(asked.question().messageId());
        assertThat(said.getConversationId()).isEqualTo("conv_1");
        assertThat(said.getRole()).isEqualTo("user");
        assertThat(said.getStatus()).isEqualTo("completed");
        assertThat(said.getText()).isEqualTo("Hi");
        assertThat(said.getUsage()).isNull();
        assertThat(said.getCode()).isNull();
        assertThat(said.getCreatedAt()).isEqualTo(TestApi.StandInAnswers.ASKED_AT.atOffset(ZoneOffset.UTC));
        assertThat(said.getCompletedAt()).isEqualTo(TestApi.StandInAnswers.ASKED_AT.atOffset(ZoneOffset.UTC));
        assertThat(messages().leaseLooks).isEmpty();
    }

    /** An answer that ended reads as it ended. It has no lease any more, so none is looked at. */
    @ParameterizedTest
    @EnumSource(value = Outcome.class, names = {"COMPLETED", "FAILED"})
    void anAnswerThatEndedReadsAsItEnded(Outcome outcome) throws Exception {
        String answer = ask(outcome).answer().messageId();

        Message ended = message(read(answer));

        assertThat(ended.getId()).isEqualTo(answer);
        assertThat(ended.getRole()).isEqualTo("assistant");
        assertThat(ended.getStatus()).isEqualTo(outcome == Outcome.COMPLETED ? "completed" : "failed");
        assertThat(ended.getText()).isEqualTo(outcome == Outcome.COMPLETED ? "Hello!" : "Hel");
        assertThat(ended.getUsage() == null ? null : ended.getUsage().getOutputTokens())
                .isEqualTo(outcome == Outcome.COMPLETED ? 2L : null);
        assertThat(ended.getCode()).isEqualTo(outcome == Outcome.COMPLETED ? null : "model_failed");
        assertThat(ended.getCompletedAt()).isEqualTo(TestApi.StandInAnswers.ENDED_AT.atOffset(ZoneOffset.UTC));
        assertThat(messages().leaseLooks).isEmpty();
    }

    /**
     * An answer whose writer is there reads in progress, with its text so far, and goes on: the read looked at the
     * writer's lease, which still runs.
     */
    @Test
    void anAnswerInProgressReadsWithItsTextSoFar() throws Exception {
        String answer = ask(Outcome.NEVER_ENDS).answer().messageId();

        Message going = message(read(answer));

        assertThat(going.getStatus()).isEqualTo("in_progress");
        assertThat(going.getText()).isEqualTo("Hel");
        assertThat(going.getUsage()).isNull();
        assertThat(going.getCode()).isNull();
        assertThat(going.getCompletedAt()).isNull();
        assertThat(messages().leaseLooks).containsExactly(answer);
        assertThat(ANSWERS.answers.get(answer).status()).isEqualTo(Messages.Status.IN_PROGRESS);
    }

    /**
     * M2-11-3: the writer has gone and its lease has run out, so the read ends the answer for it first, and reads it as it
     * ended: failed with stream_interrupted, with what it had. A later read finds it so, without looking at a lease.
     */
    @Test
    void theAnswerOfAWriterThatHasGoneIsEndedBeforeItIsRead() throws Exception {
        String answer = ask(Outcome.NEVER_ENDS).answer().messageId();
        ANSWERS.gone.add(answer);

        HttpResponse<String> first = read(answer);
        HttpResponse<String> again = read(answer);

        Message ended = message(first);
        assertThat(ended.getStatus()).isEqualTo("failed");
        assertThat(ended.getCode()).isEqualTo("stream_interrupted");
        assertThat(ended.getText()).isEqualTo("Hel");
        assertThat(ended.getCompletedAt()).isEqualTo(TestApi.StandInAnswers.ENDED_AT.atOffset(ZoneOffset.UTC));
        assertThat(again.body()).isEqualTo(first.body());
        assertThat(messages().leaseLooks).containsExactly(answer);
        assertThat(ANSWERS.events.get(answer)).extracting(Messages.Event::type).containsExactly(
                Messages.EventType.MESSAGE_STARTED, Messages.EventType.MESSAGE_DELTA, Messages.EventType.ERROR);
    }

    /** An unknown message, and a question or an answer of another conversation, are all the same 404. */
    @Test
    void whatIsNoMessageOfTheConversationIsNotFound() throws Exception {
        Messages.Asked asked = ask(Outcome.NEVER_ENDS);
        ANSWERS.gone.add(asked.answer().messageId());

        Problem unknown = problem(read("msg_00000000000000000000000000"), 404, "not_found");
        Problem question = problem(readIn("conv_2", asked.question().messageId()), 404, "not_found");
        Problem answer = problem(readIn("conv_2", asked.answer().messageId()), 404, "not_found");

        assertThat(question).isEqualTo(unknown);
        assertThat(answer).isEqualTo(unknown);
        assertThat(messages().leaseLooks).isEmpty();
        assertThat(ANSWERS.answers.get(asked.answer().messageId()).status()).isEqualTo(Messages.Status.IN_PROGRESS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"msg_a-b", "msg_", "message_1", "MSG_1", "msg_%E3%81%82"})
    void aMalformedMessageIdIsRefused(String id) throws Exception {
        assertThat(problem(read(id), 400, "invalid_request").getDetail()).isEqualTo(MESSAGE_ID_RULE);
    }

    @Test
    void aMessageIdHas64CharactersAtMost() throws Exception {
        assertThat(problem(read("msg_" + "0".repeat(61)), 400, "invalid_request").getDetail()).isEqualTo(MESSAGE_ID_RULE);
        problem(read("msg_" + "0".repeat(60)), 404, "not_found");
    }

    @Test
    void aMalformedConversationIdIsRefused() throws Exception {
        String answer = ask(Outcome.COMPLETED).answer().messageId();

        assertThat(problem(readIn("conv_a-b", answer), 400, "invalid_request").getDetail())
                .isEqualTo("conversation_id must be conv_ followed by 1 to 59 letters or digits");
    }

    /**
     * The message comes as JSON: a caller that takes none is turned away before anything is read, so the answer of a
     * writer that has gone is left as it was.
     */
    @ParameterizedTest
    @ValueSource(strings = {"application/problem+json", "text/html"})
    void aCallerThatTakesNoJsonIsTurnedAwayBeforeAnything(String accept) throws Exception {
        String answer = ask(Outcome.NEVER_ENDS).answer().messageId();
        ANSWERS.gone.add(answer);

        problem(read(answer, "Accept", accept), 406, "invalid_request");

        assertThat(messages().leaseLooks).isEmpty();
        assertThat(ANSWERS.answers.get(answer).status()).isEqualTo(Messages.Status.IN_PROGRESS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"*/*", "application/*", "text/html, application/json;q=0.5"})
    void aCallerThatTakesJsonGetsIt(String accept) throws Exception {
        String answer = ask(Outcome.COMPLETED).answer().messageId();

        assertThat(message(read(answer, "Accept", accept)).getStatus()).isEqualTo("completed");
    }

    /** A browser reads its own end user's messages (M2-12-3), as a key does. */
    @Test
    void aTokenReadsAMessageInItsOwnConversation() throws Exception {
        String[] own = goingFor("eu_browser1");

        Message going = message(readIn(own[0], own[1], "Authorization", "Bearer " + TestApi.sessionToken("eu_browser1")));

        assertThat(going.getId()).isEqualTo(own[1]);
        assertThat(going.getStatus()).isEqualTo("in_progress");
    }

    /**
     * Another end user's message is missing to a browser, as an unknown one is. The read does nothing there, so the answer
     * of a writer that has gone is left for a reader that may reach it.
     */
    @Test
    void aTokenCannotReadAnotherEndUsersMessage() throws Exception {
        String[] others = goingFor("eu_browser1");
        ANSWERS.gone.add(others[1]);
        String token = "Bearer " + TestApi.sessionToken("eu_browser2");

        HttpResponse<String> other = readIn(others[0], others[1], "Authorization", token);
        HttpResponse<String> unknown = readIn("conv_nothing", others[1], "Authorization", token);

        problem(other, 404, "not_found");
        assertThat(other.body()).isEqualTo(unknown.body());
        assertThat(messages().leaseLooks).isEmpty();
        assertThat(ANSWERS.answers.get(others[1]).status()).isEqualTo(Messages.Status.IN_PROGRESS);
    }
}
