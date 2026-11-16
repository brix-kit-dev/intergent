package aie.modules.conversation.internal;

import aie.core.gateway.api.Gateway;
import aie.core.tenancy.api.ApiKeyLookup.Owner;
import aie.modules.conversation.api.Answers;
import aie.modules.conversation.api.Messages;
import aie.modules.conversation.api.Messages.Asked;
import aie.modules.conversation.api.Messages.CancelRequest;
import aie.modules.conversation.api.Messages.ErrorCode;
import aie.modules.conversation.api.Messages.Renewal;
import aie.modules.conversation.api.Messages.Role;
import aie.modules.conversation.api.Messages.Usage;
import aie.modules.conversation.api.Messages.Written;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link Answers} with a virtual thread for each answer. An ask takes one of the instance's slots before it stores
 * anything, so a full instance stores nothing. The answer's thread reads its history, asks the gateway outside any
 * transaction (A-14), and writes each piece as a delta, then the ending, each write a short transaction of its own.
 * While it writes, the instance renews the answer's lease every 5 seconds, so that a reader ends the answer only once its
 * writer has gone (blueprint 6.4).
 *
 * <p>How an answer ends:
 * <ul>
 *   <li>The model completed: message.completed with its usage.
 *   <li>The model failed or ran out of time: error with model_failed.
 *   <li>The writer itself went wrong, for instance a write did not go through, or the instance stopped it while
 *       stopping: the model is stopped, then error with stream_interrupted is written. When even that cannot be
 *       written, the answer stays in progress until a reader ends it once the writer's lease has run out (M2-11).
 *   <li>Someone asked to cancel it: on this instance the model is told to stop at once, and an instance that writes it
 *       elsewhere learns at its next write. Then message.cancelled is written, with what the log holds and the usage the
 *       model reports, which counts the pieces the log refused too. A request that came after the model ended still
 *       wins, because the database takes no other ending then; so does one that came while the instance stopped. When
 *       the writer itself went wrong, the model's usage went with the failure, so nothing is written, and a reader ends
 *       the answer once the writer's lease has run out (M2-11).
 *   <li>Someone else ended the answer first: the model is stopped, and nothing more is written.
 * </ul>
 */
public final class GeneratingAnswers implements Answers, AutoCloseable {

    /** How long {@link #close} lets the answers under way finish before it stops them. */
    public static final Duration DRAIN = Duration.ofSeconds(10);
    /** How long the answers that {@link #close} stopped have to write their ending. */
    static final Duration STOPPING = Duration.ofSeconds(5);
    /**
     * How often the lease of each answer under way is renewed (M2 decision 3). The lease is far longer, so a renewal that
     * comes late, or fails once, costs nothing.
     */
    public static final Duration RENEWAL = Duration.ofSeconds(5);

    private static final Logger LOG = LoggerFactory.getLogger(GeneratingAnswers.class);

