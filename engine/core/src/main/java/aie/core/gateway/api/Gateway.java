package aie.core.gateway.api;

import aie.core.trust.api.DataClass;
import java.util.List;

/**
 * The only way to a model (blueprint 8). A module names a task, never a model: the gateway finds the task's route,
 * picks the first of its models that takes the call's data class, and calls it with the route's time limit. Only the
 * gateway may use the model ports (A-2), so this interface has types of its own and shows none of theirs.
 */
public interface Gateway {

    /**
     * Answers a task and hands each piece of the answer to {@code output} as it arrives. Returns once the answer has
     * ended. A cancel, a failure of the model and the time running out all come back as a result, not as an exception.
     *
     * @throws IllegalArgumentException for a task that no route answers, a request that is not well formed, or one whose
     *     data class is above the task's {@link #ceiling}; all are mistakes in the calling code, and no model is called.
     *     An exception from {@code output} also reaches the caller.
     * @throws IllegalStateException for a call from inside a database transaction, before any model is asked (A-14):
     *     an answer would hold the transaction's connection for as long as it takes
     */
    Result answer(Request request, Output output);

    /**
     * The highest data class a task takes: the highest {@code max_data_class} of the models on its route (blueprint 7.3).
     * A call above it has no model to go to and fails, never going to a model of a lower class, so the caller refuses it
     * before it stores or sends anything.
     *
     * @throws IllegalArgumentException for a task that no route answers
     */
    DataClass ceiling(String task);

    /**
     * What to answer.
     *
     * @param task what the module needs, such as {@code customer_chat}; the route table decides which model answers
     * @param turns the conversation so far, oldest first, ending with the user's turn
     * @param cancellation asked often, at least between pieces, whether the caller wants the answer to stop
     * @param dataClass the class of the call: only a model that takes it is called (blueprint 7.3, M3-3)
     */
    record Request(String task, List<Turn> turns, Cancellation cancellation, DataClass dataClass) {
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
     * The model failed, or the time ran out. Why is in the gateway's log and not here, because it is the model's own
     * text and must never reach the user.
     *
     * @param pieces how many pieces reached the output first; with none, nothing of the answer was shown
     */
    record Failed(int pieces, Usage usage) implements Result {
    }
}
