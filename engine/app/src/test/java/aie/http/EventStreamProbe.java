package aie.http;

import static java.nio.charset.StandardCharsets.UTF_8;

import aie.core.tenancy.api.ApiKeyLookup.Owner;
import aie.modules.conversation.api.EventSignals;
import aie.modules.conversation.api.Messages;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.Duration;

/** Lets tests outside this package run the real event stream, with its real times, into memory. */
public final class EventStreamProbe {

    private EventStreamProbe() {
    }

    /** What the stream sends for the answer after number {@code after}, until it ends one way or another. */
    public static String send(Messages messages, Owner caller, String conversationId, String messageId, long after) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new EventStream(messages, EventSignals.NONE).send(caller, conversationId, messageId, after, out);
        return out.toString(UTF_8);
    }

    /** The same with signals and a look at the log only this often, so that only a ring can make the stream quick. */
    public static String send(Messages messages, EventSignals signals, Duration poll, Owner caller, String conversationId,
            String messageId, long after) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new EventStream(messages, signals, poll, EventStream.HEARTBEAT, EventStream.LIMIT, Clock.systemUTC(), EventStream.SIGNALS)
                .send(caller, conversationId, messageId, after, out);
        return out.toString(UTF_8);
    }
}
