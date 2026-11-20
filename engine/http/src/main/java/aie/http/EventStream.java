package aie.http;

import static java.nio.charset.StandardCharsets.UTF_8;

import aie.core.tenancy.api.ApiKeyLookup.Owner;
import aie.http.api.model.MessageBlockedEvent;
import aie.http.api.model.MessageCancelledEvent;
import aie.http.api.model.MessageCompletedEvent;
import aie.http.api.model.MessageDeltaEvent;
import aie.http.api.model.MessageStartedEvent;
import aie.http.api.model.Usage;
import aie.modules.conversation.api.EventSignals;
import aie.modules.conversation.api.Messages;
import aie.modules.conversation.api.Messages.ErrorCode;
import aie.modules.conversation.api.Messages.Event;
import aie.modules.conversation.api.Messages.EventType;
import aie.modules.conversation.api.Messages.Expiry;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends an answer's events as Server-Sent Events, read from its event log (blueprint 6.4): each event as its number,
 * its type and the contract's JSON for it, in order, until the ending event. The log is read every 200 milliseconds,
 * or at once when an event is written in this instance (M2-7-4): the stream takes the answer's signal before each look
 * and waits on it, so a missed signal costs no more than the next look. When no event has gone out for 15 seconds, a
 * comment keeps the connection open. A caller that goes away stops the sending, not the answer.
 *
 * <p>A stream lasts 5 minutes at most, well past the route's 2 minutes. A writer that is there ends its answer within
 * the route's time, and the answer of one that died ends once its lease has run out, since the stream looks at the
 * lease (M2-11-3), so the limit is only a safety net. At the limit the stream closes without an ending event, and the
 * client resumes from the last number it got.
 */
final class EventStream {

    static final Duration POLL = Duration.ofMillis(200);
    static final Duration HEARTBEAT = Duration.ofSeconds(15);
    static final Duration LIMIT = Duration.ofMinutes(5);
    /**
     * How long a stream that sees no new event waits before it looks at the writer's lease (M2-11-3). A writer renews its
     * lease every 5 seconds, so one whose lease has run out has gone, and the stream ends the answer for it.
     */
    static final Duration LEASE_LOOK = Duration.ofSeconds(5);
    /** How many events one read takes at most. A full read is followed by the next one at once. */
    static final int BATCH = 100;

    /** How a stream ended. */
    enum Ended {
        /** The answer's ending event went out. */
        ENDING_SENT,
        /** The limit came first; the client resumes. */
        TIMED_OUT,
        /** A write failed, so the caller is gone. */
        CALLER_GONE,
        /** The thread was interrupted while it waited. */
        INTERRUPTED
    }

    /** Waits for a signal, for so long at most. Tests pass one that only moves their clock. */
    @FunctionalInterface
    interface Waiter {

        void await(CompletableFuture<Void> signal, Duration atMost) throws InterruptedException;
    }

