package aie.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import aie.app.TestApi.StandInAnswers.Outcome;
import aie.core.tenancy.api.SessionTokens;
import aie.core.tenancy.api.SessionTokens.IdentityLevel;
import aie.http.AnswerWait;
import aie.http.api.model.Message;
import aie.http.api.model.Problem;
import aie.http.api.model.Usage;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * POST /v1/conversations/{id}/messages without streaming, on the real web stack, with a stand-in that answers the way
 * each test tells it. The wait is TestApi's short one.
 */
@ExtendWith(OutputCaptureExtension.class)
class MessageControllerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TestApi.StandInAnswers ANSWERS = new TestApi.StandInAnswers();
    /** A character outside the Basic Multilingual Plane: one character, two UTF-16 units. */
    private static final String ASTRAL = "😀";
    private static final String TEXT_RULE = "text must have 1 to 32000 characters";
    private static final String LOCALE_RULE = "locale must be a BCP 47 language tag of at most 35 characters, such as ja-JP";
    private static final String ID_RULE = "conversation_id must be conv_ followed by 1 to 59 letters or digits";

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
    void answerAtOnce() {
        ANSWERS.calls.clear();
        ANSWERS.answers.clear();
        ANSWERS.outcome = Outcome.COMPLETED;
    }

    /** POST .../messages of conv_1, with the test's server key and JSON, and any further headers, in pairs. */
    private static HttpResponse<String> send(String body, String... headers) throws Exception {
        return sendTo("conv_1", body, headers);
    }

    private static HttpResponse<String> sendTo(String conversationId, String body, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + port + "/v1/conversations/" + conversationId + "/messages"))
                .header("Authorization", TestApi.AUTHORIZATION).header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30)).POST(BodyPublishers.ofString(body));
        for (int i = 0; i < headers.length; i += 2) {
            request.setHeader(headers[i], headers[i + 1]);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A body written by Jackson, so that every string is escaped properly. */
    private static String body(Map<String, Object> fields) {
        return JSON.writeValueAsString(fields);
    }

    private static Message answered(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).contains("application/json");
        return JSON.readValue(response.body(), Message.class);
    }

    private static Problem problem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).contains("application/problem+json");
        Problem problem = JSON.readValue(response.body(), Problem.class);
        assertThat(problem.getCode()).isEqualTo(code);
        return problem;
    }

    private static void assertRefused(HttpResponse<String> response, String detail) {
        assertThat(problem(response, 400, "invalid_request").getDetail()).isEqualTo(detail);
        assertThat(ANSWERS.calls).as("a refused request asks nothing").isEmpty();
    }

    @Test
    void aMessageComesBackWithItsWholeAnswer() throws Exception {
        HttpResponse<String> response = send("{\"text\":\"Hi\"}");

        Message answer = answered(response);
        assertThat(response.headers().firstValue("Request-Id")).isPresent();
        assertThat(answer.getId()).startsWith("msg_a");
        assertThat(answer.getConversationId()).isEqualTo("conv_1");
        assertThat(answer.getRole()).isEqualTo("assistant");
        assertThat(answer.getStatus()).isEqualTo("completed");
        assertThat(answer.getText()).isEqualTo("Hello!");
        assertThat(answer.getUsage()).isEqualTo(new Usage(6L, 2L));
        assertThat(answer.getCode()).isNull();
        assertThat(response.body()).contains("\"created_at\":\"2026-11-14T00:00:00Z\"", "\"completed_at\":\"2026-11-14T00:00:01Z\"")
                .doesNotContain("\"code\"");
        assertThat(ANSWERS.calls).containsExactly("ten_test/app_test asks in conv_1: Hi");
    }

    /** The answer was stored before the model was asked, so a model that fails gives a failed answer, not an error. */
    @Test
    void aFailedAnswerComesBackWithItsCode() throws Exception {
        ANSWERS.outcome = Outcome.FAILED;

        HttpResponse<String> response = send("{\"text\":\"Hi\"}");

        Message answer = answered(response);
        assertThat(answer.getStatus()).isEqualTo("failed");
        assertThat(answer.getCode()).isEqualTo("model_failed");
        assertThat(answer.getText()).isEqualTo("Hel");
        assertThat(answer.getUsage()).isNull();
        assertThat(response.body()).contains("\"completed_at\":\"2026-11-14T00:00:01Z\"").doesNotContain("\"usage\"");
    }

    @Test
    void theCallWaitsForAnAnswerThatEndsWithinTheWait() throws Exception {
        ANSWERS.outcome = Outcome.COMPLETES_SOON;

        Message answer = answered(send("{\"text\":\"Hi\"}"));

        assertThat(answer.getStatus()).isEqualTo("completed");
        assertThat(answer.getText()).isEqualTo("Hello!");
    }

    @Test
    void anAnswerThatOutlastsTheWaitComesBackInProgress() throws Exception {
        ANSWERS.outcome = Outcome.NEVER_ENDS;

        long start = System.nanoTime();
        HttpResponse<String> response = send("{\"text\":\"Hi\"}");
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        Message answer = answered(response);
        assertThat(answer.getStatus()).isEqualTo("in_progress");
        assertThat(answer.getText()).isEqualTo("Hel");
        assertThat(response.body()).doesNotContain("\"completed_at\"", "\"usage\"");
        assertThat(took).isBetween(TestApi.WAIT.limit(), Duration.ofSeconds(10));
    }

    /** Nothing was stored, so the same Idempotency-Key may come back once the instance has room again. */
    @Test
    void aFullInstanceAnswers429WithRetryAfter() throws Exception {
        ANSWERS.outcome = Outcome.BUSY;

        HttpResponse<String> response = send("{\"text\":\"Hi\"}");

        Problem problem = problem(response, 429, "rate_limited");
        assertThat(problem.getDetail()).isEqualTo("This instance answers as many messages as it can, so try again after Retry-After");
        assertThat(response.headers().firstValue("Retry-After")).contains("5");
    }

    @Test
    void aConversationOutOfReachIsNotFound() throws Exception {
        ANSWERS.outcome = Outcome.MISSING;

        problem(send("{\"text\":\"Hi\"}"), 404, "not_found");
    }

    @Test
    void theTextIsRequired() throws Exception {
        assertRefused(send("{}"), "text is required");
    }

    @Test
    void theTextHas1To32000Characters() throws Exception {
        for (String text : List.of("", "a".repeat(32001), ASTRAL.repeat(32001))) {
            assertRefused(send(body(Map.of("text", text))), TEXT_RULE);
        }

        Message answer = answered(send(body(Map.of("text", ASTRAL.repeat(32000)))));
        assertThat(answer.getStatus()).isEqualTo("completed");
        assertThat(ANSWERS.calls).singleElement().asString().endsWith(ASTRAL.repeat(32000));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\0", "Hello\0there"})
    void theTextHoldsNoNul(String text) throws Exception {
        assertRefused(send(body(Map.of("text", text))), "text cannot hold a NUL character");
    }

    /** The locale is checked now and used once prompts come (M3), so a good one asks with the text alone. */
    @Test
    void theLocaleIsChecked() throws Exception {
        assertRefused(send("{\"text\":\"Hi\",\"locale\":\"not a tag\"}"), LOCALE_RULE);

        answered(send("{\"text\":\"Hi\",\"locale\":\"ja-jp\"}"));
        assertThat(ANSWERS.calls).containsExactly("ten_test/app_test asks in conv_1: Hi");
    }

    @Test
    void theDataClassIsOneOfTheContracts() throws Exception {
        HttpResponse<String> refused = send("{\"text\":\"Hi\",\"data_class\":\"secret\"}");
        assertThat(problem(refused, 400, "invalid_request").getDetail()).startsWith("data_class must be one of: public");
        assertThat(ANSWERS.calls).isEmpty();

        answered(send("{\"text\":\"Hi\",\"data_class\":\"sensitive\"}"));
    }

    @Test
    void aMalformedConversationIdIsRefusedBeforeAnythingIsAsked() throws Exception {
        assertRefused(sendTo("conv_a-b", "{\"text\":\"Hi\"}"), ID_RULE);
    }

    private static String sse(long seq, String type, String data) {
        return "id: " + seq + "\nevent: " + type + "\ndata: " + data + "\n\n";
    }

    @Test
    void aCallerThatNamesTheStreamGetsTheEventsUntilTheEnding() throws Exception {
        HttpResponse<String> response = send("{\"text\":\"Hi\"}", "Accept", "text/event-stream");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(type -> assertThat(type).startsWith("text/event-stream"));
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-cache");
        assertThat(response.headers().firstValue("X-Accel-Buffering")).contains("no");
        assertThat(response.headers().firstValue("Request-Id")).isPresent();
        String answer = ANSWERS.answers.keySet().iterator().next();
        assertThat(response.body()).isEqualTo(sse(1, "message.started", "{\"message_id\":\"" + answer + "\"}")
                + sse(2, "message.delta", "{\"message_id\":\"" + answer + "\",\"text\":\"Hello!\"}")
                + sse(3, "message.completed", "{\"message_id\":\"" + answer + "\",\"usage\":{\"input_tokens\":6,\"output_tokens\":2}}"));
        assertThat(ANSWERS.calls).containsExactly("ten_test/app_test asks in conv_1: Hi");
    }

    /** The model failed after the answer had started, so its stream ends with the error event, not with a problem. */
    @Test
    void aFailedAnswerStreamsItsError() throws Exception {
        ANSWERS.outcome = Outcome.FAILED;

        HttpResponse<String> response = send("{\"text\":\"Hi\"}", "Accept", "text/event-stream");

        assertThat(response.statusCode()).isEqualTo(200);
        String last = response.body().split("\n\n")[2];
        assertThat(last).startsWith("id: 3\nevent: error\ndata: ").contains("\"code\":\"model_failed\"", "\"status\":502");
    }

    /** M2-7 decision 2: a caller that names text/event-stream wants the stream, even when it takes JSON too. */
    @ParameterizedTest
    @ValueSource(strings = {"text/event-stream", "text/event-stream, application/json", "application/json, text/event-stream",
        "application/json;q=0.9, text/event-stream;q=0.1"})
    void aCallerThatNamesTheStreamGetsIt(String accept) throws Exception {
        HttpResponse<String> response = send("{\"text\":\"Hi\"}", "Accept", accept);

        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(type -> assertThat(type).startsWith("text/event-stream"));
        assertThat(response.body()).startsWith("id: 1\nevent: message.started\n");
    }

    @ParameterizedTest
    @ValueSource(strings = {"*/*", "application/*", "application/json", "text/event-stream;q=0, application/json"})
    void aCallerThatTakesJsonGetsTheAnswer(String accept) throws Exception {
        assertThat(answered(send("{\"text\":\"Hi\"}", "Accept", accept)).getStatus()).isEqualTo("completed");
    }

    /** text/* meets the stream's type, yet names no stream, and it takes no JSON either. */
    @Test
    void aWildcardForTextIsTurnedAwayBeforeAnythingIsAsked() throws Exception {
        HttpResponse<String> response = send("{\"text\":\"Hi\"}", "Accept", "text/*");

        assertThat(problem(response, 406, "invalid_request").getDetail())
                .isEqualTo("The answer comes as application/json, or as text/event-stream when Accept names it");
        assertThat(ANSWERS.calls).isEmpty();
    }

    /** M2-10-2: a stream takes a key now. StreamedRetriesTest checks what the key keeps, and the retries. */
    @Test
    void aStreamWithAnIdempotencyKeyStreams() throws Exception {
        HttpResponse<String> response = send("{\"text\":\"Hi\"}", "Accept", "text/event-stream", "Idempotency-Key", "s-1");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(type -> assertThat(type).startsWith("text/event-stream"));
        assertThat(response.body()).contains("event: message.started", "event: message.completed");
        assertThat(ANSWERS.calls).containsExactly("ten_test/app_test asks in conv_1: Hi");
    }

    /** What is refused is refused before the stream starts, as a plain problem. */
    @Test
    void aRefusedStreamIsAPlainProblem() throws Exception {
        assertRefused(send("{\"text\":\"\"}", "Accept", "text/event-stream"), TEXT_RULE);

        ANSWERS.outcome = Outcome.MISSING;
        problem(send("{\"text\":\"Hi\"}", "Accept", "text/event-stream"), 404, "not_found");

        ANSWERS.outcome = Outcome.BUSY;
        HttpResponse<String> full = send("{\"text\":\"Hi\"}", "Accept", "text/event-stream");
        problem(full, 429, "rate_limited");
        assertThat(full.headers().firstValue("Retry-After")).contains("5");
    }

    /**
     * Code point 34: the log fails once the stream has started, so the stream is cut without an ending, and the failure is
     * logged once. The client resumes later (M2-8).
     */
    @Test
    void aFailureInTheMiddleOfTheStreamCutsIt(CapturedOutput output) throws Exception {
        ANSWERS.outcome = Outcome.NEVER_ENDS;
        TestApi.StandInMessages log = (TestApi.StandInMessages) context.getBean(aie.modules.conversation.api.Messages.class);
        log.readsLeft.set(1);
        try {
            assertThatThrownBy(() -> send("{\"text\":\"Hi\"}", "Accept", "text/event-stream")).isInstanceOf(IOException.class);
        } finally {
            log.readsLeft.set(-1);
        }

        assertThat(output.getOut().lines().filter(line -> line.contains(" ERROR "))).singleElement().asString()
                .contains("The request failed after its answer had started, so its connection is cut");
        assertThat(output.getOut()).contains("The stand-in's database went away").doesNotContain("Servlet.service()");
    }

    @Test
    void anAcceptNoTypeOfTheOperationMeetsAsksNothing() throws Exception {
        problem(send("{\"text\":\"Hi\"}", "Accept", "application/xml"), 406, "invalid_request");

        assertThat(ANSWERS.calls).isEmpty();
    }

    @Test
    void onBehalfOfAsksAsTheLowerTenantsApplication() throws Exception {
        answered(send("{\"text\":\"Hi\"}", "On-Behalf-Of", "ten_lower"));

        assertThat(ANSWERS.calls).containsExactly("ten_lower/app_lowertest asks in conv_1: Hi");
    }

    /** Browser tokens come in M2-12-3. Until then a valid session token is forbidden here, and nothing is asked. */
    @Test
    void aSessionTokenIsNotTakenYet() throws Exception {
        String token = TestApi.SESSION_TOKENS.issue(new SessionTokens.Session(
                "ten_test", "app_test", "eu_1", IdentityLevel.VERIFIED, List.of(), Map.of(), null)).token();

        problem(send("{\"text\":\"Hi\"}", "Authorization", "Bearer " + token), 403, "forbidden");
        assertThat(ANSWERS.calls).isEmpty();
    }

    @Test
    void theWaitIsTheContracts120Seconds() {
        assertThat(AnswerWait.CONTRACT.limit()).isEqualTo(Duration.ofSeconds(120));
        assertThatThrownBy(() -> new AnswerWait(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AnswerWait(Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AnswerWait(null)).isInstanceOf(NullPointerException.class);
    }
}
