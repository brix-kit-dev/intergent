package aie.app;

import static org.assertj.core.api.Assertions.assertThat;

import aie.app.TestApi.StandInAnswers.Outcome;
import aie.core.tenancy.api.IdempotencyKeys;
import aie.core.trust.api.DataClass;
import aie.modules.conversation.api.Answers;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * M2-10-2 on the real web stack: a streamed message with an Idempotency-Key, with stand-in answers and stand-in keys that
 * a test steers and that remember what the filter asked of them. The keys on a real database are in IdempotencyKeysTest,
 * and a retry on another instance in ApiRoleTest.
 */
@Timeout(30)
class StreamedRetriesTest {

    private static final TestApi.StandInAnswers ANSWERS = new TestApi.StandInAnswers();
    /** Conversations of browsers' end users, which a browser's request looks up before it does anything (M2-12-3). */
    private static final TestApi.StandInConversations CONVERSATIONS = new TestApi.StandInConversations();
    private static final RecordingKeys KEYS = new RecordingKeys();
    private static final String BODY = "{\"text\":\"Hi\"}";
    private static final String PATH = "/v1/conversations/conv_1/messages";

    private static ConfigurableApplicationContext context;
    private static int port;

    /** Answers each claim the way a test tells it, and remembers every call and every fingerprint. */
    static final class RecordingKeys implements IdempotencyKeys {

        final List<String> calls = new CopyOnWriteArrayList<>();
        final List<String> fingerprints = new CopyOnWriteArrayList<>();
        volatile Claim next = new Claim.Acquired();
        volatile boolean failToStream;

        @Override
        public Claim claim(Key key, String fingerprint) {
            calls.add("claim " + key.value());
            fingerprints.add(fingerprint);
            return next;
        }

        @Override
        public void finish(Key key, int status, String body) {
            calls.add("finish " + key.value() + " " + status);
        }

        @Override
        public void finishStreamed(Key key, String messageId) {
            calls.add("streamed " + key.value() + " " + messageId);
            if (failToStream) {
                throw new IllegalStateException("The stand-in's database went away");
            }
        }

        @Override
        public void release(Key key) {
            calls.add("release " + key.value());
        }
    }

    @BeforeAll
    static void start() {
        context = TestApi.start(TestApi.LOOKUP, new TestApi.StandInKeys(), new TestApi.StandInEndUsers(),
                CONVERSATIONS, ANSWERS, KEYS, "--server.port=0");
        port = TestApi.port(context);
    }

    @AfterAll
    static void stop() {
        context.close();
    }

    @BeforeEach
    void forgetEarlierCalls() {
        ANSWERS.calls.clear();
        ANSWERS.answers.clear();
        ANSWERS.questions.clear();
        ANSWERS.events.clear();
        ANSWERS.outcome = Outcome.COMPLETED;
        KEYS.calls.clear();
        KEYS.fingerprints.clear();
        KEYS.next = new IdempotencyKeys.Claim.Acquired();
        KEYS.failToStream = false;
    }

    /** POST .../messages of conv_1 with BODY, the test's server key and any further headers, in pairs. */
    private static HttpResponse<String> send(String... headers) throws Exception {
        return sendTo(PATH, headers);
    }

    /** The same, to another conversation's messages. */
    private static HttpResponse<String> sendTo(String path, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", TestApi.AUTHORIZATION).header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(20)).POST(BodyPublishers.ofString(BODY));
        for (int i = 0; i < headers.length; i += 2) {
            request.setHeader(headers[i], headers[i + 1]);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A streamed request with the key s-1. */
    private static HttpResponse<String> streamed() throws Exception {
        return send("Accept", "text/event-stream", "Idempotency-Key", "s-1");
    }

    private static String events(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("text/event-stream"));
        return response.body();
    }

    /** The SSE of an answer the stand-in completed: started, "Hello!" and completed. */
    private static String completed(String answer) {
        return "id: 1\nevent: message.started\ndata: {\"message_id\":\"" + answer + "\"}\n\n"
                + "id: 2\nevent: message.delta\ndata: {\"message_id\":\"" + answer + "\",\"text\":\"Hello!\"}\n\n"
                + "id: 3\nevent: message.completed\ndata: {\"message_id\":\"" + answer
                + "\",\"usage\":{\"input_tokens\":6,\"output_tokens\":2}}\n\n";
    }

    /** The one answer the stand-in made in this test; its number goes on from test to test. */
    private static String theAnswer() {
        assertThat(ANSWERS.answers).hasSize(1);
        return ANSWERS.answers.keySet().iterator().next();
    }

    /** The messages path of a new conversation of this browser end user. */
    private static String messagesOf(String endUserId) {
        return "/v1/conversations/" + CONVERSATIONS.start(TestApi.OWNER, endUserId, null).conversationId() + "/messages";
    }

    /**
     * A browser's request names its end user in its fingerprint (M2-12 decision 2), so another end user's request with
     * the same key and body is another request.
     */
    @Test
    void aBrowsersFingerprintNamesItsEndUser() throws Exception {
        String path = messagesOf("eu_browser1");

        events(sendTo(path, "Authorization", "Bearer " + TestApi.sessionToken("eu_browser1"), "Accept", "text/event-stream",
                "Idempotency-Key", "s-1"));

        assertThat(KEYS.fingerprints).containsExactly(sha256("end user eu_browser1\nstream\nPOST " + path + "\n" + BODY));
    }

    /**
     * A resend goes through a browser's check too (M2-12-3): were another end user's key ever to name a streamed answer, a
     * conversation that is not the browser's own is missing, and none of its events go out.
     */
    @Test
    void aResendIntoAnotherEndUsersConversationIsMissing() throws Exception {
        String path = messagesOf("eu_browser1");
        String conversation = path.split("/")[3];
        String answer = ((Answers.Start.Started) ANSWERS.ask(TestApi.OWNER, conversation, "Hi", DataClass.PUBLIC)).asked().answer().messageId();
        ANSWERS.calls.clear();
        KEYS.next = new IdempotencyKeys.Claim.Streamed(answer);

        HttpResponse<String> response = sendTo(path, "Authorization", "Bearer " + TestApi.sessionToken("eu_browser2"),
                "Accept", "text/event-stream", "Idempotency-Key", "s-1");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("Content-Type")).contains("application/problem+json");
        assertThat(response.body()).doesNotContain(answer);
        assertThat(ANSWERS.calls).isEmpty();
    }

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    /** The key keeps the answer's message, before the first event, and no body: the stream goes out as it is written. */
    @Test
    void aStreamedAnswerIsKeptAsItsMessage() throws Exception {
        String body = events(streamed());

        String answer = theAnswer();
        assertThat(body).isEqualTo(completed(answer));
        assertThat(KEYS.calls).containsExactly("claim s-1", "streamed s-1 " + answer);
        assertThat(ANSWERS.calls).containsExactly("ten_test/app_test asks in conv_1: Hi");
    }

