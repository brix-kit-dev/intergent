package aie.modules.conversation.internal;

import aie.core.gateway.api.Gateway;
import aie.core.tenancy.api.ApiKeyLookup.Owner;
import aie.modules.conversation.api.Answers;
import aie.modules.conversation.api.Messages;
import aie.modules.conversation.api.Messages.Asked;
import aie.modules.conversation.api.Messages.ErrorCode;
import aie.modules.conversation.api.Messages.Role;
import aie.modules.conversation.api.Messages.Usage;
import aie.modules.conversation.api.Messages.Written;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link Answers} with a virtual thread for each answer. An ask takes one of the instance's slots before it stores
 * anything, so a full instance stores nothing. The answer's thread reads its history, asks the gateway outside any
 * transaction (A-14), and writes each piece as a delta, then the ending, each write a short transaction of its own.
 *
 * <p>How an answer ends:
 * <ul>
 *   <li>The model completed: message.completed with its usage.
 *   <li>The model failed or ran out of time: error with model_failed.
 *   <li>The writer itself went wrong, for instance a write did not go through, or the instance stopped it while
 *       stopping: the model is stopped, then error with stream_interrupted is written. When even that cannot be
 *       written, the answer stays in progress until a reader ends it once the writer's lease has run out (M2-11).
 *   <li>Someone else ended the answer first: the model is stopped, and nothing more is written.
 * </ul>
 */
public final class GeneratingAnswers implements Answers, AutoCloseable {

    /** How long {@link #close} lets the answers under way finish before it stops them. */
    public static final Duration DRAIN = Duration.ofSeconds(10);
    /** How long the answers that {@link #close} stopped have to write their ending. */
    static final Duration STOPPING = Duration.ofSeconds(5);

    private static final Logger LOG = LoggerFactory.getLogger(GeneratingAnswers.class);

