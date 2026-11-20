package aie.ports;

/**
 * Content moderation (blueprint 4.1, 9.1): says whether a text may go on, on its way to a model or back from one. It
 * names no vendor and no language. The first adapter applies rules inside the engine (M3 decision 5), and a moderation
 * service can take its place later without a change anywhere else. An adapter that calls a service keeps its own time
 * limit on the call (A-9). Only the trust layer calls this port, and the gateway calls the trust layer.
 */
public interface ModerationPort {

    /**
     * Looks at one text and says whether it may go on.
     *
     * @throws IllegalArgumentException for content without a direction or a text
     */
    Verdict moderate(Content content);

    /**
     * A text to look at, and which way it goes.
     *
     * @param direction whether the text goes to a model or comes back from one
     * @param text the text as it is, which the port neither keeps nor changes
     */
    record Content(Direction direction, String text) {
    }

    /** Which way a text goes. */
    enum Direction {
        /** What the end user said, before a model sees it. */
        INPUT,
        /** What a model wrote, before the caller sees it. */
        OUTPUT
    }

    /** What the moderation made of a text. */
    sealed interface Verdict permits Allowed, Blocked {
    }

    /** The text may go on. */
    record Allowed() implements Verdict {
    }

    /**
     * The text may not go on.
     *
     * @param rule the rule that stopped it, a stable name such as {@code instruction_override}, for the log and the audit;
     *     never the text itself
     */
    record Blocked(String rule) implements Verdict {
    }
}
