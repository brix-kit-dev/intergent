package aie.http;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import aie.core.tenancy.api.ApiKeyLookup.Owner;
import aie.http.EventStream.Ended;
import aie.modules.conversation.api.Messages;
import aie.modules.conversation.api.Messages.ErrorCode;
import aie.modules.conversation.api.Messages.Event;
import aie.modules.conversation.api.Messages.EventType;
import aie.ports.DatabaseException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.json.JsonMapper;

/**
 * The event stream with a log a test scripts and a clock that moves only when the stream waits, so that every rule of
 * time is checked exactly, without waiting for it. Each test takes a moment, so ten seconds mean a stream that never ends.
 */
@Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class EventStreamTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Owner CALLER = new Owner("key_1", "ten_1", "app_1");
    private static final String CONVERSATION = "conv_1";
    private static final String ANSWER = "msg_1";
    private static final Instant T0 = Instant.parse("2026-11-14T00:00:00Z");
    private static final String HEARTBEAT = ": heartbeat\n\n";

    private final Time time = new Time();
    private final Log log = new Log(time);

    /** A clock that moves only when the stream sleeps, and remembers each sleep. */
    private static final class Time extends Clock {

        private Instant now = T0;
        private final List<Duration> sleeps = new ArrayList<>();

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void sleep(Duration span) {
            sleeps.add(span);
            now = now.plus(span);
        }
    }

    /** An event log in which each event shows from its own moment on. It remembers each read. */
    private static final class Log {

        private final Time time;
        private final List<Event> events = new ArrayList<>();
        private final Map<Long, Instant> shownFrom = new HashMap<>();
        private final List<String> reads = new ArrayList<>();
        private RuntimeException failure;

        Log(Time time) {
            this.time = time;
        }

        Log at(Duration moment, Event event) {
            events.add(event);
            shownFrom.put(event.seq(), T0.plus(moment));
            return this;
        }

        Messages messages() {
            return (Messages) Proxy.newProxyInstance(Messages.class.getClassLoader(), new Class<?>[] {Messages.class},
                    (proxy, method, arguments) -> {
                        if (!method.getName().equals("eventsAfter")) {
                            throw new UnsupportedOperationException(method.getName());
                        }
                        if (failure != null) {
                            throw failure;
                        }
                        Owner caller = (Owner) arguments[0];
                        long after = (long) arguments[3];
                        int limit = (int) arguments[4];
                        reads.add(caller.tenantId() + "/" + caller.applicationId() + " " + arguments[1] + " " + arguments[2]
                                + " after " + after + " at most " + limit);
                        return events.stream()
                                .filter(event -> event.seq() > after && !shownFrom.get(event.seq()).isAfter(time.now))
                                .limit(limit).toList();
                    });
        }
    }

    /** Counts its flushes. */
    private static final class Flushes extends ByteArrayOutputStream {

        private int count;

        @Override
        public void flush() {
            count++;
        }
    }

    private EventStream stream() {
        return new EventStream(log.messages(), EventStream.POLL, EventStream.HEARTBEAT, EventStream.LIMIT, time, time::sleep);
    }

    private String send(long after) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        stream().send(CALLER, CONVERSATION, ANSWER, after, out);
        return out.toString(UTF_8);
    }

    private static Event event(long seq, EventType type, String text, Messages.Usage usage, ErrorCode code) {
        return new Event(ANSWER, seq, type, text, usage, code, T0);
    }

    private static Event started(long seq) {
        return event(seq, EventType.MESSAGE_STARTED, null, null, null);
    }

    private static Event delta(long seq, String text) {
        return event(seq, EventType.MESSAGE_DELTA, text, null, null);
    }

    private static Event completed(long seq) {
        return event(seq, EventType.MESSAGE_COMPLETED, null, new Messages.Usage(6, 2), null);
    }

    private static String sse(long seq, String type, String data) {
        return "id: " + seq + "\nevent: " + type + "\ndata: " + data + "\n\n";
    }

    private static Duration seconds(double seconds) {
        return Duration.ofMillis(Math.round(seconds * 1000));
    }

    @Test
    void eachEventGoesOutAsSseUntilTheEnding() {
        log.at(Duration.ZERO, started(1)).at(Duration.ZERO, delta(2, "Hel")).at(Duration.ZERO, delta(3, "lo"))
                .at(Duration.ZERO, completed(4));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        Ended ended = stream().send(CALLER, CONVERSATION, ANSWER, 0, out);

        assertThat(ended).isEqualTo(Ended.ENDING_SENT);
        assertThat(out.toString(UTF_8)).isEqualTo(sse(1, "message.started", "{\"message_id\":\"msg_1\"}")
                + sse(2, "message.delta", "{\"message_id\":\"msg_1\",\"text\":\"Hel\"}")
                + sse(3, "message.delta", "{\"message_id\":\"msg_1\",\"text\":\"lo\"}")
                + sse(4, "message.completed", "{\"message_id\":\"msg_1\",\"usage\":{\"input_tokens\":6,\"output_tokens\":2}}"));
        assertThat(log.reads).containsExactly("ten_1/app_1 conv_1 msg_1 after 0 at most 100");
        assertThat(time.sleeps).isEmpty();
    }

    @Test
    void aCancelCarriesWhatWasGeneratedAndItsUsage() {
        log.at(Duration.ZERO, started(1))
                .at(Duration.ZERO, event(2, EventType.MESSAGE_CANCELLED, "Hel", new Messages.Usage(6, 1), null));

        assertThat(send(0)).endsWith(sse(2, "message.cancelled",
                "{\"message_id\":\"msg_1\",\"text\":\"Hel\",\"usage\":{\"input_tokens\":6,\"output_tokens\":1}}"));
    }

    /** M2-7 decision 1: 502 when the model failed upstream, 503 when the writer went away. Callers read the code. */
    @Test
    void anErrorIsAProblemWithItsCode() {
        for (ErrorCode code : ErrorCode.values()) {
            Log one = new Log(time).at(Duration.ZERO, event(1, EventType.ERROR, null, null, code));
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            Ended ended = new EventStream(one.messages(), EventStream.POLL, EventStream.HEARTBEAT, EventStream.LIMIT, time,
                    time::sleep).send(CALLER, CONVERSATION, ANSWER, 0, out);

            assertThat(ended).isEqualTo(Ended.ENDING_SENT);
            String[] lines = out.toString(UTF_8).split("\n");
            assertThat(lines[0]).isEqualTo("id: 1");
            assertThat(lines[1]).isEqualTo("event: error");
            Map<?, ?> problem = JSON.readValue(lines[2].substring("data: ".length()), Map.class);
            int status = code == ErrorCode.MODEL_FAILED ? 502 : 503;
            assertThat(problem).isEqualTo(Map.of("type", "about:blank", "status", status, "code", code.wireName(),
                    "title", status == 502 ? "Bad Gateway" : "Service Unavailable"));
        }
    }

    @Test
    void theStreamGoesOnAfterTheNumberItWasGiven() {
        log.at(Duration.ZERO, started(1)).at(Duration.ZERO, delta(2, "Hel")).at(Duration.ZERO, delta(3, "lo"))
                .at(Duration.ZERO, completed(4));

        assertThat(send(2)).startsWith("id: 3\n");
        assertThat(log.reads).containsExactly("ten_1/app_1 conv_1 msg_1 after 2 at most 100");
    }

    @Test
    void theLogIsReadEvery200MillisecondsUntilTheEnding() {
        log.at(Duration.ZERO, started(1)).at(seconds(1), delta(2, "Hel")).at(seconds(1.5), completed(3));

        String sent = send(0);

        assertThat(sent).contains("id: 1\n", "id: 2\n", "id: 3\n").doesNotContain(HEARTBEAT);
        assertThat(time.sleeps).hasSize(8).containsOnly(Duration.ofMillis(200));
        assertThat(log.reads).hasSize(9).last().asString().endsWith("after 2 at most 100");
    }

    @Test
    void aHeartbeatGoesOutWhenNoEventHasGoneOutFor15Seconds() {
        log.at(Duration.ZERO, started(1)).at(seconds(20), completed(2));

        assertThat(send(0)).isEqualTo(sse(1, "message.started", "{\"message_id\":\"msg_1\"}") + HEARTBEAT
                + sse(2, "message.completed", "{\"message_id\":\"msg_1\",\"usage\":{\"input_tokens\":6,\"output_tokens\":2}}"));
    }

    @Test
    void eventsThatKeepComingNeedNoHeartbeat() {
        log.at(Duration.ZERO, started(1));
        for (int i = 1; i <= 6; i++) {
            log.at(seconds(10 * i), delta(i + 1, "p" + i));
        }
        log.at(seconds(70), completed(8));

        assertThat(send(0)).doesNotContain(HEARTBEAT).contains("id: 8\n");
    }

    /** Until the lease of M2-11 an answer may never end, so after 5 minutes the stream closes, and the client resumes. */
    @Test
    void theStreamClosesAtItsLimitWithoutAnEnding() {
        log.at(Duration.ZERO, started(1));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        Ended ended = stream().send(CALLER, CONVERSATION, ANSWER, 0, out);

        assertThat(ended).isEqualTo(Ended.TIMED_OUT);
        assertThat(time.now).isEqualTo(T0.plus(Duration.ofMinutes(5)));
        String sent = out.toString(UTF_8);
        assertThat(sent.split(HEARTBEAT, -1)).hasSize(20);
        assertThat(sent).doesNotContain("event: message.completed", "event: error");
    }

    @Test
    void aCallerThatGoesAwayStopsTheStream() {
        log.at(Duration.ZERO, started(1)).at(seconds(1), delta(2, "Hel")).at(seconds(2), completed(3));
        OutputStream gone = new OutputStream() {

            private int writes;

            @Override
            public void write(int b) {
                throw new IllegalStateException("only whole events are written");
            }

            @Override
            public void write(byte[] b, int offset, int length) throws IOException {
                if (++writes > 1) {
                    throw new IOException("Broken pipe");
                }
            }
        };

        Ended ended = stream().send(CALLER, CONVERSATION, ANSWER, 0, gone);

        assertThat(ended).isEqualTo(Ended.CALLER_GONE);
        assertThat(log.reads.getLast()).endsWith("after 1 at most 100");
    }

    @Test
    void aLongAnswerIsReadInBatchesWithoutWaiting() {
        log.at(Duration.ZERO, started(1));
        for (int seq = 2; seq <= 250; seq++) {
            log.at(Duration.ZERO, delta(seq, "p"));
        }
        log.at(Duration.ZERO, completed(251));

        String sent = send(0);

        assertThat(sent).contains("id: 251\nevent: message.completed\n");
        assertThat(sent.split("\n\n")).hasSize(251);
        assertThat(log.reads).containsExactly("ten_1/app_1 conv_1 msg_1 after 0 at most 100",
                "ten_1/app_1 conv_1 msg_1 after 100 at most 100", "ten_1/app_1 conv_1 msg_1 after 200 at most 100");
        assertThat(time.sleeps).isEmpty();
    }

    @Test
    void eachReadWithEventsAndEachHeartbeatIsFlushedAtOnce() {
        log.at(Duration.ZERO, started(1)).at(Duration.ZERO, delta(2, "Hel")).at(seconds(1), delta(3, "lo"))
                .at(seconds(17), completed(4));
        Flushes out = new Flushes();

        stream().send(CALLER, CONVERSATION, ANSWER, 0, out);

        assertThat(out.count).isEqualTo(4);
    }

    @Test
    void aLineBreakInATextStaysWithinOneDataLine() {
        log.at(Duration.ZERO, delta(2, "a\nb")).at(Duration.ZERO, completed(3));

        assertThat(send(1)).startsWith(sse(2, "message.delta", "{\"message_id\":\"msg_1\",\"text\":\"a\\nb\"}"));
    }

    @Test
    void anInterruptStopsTheStream() {
        log.at(Duration.ZERO, started(1));
        EventStream interrupted = new EventStream(log.messages(), EventStream.POLL, EventStream.HEARTBEAT, EventStream.LIMIT,
                time, span -> {
                    throw new InterruptedException("stopping");
                });

        Ended ended = interrupted.send(CALLER, CONVERSATION, ANSWER, 0, new ByteArrayOutputStream());

        assertThat(ended).isEqualTo(Ended.INTERRUPTED);
        assertThat(Thread.interrupted()).isTrue();
    }

    /** By then the response has started, so the caller of the stream decides what becomes of it (M2-7-2). */
    @Test
    void aFailureToReadTheLogIsThrownOn() {
        log.failure = new DatabaseException("The transaction failed", new IllegalStateException("down"));

        assertThatThrownBy(() -> send(0)).isSameAs(log.failure);
    }

    @Test
    void theTimesAreTheApprovedOnes() {
        assertThat(EventStream.POLL).isEqualTo(Duration.ofMillis(200));
        assertThat(EventStream.HEARTBEAT).isEqualTo(Duration.ofSeconds(15));
        assertThat(EventStream.LIMIT).isEqualTo(Duration.ofMinutes(5));
        assertThat(EventStream.BATCH).isEqualTo(100);
        for (Duration wrong : new Duration[] {Duration.ZERO, Duration.ofMillis(-1), null}) {
            assertThatThrownBy(() -> new EventStream(log.messages(), wrong, EventStream.HEARTBEAT, EventStream.LIMIT, time,
                    time::sleep)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
