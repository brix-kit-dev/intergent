package aie.core.trust.api;

/**
 * The trust layer's checks on the way to a model and back (blueprint 9.1, 9.2). The gateway calls them on every call, so
 * no module can go around them, and they never call the gateway (A-15). The input check looks at what the end user said
 * before anything of it is stored or sent (M3-4-1), and finds the least data class it calls for (M3-4-2). Every text a
 * model is shown is masked first, however often it goes (M3-4-2). The output check holds each answer as its route's
 * check mode says, and lets the caller see only what it checked and masked (M3-4-3).
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

    /**
     * Starts the checks of one answer of a task (blueprint 9.2), which hold what the model writes as the mode says until
     * they have checked it (M3-4-3):
     * <ul>
     *   <li>{@link CheckMode#SENTENCE}: each sentence, until the next one starts;
     *   <li>{@link CheckMode#FULL}: the whole answer, until the model has completed it;
     *   <li>{@link CheckMode#INPUT}: nothing, since the input check is the only one; each piece goes out as it is.
     * </ul>
     * What they let out is masked as a model's input is. A text that repeats a long stretch of the instructions stops the
     * answer (M3-4 decision 3).
     *
     * @param instructions the prompt the model was given, which the answer must not give away
     * @throws IllegalArgumentException for a task, a mode or instructions that are missing
     */
    Output output(String task, CheckMode mode, String instructions);

    /**
     * The checks of one answer while it is written, on its writer's thread. Once they stop the answer, they release
     * nothing more.
     */
    interface Output {

        /**
         * Takes the next piece the model wrote, and says what the caller may see now: possibly nothing yet, while a
         * sentence or the whole answer is still held.
         */
        Verdict piece(String piece);

        /** The model has completed its answer: says what the caller may see of what is still held. */
        Verdict end();
    }

    /** What the output check lets out. */
    sealed interface Verdict permits Release, Stop {
    }

    /**
     * The caller may see this now.
     *
     * @param text the checked text, masked; empty while everything is held
     */
    record Release(String text) implements Verdict {

        public Release {
            if (text == null) {
                throw new IllegalArgumentException("A release has a text, empty when nothing goes out yet");
            }
        }
    }

    /**
     * The answer stops here: the caller sees nothing more of it, and what it saw is void (blueprint 6.4).
     *
     * @param rule the rule that stopped it, a stable name for the log and the audit; never the text itself
     */
    record Stop(String rule) implements Verdict {

        public Stop {
            if (rule == null || rule.isBlank()) {
                throw new IllegalArgumentException("A stopped answer names the rule that stopped it");
            }
        }
    }

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
