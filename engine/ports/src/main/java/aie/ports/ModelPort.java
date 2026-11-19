package aie.ports;

import java.time.Duration;
import java.util.List;

/**
 * Text generation by a model, streamed (blueprint 4.1). Only the gateway uses this port (A-2), and it names no vendor:
 * each adapter maps it to its own model's API. Every type of the call lives inside this interface, so that the A-2
 * rule, which matches this name, covers them all.
 */
public interface ModelPort {

    /**
     * Generates an answer and hands each piece of it to {@code output} as it arrives. Returns once the answer has ended.
     * A failure of the model, the time running out and a cancel all come back as a result, not as an exception. An
     * exception means a mistake in the request, or comes from {@code output} itself.
     */
    Result generate(Request request, Output output);

    /**
     * What to generate.
     *
     * @param model the model's ID in the gateway's registry
     * @param instructions the task's prompt for this model's family, as the gateway chose it after the model (M3-2); an
     *     adapter sends it as the system prompt
     * @param turns the conversation so far, oldest first, ending with the user's turn
     * @param timeout the longest the whole answer may take
     * @param cancellation asked often, at least between pieces, whether the caller wants the answer to stop
     */
    record Request(String model, String instructions, List<Turn> turns, Duration timeout, Cancellation cancellation) {
    }

    /** One turn of the conversation. */
    record Turn(Role role, String text) {
    }

    /** Who said a turn. */
    enum Role {
        USER,
        ASSISTANT
    }

    /** Receives the answer while it is generated. */
    @FunctionalInterface
    interface Output {

        /** The next piece of text, in order. */
        void text(String piece);
    }

    /** Lets the caller stop an answer that is under way. */
    @FunctionalInterface
    interface Cancellation {

        boolean requested();
    }

    /** What the answer used, counted the way the model counts. */
    record Usage(long inputTokens, long outputTokens) {
    }

    /** How the answer ended. Each result carries the usage up to that point. */
    sealed interface Result permits Completed, Cancelled, Failed {

        Usage usage();
    }

    /** The model finished its answer. */
    record Completed(Usage usage) implements Result {
    }

    /** The caller asked to stop, and the answer stopped. What was generated before stays generated. */
    record Cancelled(Usage usage) implements Result {
    }

    /**
     * The model failed, or the time ran out. The reason is for the logs, never for the caller.
     *
     * @param pieces how many pieces reached the output first, which tells a retry before the first piece from a
     *     failure in the middle of an answer
     */
    record Failed(String reason, int pieces, Usage usage) implements Result {
    }
}