    private final Messages messages;
    private final Gateway gateway;
    private final int maxRunning;
    private final Duration drain;
    private final Semaphore slots;
    /** The answers under way, by their message ID. */
    private final Map<String, Answer> running = new ConcurrentHashMap<>();
    private final ExecutorService threads = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("answer-", 0).factory());
    private final ScheduledExecutorService renewals =
            Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("lease-", 0).factory());
    private final AtomicBoolean closing = new AtomicBoolean();

    public GeneratingAnswers(Messages messages, Gateway gateway) {
        this(messages, gateway, MAX_RUNNING, DRAIN);
    }

    /** With another limit and drain, so that a test needs neither 64 answers nor 10 seconds. */
    public GeneratingAnswers(Messages messages, Gateway gateway, int maxRunning, Duration drain) {
        this(messages, gateway, maxRunning, drain, RENEWAL);
    }

    /** With another renewal too, so that a test need not wait 5 seconds for one. It must be far inside the lease. */
    public GeneratingAnswers(Messages messages, Gateway gateway, int maxRunning, Duration drain, Duration renewal) {
        if (maxRunning < 1) {
            throw new IllegalArgumentException("At least one answer must be able to run, not " + maxRunning);
        }
        if (drain == null || drain.isNegative()) {
            throw new IllegalArgumentException("The drain is a time of 0 or more, not " + drain);
        }
        if (renewal == null || renewal.isNegative() || renewal.isZero() || renewal.compareTo(Messages.LEASE.dividedBy(2)) > 0) {
            throw new IllegalArgumentException("A renewal comes in less than half the lease, not every " + renewal);
        }
        this.messages = Objects.requireNonNull(messages, "Answers are written through Messages");
        this.gateway = Objects.requireNonNull(gateway, "Answers come from the gateway");
        this.maxRunning = maxRunning;
        this.drain = drain;
        this.slots = new Semaphore(maxRunning);
        renewals.scheduleWithFixedDelay(this::renewAll, renewal.toNanos(), renewal.toNanos(), TimeUnit.NANOSECONDS);
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
        running.put(answer.id(), answer);
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
            running.remove(answer.id());
            slots.release();
            answer.ended.complete(null);
        }
    }

    /**
     * The request goes to the database first, so every instance learns of it. Only a request that was kept stops the
     * model here: one for an answer that had ended changes nothing.
     */
    @Override
    public CancelRequest cancel(Owner caller, String conversationId, String messageId) {
        CancelRequest request = messages.requestCancel(caller, conversationId, messageId);
        if (request instanceof CancelRequest.Recorded) {
            Answer local = running.get(messageId);
            if (local != null) {
                local.cancel();
            }
        }
        return request;
    }

    /**
     * Renews the lease of every answer under way, one after the other. A renewal that learns of a request to cancel stops
     * the model, so that one which writes nothing for a while still stops within a renewal; one that finds the answer
     * ended, as a reader ends it once a lease ran out, stops the model and writes nothing more. A failure costs only this
     * round for this answer, since the lease is far longer: the next round tries again.
     */
    private void renewAll() {
        for (Answer answer : running.values()) {
            try {
                switch (messages.renew(answer.caller, answer.conversationId, answer.id())) {
                    case Renewal.Renewed renewed when renewed.cancelRequested() -> answer.cancel();
                    case Renewal.Renewed renewed -> {
                    }
                    default -> answer.learnEndedElsewhere();
                }
            } catch (RuntimeException e) {
                LOG.warn("The lease of answer {} could not be renewed this time, so the next renewal tries again", answer.id(), e);
            }
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
     * interrupted, and waits a little for them to write that. Their leases are renewed until then, and no more after.
     * Calling it again does nothing.
     */
    @Override
    public void close() {
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        try {
            if (!slots.tryAcquire(maxRunning, drain.toNanos(), TimeUnit.NANOSECONDS)) {
                running.values().forEach(Answer::stop);
                if (!slots.tryAcquire(maxRunning, STOPPING.toNanos(), TimeUnit.NANOSECONDS)) {
                    LOG.warn("{} answer(s) did not end in time, so they stay in progress until a reader ends them",
                            running.size());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            threads.shutdown();
            renewals.shutdownNow();
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
        /** Set once someone asked to cancel the answer, here or on another instance, so that the model stops too. */
        private volatile boolean cancelRequested;

        Answer(Owner caller, String conversationId, Asked asked) {
            this.caller = caller;
            this.conversationId = conversationId;
            this.asked = asked;
        }

        String id() {
            return asked.answer().messageId();
        }

        boolean stopRequested() {
            return stopped || endedElsewhere || cancelRequested;
        }

        void stop() {
            stopped = true;
        }

        void cancel() {
            cancelRequested = true;
        }

        void learnEndedElsewhere() {
            endedElsewhere = true;
        }

        /**
         * The next piece, as a delta. An empty piece adds nothing, and the database takes no empty delta. A delta refused
         * for a cancel request tells this writer of the request.
         */
        void write(String piece) {
            if (piece.isEmpty() || endedElsewhere) {
                return;
            }
            switch (messages.addDelta(caller, conversationId, id(), piece)) {
                case Written.Added added -> {
                }
                case Written.CancelRequested refused -> cancelRequested = true;
                default -> endedElsewhere = true;
            }
        }

        /**
         * The ending the model's result calls for. A model that completed keeps its answer whole, even while the instance
         * stops. Any other result of a stopped answer is the instance's doing, not the model's. An answer someone asked to
         * cancel ends as cancelled, whatever the model did, with the usage it reports; so does one whose request only
         * shows when its ending is refused.
         */
        void end(Gateway.Result result) {
            Usage usage = usage(result.usage());
            Written ending = cancelRequested ? new Written.CancelRequested() : switch (result) {
                case Gateway.Completed done -> messages.complete(caller, conversationId, id(), usage);
                case Gateway.Failed failed when !stopped -> messages.fail(caller, conversationId, id(), ErrorCode.MODEL_FAILED);
                default -> messages.fail(caller, conversationId, id(), ErrorCode.STREAM_INTERRUPTED);
            };
            if (ending instanceof Written.CancelRequested) {
                messages.cancel(caller, conversationId, id(), usage);
            }
        }

        /**
         * The writer went wrong: the model stops if it still runs, and the answer ends as interrupted if it can. An answer
         * to be cancelled takes no such ending, and the model's usage went with the failure, so it is left to a reader.
         */
        void interrupt() {
            stopped = true;
            try {
                if (messages.fail(caller, conversationId, id(), ErrorCode.STREAM_INTERRUPTED) instanceof Written.CancelRequested) {
                    LOG.error("Answer {} was to be cancelled, but its writer went wrong and lost the model's usage, so it stays"
                            + " in progress until a reader ends it", id());
                }
            } catch (RuntimeException e) {
                LOG.error("Answer {} could not be ended either, so it stays in progress until a reader ends it", id(), e);
            }
        }

        private static Usage usage(Gateway.Usage usage) {
            return new Usage(usage.inputTokens(), usage.outputTokens());
        }
    }
}
