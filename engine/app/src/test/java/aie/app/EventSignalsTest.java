package aie.app;

import static org.assertj.core.api.Assertions.assertThat;

import aie.modules.conversation.api.EventSignals;
import aie.modules.conversation.internal.LocalEventSignals;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** The signals of one instance: a ring completes the waiting signal of its answer and no other. */
class EventSignalsTest {

    private final LocalEventSignals signals = new LocalEventSignals();

    @Test
    void aRingCompletesTheWaitingSignalAndTheNextSignalIsNew() {
        CompletableFuture<Void> first = signals.next("msg_1");

        signals.ring("msg_1");

        assertThat(first).isDone();
        CompletableFuture<Void> second = signals.next("msg_1");
        assertThat(second).isNotSameAs(first).isNotDone();
    }

    @Test
    void readersOfOneAnswerShareItsSignal() {
        CompletableFuture<Void> one = signals.next("msg_1");
        CompletableFuture<Void> another = signals.next("msg_1");

        signals.ring("msg_1");

        assertThat(another).isSameAs(one).isDone();
    }

    @Test
    void aRingReachesOnlyItsOwnAnswer() {
        CompletableFuture<Void> mine = signals.next("msg_1");
        CompletableFuture<Void> other = signals.next("msg_2");

        signals.ring("msg_1");

        assertThat(mine).isDone();
        assertThat(other).isNotDone();
        assertThat(signals.waiting()).isOne();
    }

    @Test
    void aRingThatNoOneWaitsForLeavesNothing() {
        signals.ring("msg_1");

        assertThat(signals.waiting()).isZero();
        assertThat(signals.next("msg_1")).isNotDone();
    }

    /** A reader gives back only its own signal: one that a ring replaced since stays for whoever waits on it. */
    @Test
    void forgettingGivesBackOnlyTheSignalStillWaiting() {
        CompletableFuture<Void> old = signals.next("msg_1");
        signals.ring("msg_1");
        CompletableFuture<Void> current = signals.next("msg_1");

        signals.forget("msg_1", old);
        assertThat(signals.waiting()).isOne();
        assertThat(signals.next("msg_1")).isSameAs(current);

        signals.forget("msg_1", current);
        assertThat(signals.waiting()).isZero();
    }

    @Test
    void noSignalsNeverRing() {
        CompletableFuture<Void> signal = EventSignals.NONE.next("msg_1");

        EventSignals.NONE.ring("msg_1");

        assertThat(signal).isNotDone();
    }
}
