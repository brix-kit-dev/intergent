package aie.core.gateway.api;

import aie.core.trust.api.DataClass;
import java.util.List;

/**
 * The only way to a model (blueprint 8). A module names a task, never a model: the gateway finds the task's route,
 * picks the first of its models that takes the call's data class, and calls it with the route's time limit. What the end
 * user said is checked first, before any of it is stored (M3-4-1), and a model gets a question only with the receipt of
 * that check, so no call goes around it (A-15). Every text a model is shown is masked first, and a call's class is at
 * least the one its check found (M3-4-2). Only the gateway may use the model ports (A-2), so this interface has types of
 * its own and shows none of theirs.
 */
public interface Gateway {

    /**
     * Checks what the end user said for a task, before any of it is stored or sent (blueprint 8.2, 9.1, M3-4-1). A text
     * the check blocks neither joins the history nor raises the watermark, since no model sees it (blueprint 7.3). A text
     * that passes comes back as the receipt a request for it needs ({@link Request#question}), so the same text is checked
     * once, however often it goes to a model.
     *
     * @throws IllegalArgumentException for a task that no route answers, or a text that is missing
     * @throws IllegalStateException for a call from inside a database transaction (A-14): a check may come to call a
     *     service as well
     */
    InputCheck checkInput(String task, String text);

    /**
     * Answers a task and hands each piece of the answer to {@code output} as it arrives. Returns once the answer has
     * ended. A cancel, a failure of the model and the time running out all come back as a result, not as an exception.
     *
     * @throws IllegalArgumentException for a task that no route answers, a request that is not well formed, one whose
     *     data class is above the task's {@link #ceiling}, one without this gateway's receipt for its question, or one
     *     whose class is below the class its receipt found; all are mistakes in the calling code, and no model is called.
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
     * @param dataClass the class of the call: only a model that takes it is called (blueprint 7.3, M3-3); at least the
     *     class the question's check found (M3-4-2)
     * @param question the receipt of this gateway's input check of the last turn, for this task (M3-4-1)
     */
    record Request(String task, List<Turn> turns, Cancellation cancellation, DataClass dataClass, Checked question) {
    }

    /** What the input check made of a text (M3-4-1). */
    sealed interface InputCheck permits Checked, Blocked {
    }

    /**
     * The receipt of a text that passed the input check for a task. Only the gateway that checked the text makes one, and it
     * takes no other, so a request cannot reach a model with a question that was not checked (A-15).
     */
    non-sealed interface Checked extends InputCheck {

        /** The task the text was checked for. */
        String task();

        /** The text as it was checked. */
        String text();

        /**
         * The least data class of a call with this text, as the check found it: personal when it holds personal data
         * (M3-4-2). The caller raises its call to it before anything is stored, and masking does not lower it.
         */
        DataClass dataClass();
    }

    /**
     * The check blocked the text: nothing of it may be stored or sent.
     *
     * @param rule the rule that stopped it, a stable name such as {@code instruction_override}; never the text itself
     */
    record Blocked(String rule) implements InputCheck {
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