    private final Messages messages;
    private final Gateway gateway;
    private final int maxRunning;
    private final Duration drain;
    private final Semaphore slots;
    private final Set<Answer> running = ConcurrentHashMap.newKeySet();
    private final ExecutorService threads = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("answer-", 0).factory());
    private final AtomicBoolean closing = new AtomicBoolean();

    public GeneratingAnswers(Messages messages, Gateway gateway) {
        this(messages, gateway, MAX_RUNNING, DRAIN);
    }

    /** With another limit and drain, so that a test needs neither 64 answers nor 10 seconds. */
    public GeneratingAnswers(Messages messages, Gateway gateway, int maxRunning, Duration drain) {
        if (maxRunning < 1) {
            throw new IllegalArgumentException("At least one answer must be able to run, not " + maxRunning);
        }
        if (drain == null || drain.isNegative()) {
            throw new IllegalArgumentException("The drain is a time of 0 or more, not " + drain);
        }
        this.messages = Objects.requireNonNull(messages, "Answers are written through Messages");
        this.gateway = Objects.requireNonNull(gateway, "Answers come from the gateway");
        this.maxRunning = maxRunning;
        this.drain = drain;
        this.slots = new Semaphore(maxRunning);
    }

    /**
     * The slot comes first and goes back when nothing was stored. Once the answer is stored, its thread gives the slot
     * back when the answer ends. Starting the thread fails only after {@link #close} has given up waiting; then the
     * answer stays in progress until a reader ends it.
     */
    @Override
    public Start ask(Owner caller, String conversationId, String text) {
        if (closing.get() || !slots.tryAcquire()) {
            return new Start.Busy();
        }
        Asked asked;
        try {
            Optional<Asked> stored = messages.ask(caller, conversationId, text);
            if (stored.isEmpty()) {
                slots.release();
                return new Start.NotFound();
            }
            asked = stored.get();
        } catch (RuntimeException e) {
            slots.release();
            throw e;
        }
        Answer answer = new Answer(caller, conversationId, asked);
        running.add(answer);
        threads.execute(() -> generate(answer));
        return new Start.Started(asked, answer.ended.minimalCompletionStage());
    }

    /** On the answer's own thread. Whatever happens, the slot goes back and the waiters learn that the answer is over. */
    private void generate(Answer answer) {
        try {
            Gateway.Result result = gateway.answer(
                    new Gateway.Request(TASK, turns(answer), answer::stopRequested), answer::write);
            if (!answer.endedElsewhere) {
                answer.end(result);
            }
        } catch (RuntimeException e) {
            LOG.error("Answer {} went wrong before its end, so it ends as interrupted", answer.id(), e);
            answer.interrupt();
        } finally {
            running.remove(answer);
            slots.release();
            answer.ended.complete(null);
        }
    }

    /** What the model is shown: the history before the answer, which ends with its question. */
    private List<Gateway.Turn> turns(Answer answer) {
        return messages.historyFor(answer.caller, answer.conversationId, answer.id()).stream()
                .map(message -> new Gateway.Turn(
                        message.role() == Role.USER ? Gateway.Role.USER : Gateway.Role.ASSISTANT, message.text()))
                .toList();
    }

    /**
     * Takes no more asks, lets the answers under way finish for as long as the drain, then stops the rest, which end as
     * interrupted, and waits a little for them to write that. Calling it again does nothing.
     */
    @Override
    public void close() {
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        try {
            if (!slots.tryAcquire(maxRunning, drain.toNanos(), TimeUnit.NANOSECONDS)) {
                running.forEach(Answer::stop);
                if (!slots.tryAcquire(maxRunning, STOPPING.toNanos(), TimeUnit.NANOSECONDS)) {
                    LOG.warn("{} answer(s) did not end in time, so they stay in progress until a reader ends them",
                            running.size());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            threads.shutdown();
        }
    }

    /** One answer under way, and what its writer knows of it. */
    private final class Answer {

        private final Owner caller;
        private final String conversationId;
        private final Asked asked;
        private final CompletableFuture<Void> ended = new CompletableFuture<>();
        /** Set while the instance stops, so that the model stops at its next look. */
        private volatile boolean stopped;
        /** Set once a write learned that the answer had ended already. */
        private volatile boolean endedElsewhere;

        Answer(Owner caller, String conversationId, Asked asked) {
            this.caller = caller;
            this.conversationId = conversationId;
            this.asked = asked;
        }

        String id() {
            return asked.answer().messageId();
        }

        boolean stopRequested() {
            return stopped || endedElsewhere;
        }

        void stop() {
            stopped = true;
        }

        /** The next piece, as a delta. An empty piece adds nothing, and the database takes no empty delta. */
        void write(String piece) {
            if (piece.isEmpty() || endedElsewhere) {
                return;
            }
            if (!(messages.addDelta(caller, conversationId, id(), piece) instanceof Written.Added)) {
                endedElsewhere = true;
            }
        }

        /**
         * The ending the model's result calls for. A model that completed keeps its answer whole, even while the instance
         * stops. Any other result of a stopped answer is the instance's doing, not the model's.
         */
        void end(Gateway.Result result) {
            switch (result) {
                case Gateway.Completed done -> messages.complete(caller, conversationId, id(), usage(done.usage()));
                case Gateway.Failed failed when !stopped -> messages.fail(caller, conversationId, id(), ErrorCode.MODEL_FAILED);
                default -> messages.fail(caller, conversationId, id(), ErrorCode.STREAM_INTERRUPTED);
            }
        }

        /** The writer went wrong: the model stops if it still runs, and the answer ends as interrupted if it can. */
        void interrupt() {
            stopped = true;
            try {
                messages.fail(caller, conversationId, id(), ErrorCode.STREAM_INTERRUPTED);
            } catch (RuntimeException e) {
                LOG.error("Answer {} could not be ended either, so it stays in progress until a reader ends it", id(), e);
            }
        }

        private static Usage usage(Gateway.Usage usage) {
            return new Usage(usage.inputTokens(), usage.outputTokens());
        }
    }
}
