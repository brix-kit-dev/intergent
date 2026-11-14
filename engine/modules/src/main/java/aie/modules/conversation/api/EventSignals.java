package aie.modules.conversation.api;

import java.util.concurrent.CompletableFuture;

/**
 * Lets a reader in this instance learn at once that an answer has a new event, instead of at its next look at the log.
 * It only saves time: a reader looks at the log anyway, every 200 milliseconds, and that is how a reader in another
 * instance learns of the event. A reader takes the signal before it looks at the log, so an event written after its
 * look rings that very signal, and none is missed.
 */
public interface EventSignals {

    /** Signals that never ring, for code that needs no wake-up. */
    EventSignals NONE = new EventSignals() {

        @Override
        public CompletableFuture<Void> next(String messageId) {
            return new CompletableFuture<>();
        }

        @Override
        public void ring(String messageId) {
        }

        @Override
        public void forget(String messageId, CompletableFuture<Void> signal) {
        }
    };

    /** The signal of the answer's next event in this instance. Readers of one answer share it. */
    CompletableFuture<Void> next(String messageId);

    /** The answer has a new event, written and committed: its signal completes, and the next one is a new signal. */
    void ring(String messageId);

    /** A reader that waits no more gives its signal back, so that the signal of an answer no one rings does not stay. */
    void forget(String messageId, CompletableFuture<Void> signal);
}
