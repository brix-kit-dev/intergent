package aie.app;

import static org.assertj.core.api.Assertions.assertThat;

import aie.core.tenancy.api.IdempotencyKeys;
import aie.http.StreamLimit;
import aie.http.api.model.MessageStartedEvent;
import aie.http.api.model.Problem;
import aie.modules.conversation.api.Messages;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * Point 40: an instance keeps at most its stream limit of event streams open, here 2. A stream takes a slot before it
 * asks, reads or sends again anything, and gives it back however it ends; a stream that finds none free is 429 with
 * Retry-After, and has done nothing. On the real web stack with stand-ins, whose answers stay in progress until a test
 * ends them.
 */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class StreamLimitTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TestApi.StandInAnswers ANSWERS = new TestApi.StandInAnswers();
    /** Idempotency-Keys whose retry joins an answer's stream, as a retry of a streamed request does (M2-10-2). */
    private static final Map<String, String> JOINS = new ConcurrentHashMap<>();
    private static final List<String> RELEASED = new CopyOnWriteArrayList<>();
    private static final IdempotencyKeys KEYS_THAT_JOIN = new IdempotencyKeys() {

        @Override
        public Claim claim(Key key, String fingerprint) {
            String answer = JOINS.get(key.value());
            return answer == null ? new Claim.Acquired() : new Claim.Streamed(answer);
        }

        @Override
        public void finish(Key key, int status, String body) {
        }

        @Override
        public void finishStreamed(Key key, String messageId) {
        }

        @Override
        public void release(Key key) {
            RELEASED.add(key.value());
        }
    };

    private static ConfigurableApplicationContext context;
    private static TestApi.StandInMessages messages;
    private static int port;

    /** The streams a test opened, so that each ends whatever the test leaves open. */
    private final List<Stream> opened = new ArrayList<>();

    /** A stream as it is being read: its answer and what is left of its body. */
    record Stream(String conversationId, String answerId, BufferedReader body) {
    }

    @BeforeAll
    static void start() {
        context = TestApi.start(TestApi.LOOKUP, new TestApi.StandInKeys(), new TestApi.StandInEndUsers(),
                new TestApi.StandInConversations(), ANSWERS, KEYS_THAT_JOIN, new StreamLimit(2), "--server.port=0");
        messages = (TestApi.StandInMessages) context.getBean(Messages.class);
        port = TestApi.port(context);
    }

    @AfterAll
    static void stop() {
        context.close();
    }

    @BeforeEach
    void answersStayInProgress() {
        ANSWERS.outcome = TestApi.StandInAnswers.Outcome.NEVER_ENDS;
        ANSWERS.cancelling = TestApi.StandInAnswers.Cancelling.AT_ONCE;
        ANSWERS.calls.clear();
        JOINS.clear();
        RELEASED.clear();
        messages.readsLeft.set(-1);
    }

    /** Every stream a test left open ends, so that the next test finds every slot free. */
    @AfterEach
    void endTheStreams() throws IOException {
        for (Stream stream : List.copyOf(opened)) {
            end(stream);
        }
    }

    private static HttpRequest.Builder at(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).header("Authorization", TestApi.AUTHORIZATION);
    }

    private static HttpRequest ask(String conversationId, String accept, String... headers) {
        HttpRequest.Builder request = at("/v1/conversations/" + conversationId + "/messages").header("Accept", accept)
                .header("Content-Type", "application/json").POST(BodyPublishers.ofString("{\"text\":\"Hi\"}"));
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return request.build();
    }

    private static HttpRequest resume(String conversationId, String answerId) {
        return at("/v1/conversations/" + conversationId + "/messages/" + answerId + "/events").GET().build();
    }

    /** A streamed ask that got its stream: the first event names the answer, and the rest stays to be read. */
    private Stream open(String conversationId) throws Exception {
        HttpResponse<InputStream> response = HttpClient.newHttpClient().send(ask(conversationId, "text/event-stream"),
                HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        BufferedReader body = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
        String started = nextEvent(body);
        assertThat(started).contains("event: message.started");
        String data = started.lines().filter(line -> line.startsWith("data: ")).findFirst().orElseThrow();
        Stream stream = new Stream(conversationId, JSON.readValue(data.substring("data: ".length()),
                MessageStartedEvent.class).getMessageId(), body);
        opened.add(stream);
        return stream;
    }

    /** Its answer ends as cancelled, so the stream sends that and ends, and its slot comes back before the body ends. */
    private void end(Stream stream) throws IOException {
        opened.remove(stream);
        ANSWERS.cancel(TestApi.OWNER, stream.conversationId(), stream.answerId());
        while (stream.body().readLine() != null) {
            // The rest of the stream, up to its end.
        }
        stream.body().close();
    }

    /** The next event of a stream that stays open, without its blank line, or null once it has ended. */
    private static String nextEvent(BufferedReader body) throws IOException {
        StringBuilder event = new StringBuilder();
        for (String line = body.readLine(); line != null; line = body.readLine()) {
            if (!line.isEmpty()) {
                if (!line.startsWith(":")) {
                    event.append(event.isEmpty() ? "" : "\n").append(line);
                }
            } else if (!event.isEmpty()) {
                return event.toString();
            }
        }
        return event.isEmpty() ? null : event.toString();
    }

    private static void assertFull(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.headers().firstValue("Content-Type")).contains("application/problem+json");
        assertThat(response.headers().firstValue("Retry-After")).contains("5");
        Problem problem = JSON.readValue(response.body(), Problem.class);
        assertThat(problem.getCode()).isEqualTo("rate_limited");
        assertThat(problem.getDetail()).isEqualTo("This instance has as many event streams open as it may, so try again after Retry-After");
    }

    private static HttpResponse<String> sendForString(HttpRequest request) throws Exception {
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** A streamed ask on a full instance asks nothing, so nothing is stored; once a stream ends, the next one opens. */
    @Test
    void aFullInstanceStartsNoMoreStreamsUntilOneEnds() throws Exception {
        Stream first = open("conv_1");
        open("conv_2");
        ANSWERS.calls.clear();

        assertFull(sendForString(ask("conv_3", "text/event-stream")));
        assertThat(ANSWERS.calls).as("nothing is asked for a stream that cannot open").isEmpty();

        end(first);
        assertThat(open("conv_3").answerId()).startsWith("msg_");
    }

    /** A resumed stream takes a slot too, before it reads anything of the log. */
    @Test
    void aResumeWaitsForASlotLikeAnyStream() throws Exception {
        Stream first = open("conv_1");
        open("conv_2");
        int reads = messages.reads(first.answerId());

        assertFull(sendForString(resume(first.conversationId(), first.answerId())));
        assertThat(messages.reads(first.answerId())).as("a stream that cannot open reads nothing").isEqualTo(reads);
    }

    /** A streamed retry that would join its answer's stream takes a slot too, and the key stays for when one is free. */
    @Test
    void aStreamedRetryWaitsForASlotAndKeepsItsKey() throws Exception {
        Stream first = open("conv_1");
        open("conv_2");
        JOINS.put("retry-1", first.answerId());
        ANSWERS.calls.clear();

        assertFull(sendForString(ask("conv_1", "text/event-stream", "Idempotency-Key", "retry-1")));
        assertThat(ANSWERS.calls).isEmpty();
        assertThat(RELEASED).as("the key still names the answer, so a later retry joins it").isEmpty();
    }

    /** Only a stream takes a slot: a call that wants JSON, and HEAD of the events, go through on a full instance. */
    @Test
    void whatSendsNoStreamTakesNoSlot() throws Exception {
        Stream first = open("conv_1");
        open("conv_2");

        assertThat(sendForString(ask("conv_3", "application/json")).statusCode()).isEqualTo(200);
        assertThat(HttpClient.newHttpClient().send(at("/v1/conversations/" + first.conversationId() + "/messages/"
                + first.answerId() + "/events").method("HEAD", BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);
    }

    /** A caller that went away gives its slot back once its stream finds that out, at its next write. */
    @Test
    void aStreamWhoseCallerWentAwayGivesItsSlotBack() throws Exception {
        Stream gone = open("conv_1");
        open("conv_2");
        gone.body().close();
        opened.remove(gone);

        ANSWERS.cancel(TestApi.OWNER, gone.conversationId(), gone.answerId());

        assertThat(openWithin(Duration.ofSeconds(5), "conv_3")).as("a stream opens once the slot is back").isNotNull();
    }

    /**
     * A stream whose log cannot be read is cut (code point 34), and gives its slot back all the same: both slots are free
     * again afterwards. Its first read works and its second fails; no other stream reads meanwhile.
     */
    @Test
    void aStreamThatFailsGivesItsSlotBack() throws Exception {
        messages.readsLeft.set(1);
        HttpResponse<InputStream> failing = HttpClient.newHttpClient().send(ask("conv_1", "text/event-stream"),
                HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = failing.body()) {
            body.readAllBytes();
        } catch (IOException cut) {
            // The connection was cut, as it is for a failure after the response started.
        }
        messages.readsLeft.set(-1);

        assertThat(openWithin(Duration.ofSeconds(5), "conv_2")).isNotNull();
        assertThat(openWithin(Duration.ofSeconds(5), "conv_3")).as("the failed stream's slot is free too").isNotNull();
    }

    /** Many streams at once take exactly the slots, and every slot comes back: the count is neither short nor over. */
    @Test
    void manyStreamsAtOnceTakeExactlyTheSlots() throws Exception {
        List<CompletableFuture<HttpResponse<InputStream>>> asks = IntStream.range(0, 20)
                .mapToObj(i -> HttpClient.newHttpClient().sendAsync(ask("conv_" + i, "text/event-stream"),
                        HttpResponse.BodyHandlers.ofInputStream()))
                .toList();
        List<HttpResponse<InputStream>> answered = new ArrayList<>();
        for (CompletableFuture<HttpResponse<InputStream>> ask : asks) {
            answered.add(ask.get(30, TimeUnit.SECONDS));
        }

        assertThat(answered.stream().filter(response -> response.statusCode() == 200)).hasSize(2);
        assertThat(answered.stream().filter(response -> response.statusCode() == 429)).hasSize(18);
        for (HttpResponse<InputStream> response : answered) {
            if (response.statusCode() == 200) {
                BufferedReader body = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
                String data = nextEvent(body).lines().filter(line -> line.startsWith("data: ")).findFirst().orElseThrow();
                String conversation = response.request().uri().getPath().split("/")[3];
                opened.add(new Stream(conversation, JSON.readValue(data.substring("data: ".length()),
                        MessageStartedEvent.class).getMessageId(), body));
            } else {
                response.body().close();
            }
        }
        endTheStreams();

        open("conv_a");
        open("conv_b");
        assertFull(sendForString(ask("conv_c", "text/event-stream")));
    }

    /** A stream that opens within the time, or null. A slot that a stream gives back is free at most a moment later. */
    private Stream openWithin(Duration time, String conversationId) throws Exception {
        long until = System.nanoTime() + time.toNanos();
        while (System.nanoTime() < until) {
            HttpResponse<InputStream> response = HttpClient.newHttpClient().send(ask(conversationId, "text/event-stream"),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() == 200) {
                BufferedReader body = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
                String data = nextEvent(body).lines().filter(line -> line.startsWith("data: ")).findFirst().orElseThrow();
                Stream stream = new Stream(conversationId, JSON.readValue(data.substring("data: ".length()),
                        MessageStartedEvent.class).getMessageId(), body);
                opened.add(stream);
                return stream;
            }
            response.body().close();
            Thread.sleep(50);
        }
        return null;
    }
}
