package aie.app;

import static org.assertj.core.api.Assertions.assertThat;

import aie.app.TestApi.StandInAnswers.Cancelling;
import aie.app.TestApi.StandInAnswers.Outcome;
import aie.core.tenancy.api.SessionTokens;
import aie.core.tenancy.api.SessionTokens.IdentityLevel;
import aie.http.api.model.Message;
import aie.http.api.model.Problem;
import aie.modules.conversation.api.Answers;
import aie.modules.conversation.api.Messages;
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
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * POST /v1/conversations/{id}/messages/{message_id}/cancel (M2-9-3) on the real web stack, with stand-ins whose answers
 * and cancels are steered by each test. The call looks for the ending every 200 milliseconds, on any instance.
 */
@Timeout(30)
class MessageCancelTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TestApi.StandInAnswers ANSWERS = new TestApi.StandInAnswers();
    private static final String MESSAGE_ID_RULE = "message_id must be msg_ followed by 1 to 60 letters or digits";

    private static ConfigurableApplicationContext context;
    private static int port;

    /**
     * The first call of a fresh JVM loads and warms what it needs, so one goes first, before any test times a call. What
     * it gets is left to the tests to check.
     */
    @BeforeAll
    static void start() throws Exception {
        context = TestApi.start(TestApi.LOOKUP, new TestApi.StandInKeys(), new TestApi.StandInEndUsers(),
                new TestApi.StandInConversations(), ANSWERS, "--server.port=0");
        port = TestApi.port(context);
        cancel("msg_00000000000000000000000000");
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
        ANSWERS.requested.clear();
        ANSWERS.cancelling = Cancelling.AT_ONCE;
    }

    /** An answer in conv_1, made the way the outcome says. */
    private static Messages.Asked ask(Outcome outcome) {
        ANSWERS.outcome = outcome;
        return ((Answers.Start.Started) ANSWERS.ask(TestApi.OWNER, "conv_1", "Hi")).asked();
    }

    /** An answer in conv_1 that is still being written, with "Hel" so far. */
    private static String going() {
        return ask(Outcome.NEVER_ENDS).answer().messageId();
    }

    /** POST .../cancel of the message in conv_1, with the test's server key and any further headers, in pairs. */
    private static HttpResponse<String> cancel(String messageId, String... headers) throws Exception {
        return cancelIn("conv_1", messageId, headers);
    }

    private static HttpResponse<String> cancelIn(String conversationId, String messageId, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/conversations/"
                        + conversationId + "/messages/" + messageId + "/cancel"))
                .header("Authorization", TestApi.AUTHORIZATION).timeout(Duration.ofSeconds(20));
        for (int i = 0; i < headers.length; i += 2) {
            request.setHeader(headers[i], headers[i + 1]);
        }
        return HttpClient.newHttpClient().send(request.POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
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

    /** The calls that reached the answers: the ask, then each cancel. */
    private static List<String> cancels() {
        return ANSWERS.calls.stream().filter(call -> call.contains(" cancels ")).toList();
    }

    /** The writer ends the answer at once, so the call returns it cancelled, with what it had and its usage. */
    @Test
    void aCancelledAnswerComesBackCancelled() throws Exception {
        String answer = going();

        long start = System.nanoTime();
        Message cancelled = message(cancel(answer));
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(cancelled.getId()).isEqualTo(answer);
        assertThat(cancelled.getConversationId()).isEqualTo("conv_1");
        assertThat(cancelled.getRole()).isEqualTo("assistant");
        assertThat(cancelled.getStatus()).isEqualTo("cancelled");
        assertThat(cancelled.getText()).isEqualTo("Hel");
        assertThat(cancelled.getUsage().getInputTokens()).isEqualTo(6);
        assertThat(cancelled.getUsage().getOutputTokens()).isEqualTo(3);
        assertThat(cancelled.getCode()).isNull();
        assertThat(cancelled.getCompletedAt()).isNotNull();
        assertThat(cancels()).containsExactly("ten_test/app_test cancels " + answer + " in conv_1");
        assertThat(took).isLessThan(Duration.ofSeconds(2));
    }

    /** A writer on another instance ends the answer at its next piece: the call waits for it, and no longer. */
    @Test
    void theCallWaitsForTheEnding() throws Exception {
        String answer = going();
        ANSWERS.cancelling = Cancelling.SOON;

        long start = System.nanoTime();
        Message cancelled = message(cancel(answer));
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(cancelled.getStatus()).isEqualTo("cancelled");
        assertThat(took).isBetween(Duration.ofMillis(300), Duration.ofSeconds(3));
    }

    /**
     * A writer whose model went quiet does not end the answer within the wait: the call gives up after 5 seconds and
     * returns the answer in progress, with its text so far. The request stays, and the answer ends as cancelled later.
     */
    @Test
    void anAnswerThatDoesNotEndInTimeComesBackInProgress() throws Exception {
        String answer = going();
        ANSWERS.cancelling = Cancelling.NEVER;

        long start = System.nanoTime();
        Message going = message(cancel(answer));
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(going.getStatus()).isEqualTo("in_progress");
        assertThat(going.getText()).isEqualTo("Hel");
        assertThat(going.getUsage()).isNull();
        assertThat(going.getCompletedAt()).isNull();
        assertThat(took).isBetween(Duration.ofSeconds(5), Duration.ofSeconds(9));
    }

    /** An answer that had ended comes back as it ended, and changes nothing, however often it is cancelled. */
    @ParameterizedTest
    @EnumSource(value = Outcome.class, names = {"COMPLETED", "FAILED"})
    void anAnswerThatEndedComesBackAsItEnded(Outcome outcome) throws Exception {
        String answer = ask(outcome).answer().messageId();

        HttpResponse<String> first = cancel(answer);
        HttpResponse<String> again = cancel(answer);

        Message ended = message(first);
        assertThat(ended.getStatus()).isEqualTo(outcome == Outcome.COMPLETED ? "completed" : "failed");
        assertThat(ended.getText()).isEqualTo(outcome == Outcome.COMPLETED ? "Hello!" : "Hel");
        assertThat(ended.getCode()).isEqualTo(outcome == Outcome.COMPLETED ? null : "model_failed");
        assertThat(again.body()).isEqualTo(first.body());
        assertThat(ANSWERS.answers.get(answer).status()).isEqualTo(
                outcome == Outcome.COMPLETED ? Messages.Status.COMPLETED : Messages.Status.FAILED);
    }

    /** The second cancel finds the answer cancelled by the first, and returns it the same. */
    @Test
    void cancellingAgainIsSafe() throws Exception {
        String answer = going();

        HttpResponse<String> first = cancel(answer);
        HttpResponse<String> again = cancel(answer);

        assertThat(message(first).getStatus()).isEqualTo("cancelled");
        assertThat(again.body()).isEqualTo(first.body());
        assertThat(ANSWERS.events.get(answer)).extracting(Messages.Event::type).containsExactly(
                Messages.EventType.MESSAGE_STARTED, Messages.EventType.MESSAGE_DELTA, Messages.EventType.MESSAGE_CANCELLED);
    }

    /** An unknown message, a user message and an answer of another conversation are all the same 404. */
    @Test
    void whatIsNoAnswerOfTheConversationIsNotFound() throws Exception {
        Messages.Asked asked = ask(Outcome.NEVER_ENDS);

        Problem unknown = problem(cancel("msg_00000000000000000000000000"), 404, "not_found");
        Problem question = problem(cancel(asked.question().messageId()), 404, "not_found");
        Problem elsewhere = problem(cancelIn("conv_2", asked.answer().messageId()), 404, "not_found");

        assertThat(question).isEqualTo(unknown);
        assertThat(elsewhere).isEqualTo(unknown);
        assertThat(ANSWERS.answers.get(asked.answer().messageId()).status()).isEqualTo(Messages.Status.IN_PROGRESS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"msg_a-b", "msg_", "message_1", "MSG_1", "msg_%E3%81%82"})
    void aMalformedMessageIdIsRefusedBeforeAnything(String id) throws Exception {
        assertThat(problem(cancel(id), 400, "invalid_request").getDetail()).isEqualTo(MESSAGE_ID_RULE);
        assertThat(cancels()).isEmpty();
    }

    @Test
    void aMessageIdHas64CharactersAtMost() throws Exception {
        assertThat(problem(cancel("msg_" + "0".repeat(61)), 400, "invalid_request").getDetail()).isEqualTo(MESSAGE_ID_RULE);
        problem(cancel("msg_" + "0".repeat(60)), 404, "not_found");
    }

    @Test
    void aMalformedConversationIdIsRefusedBeforeAnything() throws Exception {
        String answer = going();

        assertThat(problem(cancelIn("conv_a-b", answer), 400, "invalid_request").getDetail())
                .isEqualTo("conversation_id must be conv_ followed by 1 to 59 letters or digits");
        assertThat(cancels()).isEmpty();
    }

    /** The answer comes as JSON: a caller that takes none is turned away before the request is kept. */
    @ParameterizedTest
    @ValueSource(strings = {"application/problem+json", "text/html"})
    void aCallerThatTakesNoJsonIsTurnedAwayBeforeAnything(String accept) throws Exception {
        String answer = going();

        problem(cancel(answer, "Accept", accept), 406, "invalid_request");

        assertThat(cancels()).isEmpty();
        assertThat(ANSWERS.answers.get(answer).status()).isEqualTo(Messages.Status.IN_PROGRESS);
    }

    @Test
    void aCallerThatTakesAnythingGetsJson() throws Exception {
        String answer = going();

        assertThat(message(cancel(answer, "Accept", "*/*")).getStatus()).isEqualTo("cancelled");
    }

    /** Browser tokens come in M2-12-3. Until then a valid session token is forbidden here, and nothing is cancelled. */
    @Test
    void aSessionTokenIsNotTakenYet() throws Exception {
        String answer = going();
        String token = TestApi.SESSION_TOKENS.issue(new SessionTokens.Session(
                "ten_test", "app_test", "eu_1", IdentityLevel.VERIFIED, List.of(), Map.of(), null)).token();

        problem(cancel(answer, "Authorization", "Bearer " + token), 403, "forbidden");

        assertThat(cancels()).isEmpty();
    }
}
