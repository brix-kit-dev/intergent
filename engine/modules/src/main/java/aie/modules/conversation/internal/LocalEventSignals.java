package aie.modules.conversation.internal;

import aie.modules.conversation.api.EventSignals;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link EventSignals} within this process: one waiting signal per answer that a reader waits for. A ring takes the
 * signal away as it completes it, so the next reader takes a new one. A signal that a reader gives back goes only if it
 * is still the one waiting; another reader of the same answer that still waits on it falls back on its next look.
 */
public final class LocalEventSignals implements EventSignals {

    private final ConcurrentHashMap<String, CompletableFuture<Void>> waiting = new ConcurrentHashMap<>();

    @Override
    public CompletableFuture<Void> next(String messageId) {
        return waiting.computeIfAbsent(messageId, unused -> new CompletableFuture<>());
    }

    @Override
    public void ring(String messageId) {
        CompletableFuture<Void> signal = waiting.remove(messageId);
        if (signal != null) {
            signal.complete(null);
        }
    }

    @Override
    public void forget(String messageId, CompletableFuture<Void> signal) {
        waiting.remove(messageId, signal);
    }

    /** How many answers have a signal that waits, so that a test or a probe can see none is left behind. */
    public int waiting() {
        return waiting.size();
    }
}
