package aie.core.trust.api;

/**
 * The trust layer's checks on the way to a model and back (blueprint 9.1, 9.2). The gateway calls them on every call, so
 * no module can go around them, and they never call the gateway (A-15). The input check looks at what the end user said
 * before anything of it is stored or sent (M3-4-1). The output check looks at each piece of an answer before the caller
 * sees it; it passes every piece until M3-4-3.
 */
public interface Checks {

    /**
     * Checks what the end user said for a task, before any of it is stored or goes to a model.
     *
     * @throws IllegalArgumentException for a task or a text that is missing
     */
    Input input(String task, String text);

    /** Looks at a piece of an answer before the caller sees it, and returns what the caller may see. */
    String output(String task, String piece);

    /** What the input check made of a text. */
    sealed interface Input permits Passed, Blocked {
    }

    /** The text may be stored and sent. */
    record Passed() implements Input {
    }

    /**
     * The text may neither be stored nor sent.
     *
     * @param rule the rule that stopped it, a stable name for the log and the audit; never the text itself
     */
    record Blocked(String rule) implements Input {

        public Blocked {
            if (rule == null || rule.isBlank()) {
                throw new IllegalArgumentException("A blocked text names the rule that stopped it");
            }
        }
    }
}
