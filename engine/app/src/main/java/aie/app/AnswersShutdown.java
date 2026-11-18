package aie.app;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.SmartLifecycle;

/**
 * Point 39: the answers under way get their drain while Tomcat drains its requests, in the same phase of the shutdown
 * and at the same time. Tomcat takes no more connections and waits for the requests it has; the generator takes no more
 * asks, lets its answers finish for its drain, then stops the rest, which end as interrupted. So a call that waits for
 * its answer gets it as it ended, and a stream sends its ending, while the pool is still open, and Tomcat's wait ends
 * with them. Before, the generator closed only once Tomcat had given up on those calls, and their reads met a closing
 * pool. The context closes the generator again later, which does nothing then.
 */
final class AnswersShutdown implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(AnswersShutdown.class);

    private final Runnable endAnswers;
    private volatile boolean running;

    /** With what ends the answers under way, such as closing the generator, which blocks until they have ended. */
    AnswersShutdown(Runnable endAnswers) {
        this.endAnswers = Objects.requireNonNull(endAnswers, "The shutdown ends the answers under way");
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Tomcat's graceful shutdown stops in this phase, so the two stop together. */
    @Override
    public int getPhase() {
        return WebServerApplicationContext.GRACEFUL_SHUTDOWN_PHASE;
    }

    /**
     * On a thread of its own, so that whichever of the two the context stops first, the other starts at once beside it.
     * A failure is logged, and the callback comes all the same, so that the context need not wait out the phase.
     */
    @Override
    public void stop(Runnable callback) {
        Thread.ofVirtual().name("answers-shutdown").start(() -> {
            try {
                stop();
            } catch (RuntimeException e) {
                LOG.error("The answers under way could not be ended, so the shutdown goes on without them", e);
            } finally {
                callback.run();
            }
        });
    }

    @Override
    public void stop() {
        try {
            endAnswers.run();
        } finally {
            running = false;
        }
    }
}