    /** Waits on the signal itself: a ring ends the wait at once. */
    static final Waiter SIGNALS = (signal, atMost) -> {
        try {
            signal.get(atMost.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException | ExecutionException noEvent) {
            // Time for the next look, whatever woke the wait.
        }
    };

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final byte[] HEARTBEAT_COMMENT = ": heartbeat\n\n".getBytes(UTF_8);

    private final Messages messages;
    private final EventSignals signals;
    private final Duration poll;
    private final Duration heartbeat;
    private final Duration limit;
    private final Duration leaseLook;
    private final Clock clock;
    private final Waiter waiter;

    EventStream(Messages messages, EventSignals signals) {
        this(messages, signals, POLL, HEARTBEAT, LIMIT, Clock.systemUTC(), SIGNALS);
    }

    EventStream(Messages messages, EventSignals signals, Duration poll, Duration heartbeat, Duration limit, Clock clock,
            Waiter waiter) {
        this(messages, signals, poll, heartbeat, limit, LEASE_LOOK, clock, waiter);
    }

    EventStream(Messages messages, EventSignals signals, Duration poll, Duration heartbeat, Duration limit, Duration leaseLook,
            Clock clock, Waiter waiter) {
        this.messages = Objects.requireNonNull(messages, "A stream reads the log through Messages");
        this.signals = Objects.requireNonNull(signals, "A stream needs the signals, or NONE");
        this.poll = positive(poll, "poll");
        this.heartbeat = positive(heartbeat, "heartbeat");
        this.limit = positive(limit, "limit");
        this.leaseLook = positive(leaseLook, "lease look");
        this.clock = Objects.requireNonNull(clock, "A stream needs a clock");
        this.waiter = Objects.requireNonNull(waiter, "A stream needs a way to wait");
    }

    private static Duration positive(Duration time, String name) {
        if (time == null || time.isNegative() || time.isZero()) {
            throw new IllegalArgumentException("The stream's " + name + " is a time above zero, not " + time);
        }
        return time;
    }

    /**
     * Sends the answer's events after number {@code after}, until its ending event, the limit, or the caller going away.
     * Each read that found events is flushed at once, and so is each heartbeat. A failure to read the log is thrown on,
     * to a caller whose response has started by then.
     *
     * <p>A stream that has seen no new event for {@link #LEASE_LOOK} looks at the writer's lease. Once the lease has run
     * out, the writer has gone, so the stream ends the answer for it (blueprint 6.4), reads again at once, and sends that
     * ending like any other.
     */
    Ended send(Owner caller, String conversationId, String messageId, long after, OutputStream out) {
        Instant start = clock.instant();
        Instant deadline = start.plus(limit);
        Instant beat = start.plus(heartbeat);
        Instant look = start.plus(leaseLook);
        long last = after;
        CompletableFuture<Void> signal = null;
        try {
            while (true) {
                signal = signals.next(messageId);
                List<Event> events = messages.eventsAfter(caller, conversationId, messageId, last, BATCH);
                for (Event event : events) {
                    out.write(sse(event));
                    last = event.seq();
                    if (ending(event.type())) {
                        out.flush();
                        return Ended.ENDING_SENT;
                    }
                }
                Instant now = clock.instant();
                if (!events.isEmpty()) {
                    out.flush();
                    beat = now.plus(heartbeat);
                    look = now.plus(leaseLook);
                    if (events.size() == BATCH) {
                        continue;
                    }
                } else if (!now.isBefore(look)) {
                    look = now.plus(leaseLook);
                    if (messages.endIfExpired(caller, conversationId, messageId) instanceof Expiry.Ended) {
                        continue;
                    }
                }
                if (!now.isBefore(deadline)) {
                    return Ended.TIMED_OUT;
                }
                if (!now.isBefore(beat)) {
                    out.write(HEARTBEAT_COMMENT);
                    out.flush();
                    beat = now.plus(heartbeat);
                }
                Duration left = Duration.between(now, deadline);
                waiter.await(signal, left.compareTo(poll) < 0 ? left : poll);
            }
        } catch (IOException gone) {
            return Ended.CALLER_GONE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Ended.INTERRUPTED;
        } finally {
            if (signal != null) {
                signals.forget(messageId, signal);
            }
        }
    }

    /** One event as SSE: its number as the id, its type as the event, its JSON on one line as the data. */
    static byte[] sse(Event event) {
        return ("id: " + event.seq() + "\nevent: " + event.type().wireName() + "\ndata: "
                + JSON.writeValueAsString(data(event)) + "\n\n").getBytes(UTF_8);
    }

    /**
     * The contract's JSON of each type. An error is a problem whose code says why, and never the model's own words; so is
     * message.blocked a code alone, never what was blocked (M3-4-3).
     */
    private static Object data(Event event) {
        return switch (event.type()) {
            case MESSAGE_STARTED -> new MessageStartedEvent(event.messageId());
            case MESSAGE_DELTA -> new MessageDeltaEvent(event.messageId(), event.text());
            case MESSAGE_COMPLETED -> new MessageCompletedEvent(event.messageId(), usage(event));
            case MESSAGE_BLOCKED -> new MessageBlockedEvent(event.messageId(), event.code().wireName());
            case MESSAGE_CANCELLED -> new MessageCancelledEvent(event.messageId(), event.text(), usage(event));
            case ERROR -> Problems.of(status(event.code()), event.code().wireName(), null);
        };
    }

    /**
     * The status an error event's problem carries, although the response itself is a 200 by then: the model failed
     * upstream, 502; the writer went away, 503. Callers read the code. output_blocked comes with message.blocked, never
     * with an error event, which the database holds to.
     */
    static int status(ErrorCode code) {
        return switch (code) {
            case MODEL_FAILED -> 502;
            case STREAM_INTERRUPTED -> 503;
            case OUTPUT_BLOCKED -> throw new IllegalStateException("output_blocked comes with message.blocked, not with error");
        };
    }

    private static Usage usage(Event event) {
        return new Usage(event.usage().inputTokens(), event.usage().outputTokens());
    }

    /** Whether an event of this type ends its answer, so that nothing can follow it. */
    static boolean ending(EventType type) {
        return type == EventType.MESSAGE_COMPLETED || type == EventType.MESSAGE_BLOCKED || type == EventType.MESSAGE_CANCELLED
                || type == EventType.ERROR;
    }
}
