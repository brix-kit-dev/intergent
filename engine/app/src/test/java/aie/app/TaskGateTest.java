package aie.app;

import static org.assertj.core.api.Assertions.assertThat;

import aie.core.tenancy.api.IdempotencyKeys;
import aie.http.StreamLimit;
import aie.http.api.model.Problem;
import aie.modules.conversation.api.Answers;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * M3-4-4: the gate of blueprint 9.1 in front of sending a message, on the real web stack with stand-ins. Where the
 * deployment does not offer the conversation module's task, a message is 403 forbidden after identity and before the
 * data class: nothing is asked, read or sent again. Which tasks are offered is the region pack's policy, not this code's.
 */
@Timeout(30)
class TaskGateTest {

    private static final TestApi.StandInAnswers ANSWERS = new TestApi.StandInAnswers();
    /** Conversations of browsers' end users, which a browser's request looks up before it does anything (M2-12-3). */
    private static final TestApi.StandInConversations CONVERSATIONS = new TestApi.StandInConversations();
    private static final TestApi.StandInApplications APPLICATIONS = new TestApi.StandInApplications();
    private static final StreamedRetriesTest.RecordingKeys KEYS = new StreamedRetriesTest.RecordingKeys();
    /** Whether the gate opens the conversation module's task. It opens no other task, ever. */
    private static final AtomicBoolean OPEN = new AtomicBoolean();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String REFUSAL = "This deployment does not offer the task customer_chat";

    private static ConfigurableApplicationContext context;
    private static int port;

    @BeforeAll
    static void start() {
        context = TestApi.start(TestApi.LOOKUP, new TestApi.StandInKeys(), new TestApi.StandInEndUsers(), CONVERSATIONS,
                ANSWERS, KEYS, StreamLimit.DEFAULT, TestApi.WAIT, APPLICATIONS,
                task -> OPEN.get() && Answers.TASK.equals(task), "--server.port=0");
        port = TestApi.port(context);
    }

    @AfterAll
    static void stop() {
        context.close();
    }

    @BeforeEach
    void closeTheGate() {
        OPEN.set(false);
        ANSWERS.calls.clear();
        APPLICATIONS.calls.clear();
        KEYS.calls.clear();
        KEYS.next = new IdempotencyKeys.Claim.Acquired();
    }

    /** POST .../messages of the conversation, with this credential and body, and any further headers, in pairs. */
    private static HttpResponse<String> send(String authorization, String conversation, String body, String... headers)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + port + "/v1/conversations/" + conversation + "/messages"))
                .header("Content-Type", "application/json").timeout(Duration.ofSeconds(20)).POST(BodyPublishers.ofString(body));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        for (int i = 0; i < headers.length; i += 2) {
            request.setHeader(headers[i], headers[i + 1]);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Problem forbidden(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("Content-Type")).contains("application/problem+json");
        Problem problem = JSON.readValue(response.body(), Problem.class);
        assertThat(problem.getCode()).isEqualTo("forbidden");
        return problem;
    }

    /**
     * Decision 4: 403 forbidden, with a detail that says the service is not offered here. It comes before the data class,
     * so the application's default class is not read, and nothing is asked.
     */
    @Test
    void aTaskTheDeploymentDoesNotOfferIs403Forbidden() throws Exception {
        HttpResponse<String> response = send(TestApi.AUTHORIZATION, "conv_1", "{\"text\":\"Hi\",\"data_class\":\"personal\"}");

        assertThat(forbidden(response).getDetail()).isEqualTo(REFUSAL);
        assertThat(ANSWERS.calls).isEmpty();
        assertThat(APPLICATIONS.calls).isEmpty();
    }

    /** A streamed request is refused before its stream starts, as a plain problem. */
    @Test
    void aStreamIsRefusedBeforeItStarts() throws Exception {
        HttpResponse<String> response = send(TestApi.AUTHORIZATION, "conv_1", "{\"text\":\"Hi\"}", "Accept", "text/event-stream");

        assertThat(forbidden(response).getDetail()).isEqualTo(REFUSAL);
        assertThat(ANSWERS.calls).isEmpty();
    }

    /** Not even an answer streamed earlier under the same Idempotency-Key goes out again through a closed gate. */
    @Test
    void noEarlierAnswerIsSentAgainThroughAClosedGate() throws Exception {
        KEYS.next = new IdempotencyKeys.Claim.Streamed("msg_a1");

        HttpResponse<String> response = send(TestApi.AUTHORIZATION, "conv_1", "{\"text\":\"Hi\"}", "Accept", "text/event-stream",
                "Idempotency-Key", "k-1");

        assertThat(forbidden(response).getDetail()).isEqualTo(REFUSAL);
        assertThat(response.body()).doesNotContain("message.started");
        assertThat(ANSWERS.calls).isEmpty();
    }

    /** Where the task is offered, the same request goes on and is answered. */
    @Test
    void anOfferedTaskGoesOn() throws Exception {
        OPEN.set(true);

        HttpResponse<String> response = send(TestApi.AUTHORIZATION, "conv_1", "{\"text\":\"Hi\"}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(ANSWERS.calls).containsExactly("ten_test/app_test asks in conv_1: Hi");
    }

    /**
     * The gate stands after identity: a request without a key is 401, and a browser's request in another end user's
     * conversation is 404, as before, whether or not the task is offered.
     */
    @Test
    void theGateStandsAfterIdentity() throws Exception {
        String conversation = CONVERSATIONS.start(TestApi.OWNER, "eu_1", null).conversationId();

        assertThat(send(null, "conv_1", "{\"text\":\"Hi\"}").statusCode()).isEqualTo(401);
        assertThat(send("Bearer " + TestApi.sessionToken("eu_2"), conversation, "{\"text\":\"Hi\"}").statusCode()).isEqualTo(404);
        assertThat(forbidden(send("Bearer " + TestApi.sessionToken("eu_1"), conversation, "{\"text\":\"Hi\"}")).getDetail())
                .isEqualTo(REFUSAL);
        assertThat(ANSWERS.calls).isEmpty();
    }

    /** What is wrong with the request itself is said first, as for any refusal. */
    @Test
    void aRequestThatIsNotWellFormedIs400First() throws Exception {
        assertThat(send(TestApi.AUTHORIZATION, "conv_1", "{\"text\":\"\"}").statusCode()).isEqualTo(400);
    }
}
