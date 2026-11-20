package aie.modules.conversation.api;

import aie.core.tenancy.api.ApiKeyLookup.Owner;
import aie.core.trust.api.DataClass;
import aie.modules.conversation.api.Messages.Asked;
import aie.modules.conversation.api.Messages.CancelRequest;
import java.util.concurrent.CompletionStage;

/**
 * Answering what the end user says (blueprint 6.4). An ask stores the question and starts its answer, as
 * {@link Messages#ask} does. Then the answer is generated in the background: the model is called outside any
 * transaction, and each piece is written as an event in a short transaction of its own. The answer does not depend on
 * whoever asked: a caller that stops waiting does not stop it. Only a cancel does, from any instance.
 */
public interface Answers {

    /** The task this module asks the gateway to answer. The route table decides which model answers it. */
    String TASK = "customer_chat";

    /** How many answers one instance generates at once, at most. */
    int MAX_RUNNING = 64;

    /**
     * Stores what the end user said, starts its answer and generates it in the background. Nothing is stored when the
     * conversation is out of reach, when this instance already generates as many answers as it may, or when it is
     * stopping.
     *
     * @param floor the least data class of the call, the higher of the application's default and the class the caller
     *     declared; {@link Messages#ask} raises it to the conversation's watermark (M3-3)
     * @throws IllegalArgumentException for text that {@link Messages#ask} refuses
     */
    Start ask(Owner caller, String conversationId, String text, DataClass floor);

    /**
     * Asks for an answer to be cancelled (blueprint 6.4): the request is kept with the answer, as
     * {@link Messages#requestCancel} keeps it. When this instance writes the answer, its model is told to stop at once;
     * an instance that writes it elsewhere learns at its next write. The writer then ends the answer with
     * message.cancelled, which carries what its log holds and the usage the model reports.
     *
     * @return what became of the request: recorded, or the answer had ended already, or it is not found
     */
    CancelRequest cancel(Owner caller, String conversationId, String messageId);

    /** What became of an ask. */
    sealed interface Start {

        /**
         * The answer is under way.
         *
         * @param asked the question and its answer, as they were stored
         * @param ended completes once the answer's ending is written, or once its writer has given up writing it
         */
        record Started(Asked asked, CompletionStage<Void> ended) implements Start {
        }

        /** This instance already generates as many answers as it may, or it is stopping. Nothing was stored. */
        record Busy() implements Start {
        }

        /** No conversation has this ID in the caller's application. Nothing was stored. */
        record NotFound() implements Start {
        }
    }
}
