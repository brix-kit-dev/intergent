package aie.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.web.server.context.WebServerApplicationContext;

/** Point 39: the step that ends the answers under way while Tomcat drains its requests. */
@ExtendWith(QuietOutput.class)
class AnswersShutdownTest {

    @Test
    void itStopsInTheSamePhaseAsTomcatsGracefulShutdown() {
        assertThat(new AnswersShutdown(() -> { }).getPhase()).isEqualTo(WebServerApplicationContext.GRACEFUL_SHUTDOWN_PHASE);
    }

    @Test
    void itRunsOnceStartedAndNoLongerOnceStopped() {
        AtomicInteger ended = new AtomicInteger();
        AnswersShutdown shutdown = new AnswersShutdown(ended::incrementAndGet);

        assertThat(shutdown.isRunning()).isFalse();
        shutdown.start();
        assertThat(shutdown.isRunning()).isTrue();
        shutdown.stop();

        assertThat(shutdown.isRunning()).isFalse();
        assertThat(ended).hasValue(1);
    }

    /**
     * Ending the answers takes as long as the generator's drain, so it runs on its own thread: the context goes on at once
     * to Tomcat's graceful shutdown in the same phase, and hears back only once the answers have ended.
     */
    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void stoppingHoldsUpNothingElseInItsPhase() throws Exception {
        CountDownLatch drained = new CountDownLatch(1);
        CountDownLatch ending = new CountDownLatch(1);
        CountDownLatch heardBack = new CountDownLatch(1);
        AnswersShutdown shutdown = new AnswersShutdown(() -> {
            ending.countDown();
            try {
                drained.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        shutdown.start();

        shutdown.stop(heardBack::countDown);

        assertThat(ending.await(5, TimeUnit.SECONDS)).as("the answers are being ended").isTrue();
        assertThat(heardBack.getCount()).as("no word back while the answers drain").isEqualTo(1);
        drained.countDown();
        assertThat(heardBack.await(5, TimeUnit.SECONDS)).as("word back once they have ended").isTrue();
        assertThat(shutdown.isRunning()).isFalse();
    }

    /**
     * A failure to end the answers is logged, and word still goes back, so the context does not wait out the phase's 30
     * seconds.
     */
    @Test
    void aFailureIsLoggedAndStillSendsWordBack(CapturedOutput output) throws Exception {
        CountDownLatch heardBack = new CountDownLatch(1);
        AnswersShutdown shutdown = new AnswersShutdown(() -> {
            throw new IllegalStateException("The answers could not be ended");
        });
        shutdown.start();

        shutdown.stop(heardBack::countDown);

        assertThat(heardBack.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(shutdown.isRunning()).isFalse();
        assertThat(output.getAll()).contains("ERROR", "The answers under way could not be ended, so the shutdown goes on without them",
                "IllegalStateException: The answers could not be ended");
        assertThatThrownBy(shutdown::stop).hasMessage("The answers could not be ended");
    }
}