    /** A retry asks nothing: it sends the kept answer's events again, from the first. */
    @Test
    void aRetrySendsTheKeptAnswerAgain() throws Exception {
        String kept = ((Answers.Start.Started) ANSWERS.ask(TestApi.OWNER, "conv_1", "Hi", DataClass.PUBLIC)).asked().answer().messageId();
        ANSWERS.calls.clear();
        KEYS.next = new IdempotencyKeys.Claim.Streamed(kept);

        String body = events(streamed());

        assertThat(body).isEqualTo(completed(kept));
        assertThat(ANSWERS.calls).as("a retry asks nothing").isEmpty();
        assertThat(KEYS.calls).containsExactly("claim s-1");
    }

    /** A retry while the answer is still being written follows it to its ending. */
    @Test
    void aRetryFollowsAnAnswerStillWritten() throws Exception {
        ANSWERS.outcome = Outcome.COMPLETES_SOON;
        String kept = ((Answers.Start.Started) ANSWERS.ask(TestApi.OWNER, "conv_1", "Hi", DataClass.PUBLIC)).asked().answer().messageId();
        KEYS.next = new IdempotencyKeys.Claim.Streamed(kept);

        assertThat(events(streamed())).isEqualTo(completed(kept));
    }

    /**
     * M2-10 decision 1: a request that asks for a stream is another request than the same one asking for JSON, so its
     * fingerprint differs; that of a request asking for JSON is the one the keys stored before streams took a key.
     */
    @Test
    void aStreamIsAnotherRequestThanTheSameAskingForJson() throws Exception {
        send("Idempotency-Key", "s-1");
        send("Accept", "text/event-stream", "Idempotency-Key", "s-1");
        send("Accept", "text/event-stream;q=0, application/json", "Idempotency-Key", "s-1");

        String json = sha256("POST " + PATH + "\n" + BODY);
        assertThat(KEYS.fingerprints).containsExactly(json, sha256("stream\nPOST " + PATH + "\n" + BODY), json);
    }

    /** What is refused before the stream is stored as any answer is, and a retry gets it again. */
    @Test
    void aRefusalBeforeTheStreamIsStoredAsAnyAnswer() throws Exception {
        ANSWERS.outcome = Outcome.MISSING;

        HttpResponse<String> response = streamed();

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(KEYS.calls).containsExactly("claim s-1", "finish s-1 404");
    }

    /** A full instance did nothing, so the key is let go, and the same key may come back after Retry-After. */
    @Test
    void aFullInstanceLetsTheKeyGo() throws Exception {
        ANSWERS.outcome = Outcome.BUSY;

        HttpResponse<String> response = streamed();

        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(KEYS.calls).containsExactly("claim s-1", "release s-1");
    }

    /** A key that cannot keep the answer's message costs the caller nothing: the stream goes out, and the error is logged. */
    @Test
    void aStreamGoesOutWhenItsKeyCannotKeepIt() throws Exception {
        KEYS.failToStream = true;

        String body = events(streamed());

        String answer = theAnswer();
        assertThat(body).isEqualTo(completed(answer));
        assertThat(KEYS.calls).containsExactly("claim s-1", "streamed s-1 " + answer);
    }

    @Test
    void aStreamWithoutAKeyTouchesNoKey() throws Exception {
        assertThat(events(send("Accept", "text/event-stream"))).isEqualTo(completed(theAnswer()));

        assertThat(KEYS.calls).isEmpty();
    }

    /** A JSON request with a key keeps its body as before, and never its message. */
    @Test
    void aJsonAnswerIsStillKeptAsItsBody() throws Exception {
        HttpResponse<String> response = send("Idempotency-Key", "s-1");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(KEYS.calls).containsExactly("claim s-1", "finish s-1 200");
    }
}
