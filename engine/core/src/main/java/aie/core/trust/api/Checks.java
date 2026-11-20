package aie.core.trust.api;

/**
 * The trust layer's checks on the way to a model and back (blueprint 9.1, 9.2). The gateway calls them on every call, so
 * no module can go around them, and they never call the gateway (A-15). The input check looks at what the end user said
 * before anything of it is stored or sent (M3-4-1), and finds the least data class it calls for (M3-4-2). Every text a
 * model is shown is masked first, however often it goes (M3-4-2). The output check looks at each piece of an answer
 * before the caller sees it; it passes every piece until M3-4-3.
 */
public interface Checks {

    /**
     * Checks what the end user said for a task, before any of it is stored or goes to a model.
     *
     * @throws IllegalArgumentException for a task or a text that is missing
     */
    Input input(String task, String text);

    /**
     * What a model may be shown of a text: the text with its personal data masked, such as {@code [phone]} for a
     * telephone number (M3-4-2). The text itself stays as it was wherever it is kept.
     *
     * @throws IllegalArgumentException for a text that is missing
     */
    String masked(String text);

    /** Looks at a piece of an answer before the caller sees it, and returns what the caller may see. */
    String output(String task, String piece);

    /** What the input check made of a text. */
    sealed interface Input permits Passed, Blocked {
    }

    /**
     * The text may be stored and sent.
     *
     * @param dataClass the least data class of a call with this text: personal when the check found personal data in
     *     it, public otherwise (blueprint 7.3). Masking the data does not lower it, since rules always miss some.
     */
    record Passed(DataClass dataClass) implements Input {

        public Passed {
            if (dataClass == null) {
                throw new IllegalArgumentException("A text that passed has the least class its check found");
            }
        }
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
