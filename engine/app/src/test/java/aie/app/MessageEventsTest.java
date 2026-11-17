package aie.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import aie.app.TestApi.StandInAnswers.Outcome;
import aie.core.tenancy.api.SessionTokens;
import aie.core.tenancy.api.SessionTokens.IdentityLevel;
import aie.http.api.StreamsApi;
import aie.http.api.model.Problem;
import aie.modules.conversation.api.Answers;
import aie.modules.conversation.api.Messages;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.lang.reflect.Parameter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
 * GET /v1/conversations/{id}/messages/{message_id}/events (M2-8) on the real web stack, with stand-ins whose answers
 * are steered by each test. A stream that waited for an ending that never comes would run into the timeout.
 */
@Timeout(30)
class MessageEventsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TestApi.StandInAnswers ANSWERS = new TestApi.StandInAnswers();
    private static final String MALFORMED_ID = "Last-Event-ID must be the id of an event the stream sent, a number from 1";
    private static final String BEYOND_THE_LOG = "Last-Event-ID names no event of this answer";

    private static ConfigurableApplicationContext context;
    private static int port;

    @BeforeAll
    static void start() {
        context = TestApi.start(TestApi.LOOKUP, new TestApi.StandInKeys(), new TestApi.StandInEndUsers(),
                new TestApi.StandInConversations(), ANSWERS, "--server.port=0");
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
    }

    /** An answer in conv_1, made the way the outcome says. */
    private static Messages.Asked ask(Outcome outcome) {
        ANSWERS.outcome = outcome;
        return ((Answers.Start.Started) ANSWERS.ask(TestApi.OWNER, "conv_1", "Hi")).asked();
    }

    private static HttpRequest.Builder request(String conversationId, String messageId, String... headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/conversations/"
                        + conversationId + "/messages/" + messageId + "/events"))
                .header("Authorization", TestApi.AUTHORIZATION).timeout(Duration.ofSeconds(20));
        for (int i = 0; i < headers.length; i += 2) {
            request.setHeader(headers[i], headers[i + 1]);
        }
        return request;
    }

    /** GET .../events of the message in conv_1, with the test's server key and any further headers, in pairs. */
    private static HttpResponse<String> events(String messageId, String... headers) throws Exception {
        return eventsIn("conv_1", messageId, headers);
    }

    private static HttpResponse<String> eventsIn(String conversationId, String messageId, String... headers) throws Exception {
        return HttpClient.newHttpClient().send(request(conversationId, messageId, headers).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> streamed(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(type -> assertThat(type).startsWith("text/event-stream"));
        return response;
    }

    private static Problem problem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).contains("application/problem+json");
        Problem problem = JSON.readValue(response.body(), Problem.class);
        assertThat(problem.getCode()).isEqualTo(code);
        return problem;
    }

    private static String sse(long seq, String type, String data) {
        return "id: " + seq + "\nevent: " + type + "\ndata: " + data + "\n\n";
    }

    private static String started(String answer) {
        return sse(1, "message.started", "{\"message_id\":\"" + answer + "\"}");
    }

    private static String delta(String answer) {
        return sse(2, "message.delta", "{\"message_id\":\"" + answer + "\",\"text\":\"Hello!\"}");
    }

    private static String completed(String answer, long seq) {
        return sse(seq, "message.completed", "{\"message_id\":\"" + answer + "\",\"usage\":{\"input_tokens\":6,\"output_tokens\":2}}");
    }

    private static TestApi.StandInMessages log() {
        return (TestApi.StandInMessages) context.getBean(Messages.class);
    }

    /** Without Last-Event-ID the stream starts from the first event, so an answer that ended is sent again whole. */
    @Test
    void anAnswerThatEndedIsSentAgainFromItsFirstEvent() throws Exception {
        String answer = ask(Outcome.COMPLETED).answer().messageId();

        HttpResponse<String> response = streamed(events(answer));

        assertThat(response.body()).isEqualTo(started(answer) + delta(answer) + completed(answer, 3));
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-cache");
        assertThat(response.headers().firstValue("X-Accel-Buffering")).contains("no");
        assertThat(response.headers().firstValue("Request-Id")).isPresent();
        assertThat(ANSWERS.calls).as("a stream asks nothing").containsExactly("ten_test/app_test asks in conv_1: Hi");
    }

    @Test
    void theStreamGoesOnAfterTheLastEventTheCallerGot() throws Exception {
        String answer = ask(Outcome.COMPLETED).answer().messageId();

        assertThat(streamed(events(answer, "Last-Event-ID", "1")).body()).isEqualTo(delta(answer) + completed(answer, 3));
        assertThat(streamed(events(answer, "Last-Event-ID", "2")).body()).isEqualTo(completed(answer, 3));
    }

    /** M2-8 decision 2: after the ending nothing comes, so the stream closes at once and sends nothing. */
    @Test
    void aCallerThatHasTheEndingGetsNothingMore() throws Exception {
        String answer = ask(Outcome.COMPLETED).answer().messageId();

        HttpResponse<String> response = streamed(events(answer, "Last-Event-ID", "3"));

        assertThat(response.body()).isEmpty();
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-cache");
    }

    /** The SSE standard's last event ID is empty until the first event came, so an empty one is none. */
    @Test
    void anEmptyLastEventIdIsNone() throws Exception {
        String answer = ask(Outcome.COMPLETED).answer().messageId();

        assertThat(streamed(events(answer, "Last-Event-ID", "")).body()).isEqualTo(started(answer) + delta(answer) + completed(answer, 3));
    }

    /** M2-8 decision 1: only an id the stream sent passes, before anything is read. */
    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "+1", "01", "1.0", "1e3", "0x1", "abc", "1 2", "1,2", "12345678901"})
    void anIdTheStreamCouldNotHaveSentIsRefused(String id) throws Exception {
        String answer = ask(Outcome.COMPLETED).answer().messageId();

        assertThat(problem(events(answer, "Last-Event-ID", id), 400, "invalid_request").getDetail()).isEqualTo(MALFORMED_ID);
        assertThat(log().reads(answer)).as("the log is not read").isZero();
    }

    /**
     * An id beyond the log was never sent, since the numbers never go back, and a stream after it would wait for a number
     * that never comes; so it is refused for an answer that ended and for one that goes on.
     */
    @Test
    void anIdBeyondTheLogIsRefused() throws Exception {
        String ended = ask(Outcome.COMPLETED).answer().messageId();
        String goingOn = ask(Outcome.NEVER_ENDS).answer().messageId();

        for (HttpResponse<String> refused : List.of(events(ended, "Last-Event-ID", "4"), events(ended, "Last-Event-ID", "9999999999"),
                events(goingOn, "Last-Event-ID", "3"))) {
            assertThat(problem(refused, 400, "invalid_request").getDetail()).isEqualTo(BEYOND_THE_LOG);
        }
    }

    /**
     * Blueprint 6.4: when content is withdrawn, its deltas go and its ending stays, with a higher number. A caller that got
     * a delta that is gone still gets the ending, and nothing after it.
     */
    @Test
    void anEventThatIsGoneStillLeadsToTheEnding() throws Exception {
        String answer = "msg_withdrawn";
        ANSWERS.answers.put(answer, new Messages.Message(answer, "conv_1", Messages.Role.ASSISTANT, Messages.Status.COMPLETED,
                "", new Messages.Usage(6, 2), null, TestApi.StandInAnswers.ASKED_AT, TestApi.StandInAnswers.ENDED_AT));
        ANSWERS.events.put(answer, List.of(
                new Messages.Event(answer, 1, Messages.EventType.MESSAGE_STARTED, null, null, null, TestApi.StandInAnswers.ASKED_AT),
                new Messages.Event(answer, 2, Messages.EventType.MESSAGE_DELTA, "Hello!", null, null, TestApi.StandInAnswers.ASKED_AT),
                new Messages.Event(answer, 5, Messages.EventType.MESSAGE_COMPLETED, null, new Messages.Usage(6, 2), null,
                        TestApi.StandInAnswers.ASKED_AT)));

        assertThat(streamed(events(answer, "Last-Event-ID", "3")).body()).isEqualTo(completed(answer, 5));
        assertThat(streamed(events(answer, "Last-Event-ID", "5")).body()).isEmpty();
        assertThat(problem(events(answer, "Last-Event-ID", "6"), 400, "invalid_request").getDetail()).isEqualTo(BEYOND_THE_LOG);
    }

    @Test
    void aFailedAnswerIsSentAgainWithItsError() throws Exception {
        String answer = ask(Outcome.FAILED).answer().messageId();

        String[] sent = streamed(events(answer, "Last-Event-ID", "1")).body().split("\n\n");

        assertThat(sent).hasSize(2);
        assertThat(sent[1]).startsWith("id: 3\nevent: error\ndata: ").contains("\"code\":\"model_failed\"", "\"status\":502");
    }

    /** A user message has no events, so it is as missing as a message that does not exist. */
    @Test
    void aUserMessageHasNoStream() throws Exception {
        String question = ask(Outcome.COMPLETED).question().messageId();

        HttpResponse<String> asked = events(question);
        HttpResponse<String> unknown = events("msg_nothing");

        problem(asked, 404, "not_found");
        assertThat(asked.body()).isEqualTo(unknown.body());
    }

    @Test
    void anAnswerOfAnotherConversationIsNotFound() throws Exception {
        String answer = ask(Outcome.COMPLETED).answer().messageId();

        HttpResponse<String> elsewhere = eventsIn("conv_2", answer);

        problem(elsewhere, 404, "not_found");
        assertThat(elsewhere.body()).isEqualTo(events("msg_nothing").body());
    }

    @ParameterizedTest
    @ValueSource(strings = {"msg_a-b", "MSG_1", "msg_", "msg_%0A1", "message_1"})
    void aMalformedMessageIdIsRefused(String id) throws Exception {
        assertThat(problem(events(id), 400, "invalid_request").getDetail())
                .isEqualTo("message_id must be msg_ followed by 1 to 60 letters or digits");
    }

    @Test
    void aMessageIdHas64CharactersAtMost() throws Exception {
        assertThat(problem(events("msg_" + "0".repeat(61)), 400, "invalid_request").getDetail())
                .isEqualTo("message_id must be msg_ followed by 1 to 60 letters or digits");
        problem(events("msg_" + "0".repeat(60)), 404, "not_found");
    }

    @Test
    void theMessageIdRuleIsTheContracts() throws Exception {
        Parameter id = StreamsApi.class.getMethod("streamMessageEvents",
                String.class, String.class, String.class, String.class, String.class, String.class).getParameters()[1];

        assertThat(id.getAnnotation(Pattern.class).regexp()).isEqualTo("^msg_[0-9A-Za-z]+$");
        assertThat(id.getAnnotation(Size.class).max()).isEqualTo(64);
    }

    @Test
    void aMalformedConversationIdIsRefused() throws Exception {
        assertThat(problem(eventsIn("conv_a-b", "msg_1"), 400, "invalid_request").getDetail())
                .isEqualTo("conversation_id must be conv_ followed by 1 to 59 letters or digits");
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/event-stream", "*/*", "text/*", "application/json;q=0.9, text/event-stream;q=0.1"})
    void aCallerThatTakesTheStreamGetsIt(String accept) throws Exception {
        String answer = ask(Outcome.COMPLETED).answer().messageId();

        assertThat(streamed(events(answer, "Accept", accept)).body()).startsWith("id: 1\nevent: message.started\n");
    }

    /** application/problem+json alone and a quality of 0 meet no stream, though Spring would let them through. */
    @ParameterizedTest
    @ValueSource(strings = {"application/problem+json", "text/event-stream;q=0"})
    void aCallerThatTakesNoStreamIsTurnedAway(String accept) throws Exception {
        String answer = ask(Outcome.COMPLETED).answer().messageId();

        assertThat(problem(events(answer, "Accept", accept), 406, "invalid_request").getDetail())
                .isEqualTo("The events come as text/event-stream");
    }

    @Test
    void anAcceptNoTypeOfTheOperationMeetsIsTurnedAway() throws Exception {
        String answer = ask(Outcome.COMPLETED).answer().messageId();

        problem(events(answer, "Accept", "application/json"), 406, "invalid_request");
    }

    /** HEAD has no body to wait for, so it answers at once and reads no events, even for an answer that goes on. */
    @Test
    void headAnswersAtOnceAndReadsNothing() throws Exception {
        String answer = ask(Outcome.NEVER_ENDS).answer().messageId();

        HttpResponse<String> head = HttpClient.newHttpClient().send(request("conv_1", answer).HEAD().build(),
                HttpResponse.BodyHandlers.ofString());

        streamed(head);
        assertThat(head.body()).isEmpty();
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(5)).until(() -> log().reads(answer) == 0);
    }

    /** Browser tokens come in M2-12-3. Until then a valid session token is forbidden here. */
    @Test
    void aSessionTokenIsNotTakenYet() throws Exception {
        String answer = ask(Outcome.COMPLETED).answer().messageId();
        String token = TestApi.SESSION_TOKENS.issue(new SessionTokens.Session(
                "ten_test", "app_test", "eu_1", IdentityLevel.VERIFIED, List.of(), Map.of(), null)).token();

        problem(events(answer, "Authorization", "Bearer " + token), 403, "forbidden");
    }
}
