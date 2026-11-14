package aie.http;

import static java.nio.charset.StandardCharsets.UTF_8;

import aie.core.tenancy.api.ApiKeyLookup.Owner;
import aie.modules.conversation.api.Messages;
import java.io.ByteArrayOutputStream;

/** Lets tests outside this package run the real event stream, with its real times, into memory. */
public final class EventStreamProbe {

    private EventStreamProbe() {
    }

    /** What the stream sends for the answer after number {@code after}, until it ends one way or another. */
    public static String send(Messages messages, Owner caller, String conversationId, String messageId, long after) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new EventStream(messages).send(caller, conversationId, messageId, after, out);
        return out.toString(UTF_8);
    }
}
