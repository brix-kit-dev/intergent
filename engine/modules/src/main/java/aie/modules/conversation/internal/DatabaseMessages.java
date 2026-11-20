package aie.modules.conversation.internal;

import aie.core.Ids;
import aie.core.tenancy.api.ApiKeyLookup.Owner;
import aie.core.trust.api.DataClass;
import aie.modules.conversation.api.EventSignals;
import aie.modules.conversation.api.Messages;
import aie.ports.DatabaseException;
import aie.ports.DatabasePort;
import aie.ports.Row;
import aie.ports.Tx;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link Messages} on the database. Each write takes the conversation from the caller's application in the same
 * statement, so a conversation of another application adds nothing, and row-level security keeps each tenant to its own
 * rows. The tables hold the rules themselves as well, for SQL that goes around this class: the database numbers the
 * events, ends an answer once, changes a message only through its events, lets an answer with a cancel request end
 * only as cancelled, and keeps a lease with each answer in progress alone.
 */
public final class DatabaseMessages implements Messages {

    /** A message as a statement returns it. A usage comes as a flag and two numbers, because Row.number refuses null. */
    private static final String RETURNED = """
            message_id, conversation_id, role, status, text, input_tokens IS NOT NULL AS has_usage,
            coalesce(input_tokens, 0) AS input_tokens, coalesce(output_tokens, 0) AS output_tokens, code, created_at,
            completed_at
            """;
    private static final String ADD_USER_MESSAGE = """
            INSERT INTO conversation_messages (message_id, tenant_id, conversation_id, role, status, text, completed_at, data_class)
            SELECT ?, tenant_id, conversation_id, 'user', 'completed', ?, now(), data_class_watermark
            FROM conversation_conversations
            WHERE application_id = ? AND conversation_id = ?
            RETURNING %s
            """.formatted(RETURNED);
    private static final String START_ASSISTANT_MESSAGE = """
            INSERT INTO conversation_messages (message_id, tenant_id, conversation_id, role, status, data_class)
            SELECT ?, tenant_id, conversation_id, 'assistant', 'in_progress', data_class_watermark
            FROM conversation_conversations
            WHERE application_id = ? AND conversation_id = ?
            RETURNING %s
            """.formatted(RETURNED);
    /**
     * Asks in one conversation take turns on the conversation's row. This lock is the weakest that two asks cannot hold
     * at once, and it leaves the row free for the key-share lock that each new message's foreign key takes.
     */
    private static final String TAKE_TURN = """
            SELECT data_class_watermark FROM conversation_conversations
            WHERE application_id = ? AND conversation_id = ?
            FOR NO KEY UPDATE
            """;
    /** The watermark rises to the call's class before its messages are stored, which take it as their class. */
    private static final String RAISE_WATERMARK = """
            UPDATE conversation_conversations SET data_class_watermark = ?
            WHERE application_id = ? AND conversation_id = ?
            """;
    private static final String ADD_STARTED = """
            INSERT INTO conversation_events (tenant_id, message_id, type) VALUES (?, ?, 'message.started')
            """;
    /** The lease as long as {@link Messages#LEASE}, as an interval the database reads. */
    private static final String LEASE_INTERVAL = LEASE.toSeconds() + " seconds";
    private static final String ADD_LEASE = """
            INSERT INTO conversation_answer_leases (tenant_id, message_id, expires_at) VALUES (?, ?, now() + ?::interval)
            """;
    /**
     * An answer that has ended is left out here; the database would refuse the renewal anyway. A request to cancel comes
     * back with the renewal, as the statement saw it.
     */
    private static final String RENEW = """
            UPDATE conversation_answer_leases l
            SET expires_at = now() + ?::interval
            FROM conversation_messages m
            JOIN conversation_conversations c ON c.tenant_id = m.tenant_id AND c.conversation_id = m.conversation_id
            WHERE l.tenant_id = m.tenant_id AND l.message_id = m.message_id
                AND c.application_id = ? AND c.conversation_id = ? AND m.message_id = ? AND m.role = 'assistant'
                AND m.status = 'in_progress'
            RETURNING EXISTS (
                SELECT FROM conversation_cancel_requests r WHERE r.tenant_id = l.tenant_id AND r.message_id = l.message_id)
                AS cancel_requested
            """;
    /**
     * The lease of an answer, locked, so that a renewal waits until this transaction ends, and one that came first is seen
     * here. Whether it has run out is read after the lock.
     */
    private static final String HOLD_LEASE = """
            SELECT m.status, l.expires_at <= now() AS expired, EXISTS (
                    SELECT FROM conversation_cancel_requests r WHERE r.tenant_id = m.tenant_id AND r.message_id = m.message_id)
                AS cancel_requested
            FROM conversation_answer_leases l
            JOIN conversation_messages m ON m.tenant_id = l.tenant_id AND m.message_id = l.message_id
            JOIN conversation_conversations c ON c.tenant_id = m.tenant_id AND c.conversation_id = m.conversation_id
            WHERE c.application_id = ? AND c.conversation_id = ? AND l.message_id = ? AND m.role = 'assistant'
            FOR UPDATE OF l
            """;
    /**
     * An answer that has ended is left out here, and so is one with a cancel request, for any event but
     * message.cancelled. The database would refuse their event anyway.
     */
    private static final String ADD_EVENT = """
            INSERT INTO conversation_events (tenant_id, message_id, type, text, input_tokens, output_tokens, code)
            SELECT m.tenant_id, m.message_id, ?, ?::text, ?::bigint, ?::bigint, ?::text
            FROM conversation_messages m
            JOIN conversation_conversations c ON c.tenant_id = m.tenant_id AND c.conversation_id = m.conversation_id
            WHERE c.application_id = ? AND c.conversation_id = ? AND m.message_id = ? AND m.role = 'assistant'
                AND m.status = 'in_progress'
                AND (?::text = 'message.cancelled' OR NOT EXISTS (
                    SELECT FROM conversation_cancel_requests r WHERE r.tenant_id = m.tenant_id AND r.message_id = m.message_id))
            RETURNING seq
            """;
    /** Where an answer stands, and whether someone asked for it to be cancelled. */
    private static final String ANSWER_STATE = """
            SELECT m.status, EXISTS (
                    SELECT FROM conversation_cancel_requests r WHERE r.tenant_id = m.tenant_id AND r.message_id = m.message_id)
                AS cancel_requested
            FROM conversation_messages m
            JOIN conversation_conversations c ON c.tenant_id = m.tenant_id AND c.conversation_id = m.conversation_id
            WHERE c.application_id = ? AND c.conversation_id = ? AND m.message_id = ? AND m.role = 'assistant'
            """;
    /**
     * An answer that has ended is left out here; the database would refuse its request anyway. A request made before
     * stands, and this one adds nothing.
     */
    private static final String REQUEST_CANCEL = """
            INSERT INTO conversation_cancel_requests (tenant_id, message_id)
            SELECT m.tenant_id, m.message_id
            FROM conversation_messages m
            JOIN conversation_conversations c ON c.tenant_id = m.tenant_id AND c.conversation_id = m.conversation_id
            WHERE c.application_id = ? AND c.conversation_id = ? AND m.message_id = ? AND m.role = 'assistant'
                AND m.status = 'in_progress'
            ON CONFLICT (tenant_id, message_id) DO NOTHING
            RETURNING message_id
            """;
    private static final String EVENTS_AFTER = """
            SELECT e.message_id, e.seq, e.type, e.text, e.input_tokens IS NOT NULL AS has_usage,
                coalesce(e.input_tokens, 0) AS input_tokens, coalesce(e.output_tokens, 0) AS output_tokens, e.code,
                e.created_at
            FROM conversation_events e
            JOIN conversation_messages m ON m.tenant_id = e.tenant_id AND m.message_id = e.message_id
            JOIN conversation_conversations c ON c.tenant_id = m.tenant_id AND c.conversation_id = m.conversation_id
            WHERE c.application_id = ? AND c.conversation_id = ? AND e.message_id = ? AND e.seq > ?
            ORDER BY e.seq
            LIMIT ?
            """;
    /** An answer in progress has no text of its own yet: its text is its deltas so far. */
    private static final String OF_THE_APPLICATION = """
            SELECT m.message_id, m.conversation_id, m.role, m.status,
                CASE WHEN m.status = 'in_progress' THEN coalesce((
                    SELECT string_agg(e.text, '' ORDER BY e.seq)
                    FROM conversation_events e
                    WHERE e.tenant_id = m.tenant_id AND e.message_id = m.message_id AND e.type = 'message.delta'), '')
                ELSE m.text END AS text,
                m.input_tokens IS NOT NULL AS has_usage, coalesce(m.input_tokens, 0) AS input_tokens,
                coalesce(m.output_tokens, 0) AS output_tokens, m.code, m.created_at, m.completed_at
            FROM conversation_messages m
            JOIN conversation_conversations c ON c.tenant_id = m.tenant_id AND c.conversation_id = m.conversation_id
            WHERE c.application_id = ? AND c.conversation_id = ?
            """;
    /**
     * The last messages before an answer that count for the model, at most the given number, oldest first. A user message
     * is stored completed, so this takes every one, with the answers that completed or were cancelled with some text.
     */
    private static final String HISTORY = """
            SELECT h.* FROM (
                SELECT m.message_id, m.conversation_id, m.role, m.status, m.text, m.input_tokens IS NOT NULL AS has_usage,
                    coalesce(m.input_tokens, 0) AS input_tokens, coalesce(m.output_tokens, 0) AS output_tokens, m.code,
                    m.created_at, m.completed_at, m.ordinal
                FROM conversation_messages a
                JOIN conversation_conversations c ON c.tenant_id = a.tenant_id AND c.conversation_id = a.conversation_id
                JOIN conversation_messages m ON m.tenant_id = a.tenant_id AND m.conversation_id = a.conversation_id
                    AND m.ordinal < a.ordinal
                WHERE c.application_id = ? AND c.conversation_id = ? AND a.message_id = ? AND a.role = 'assistant'
                    AND m.status IN ('completed', 'cancelled') AND m.text <> ''
                ORDER BY m.ordinal DESC
                LIMIT ?
            ) h
            ORDER BY h.ordinal
            """;
    private static final String FIND = OF_THE_APPLICATION + "AND m.message_id = ?";
    private static final String LIST = OF_THE_APPLICATION + "ORDER BY m.ordinal";
    /** The conversation, when it is one of the caller's application. */
    private static final String CONVERSATION = """
            SELECT conversation_id FROM conversation_conversations WHERE application_id = ? AND conversation_id = ?
            """;
    /** Where a message of the conversation stands: the database's own number for it. */
    private static final String POSITION = """
            SELECT m.ordinal
            FROM conversation_messages m
            JOIN conversation_conversations c ON c.tenant_id = m.tenant_id AND c.conversation_id = m.conversation_id
            WHERE c.application_id = ? AND c.conversation_id = ? AND m.message_id = ?
            """;
    /**
     * The messages after a number, in its order. The index on the conversation and the number holds them in this order,
     * so a page of a long conversation reads its own rows and no others.
     */
    private static final String PAGE = OF_THE_APPLICATION + "AND m.ordinal > ? ORDER BY m.ordinal LIMIT ?";
    /** The database numbers messages from 1, so the first message comes after this one. */
    private static final long BEFORE_THE_FIRST = 0;

    private static final Logger LOG = LoggerFactory.getLogger(DatabaseMessages.class);

    private final DatabasePort database;
    private final EventSignals signals;

    /** With no signals, for code that streams nothing. */
    public DatabaseMessages(DatabasePort database) {
        this(database, EventSignals.NONE);
    }

    /** Each event that goes in rings its answer's signal, once its transaction has committed. */
    public DatabaseMessages(DatabasePort database, EventSignals signals) {
        this.database = database;
        this.signals = signals;
    }

    @Override
    public Optional<Message> addUserMessage(Owner caller, String conversationId, String text) {
        userText(text);
        return database.inTenantTx(caller.tenantId(), tx -> tx.query(ADD_USER_MESSAGE, DatabaseMessages::message,
                Ids.next("msg"), text, caller.applicationId(), conversationId)).stream().findFirst();
    }

    @Override
    public Optional<Message> startAssistantMessage(Owner caller, String conversationId) {
        return database.inTenantTx(caller.tenantId(), tx -> start(tx, caller, conversationId)).stream().findFirst();
    }

    /**
     * Takes the conversation's turn first, so that no other ask puts a message between the question and its answer, and
     * the watermark the call is measured by stays as read until the transaction ends. A conversation out of reach takes no
     * lock, and nothing is stored; nor is anything for a call above the ceiling.
     */
    @Override
    public Ask ask(Owner caller, String conversationId, String text, DataClass floor, DataClass ceiling) {
        userText(text);
        if (floor == null) {
            throw new IllegalArgumentException("An ask needs its least data class, the application's default at the least");
        }
        if (ceiling == null) {
            throw new IllegalArgumentException("An ask needs the ceiling of its task, the highest class its models take");
        }
        return database.<Ask>inTenantTx(caller.tenantId(), tx -> {
            List<String> watermark = tx.query(TAKE_TURN, row -> row.string("data_class_watermark"),
                    caller.applicationId(), conversationId);
            if (watermark.isEmpty()) {
                return new Ask.NotFound();
            }
            DataClass held = DataClass.fromWireName(watermark.getFirst()).orElseThrow();
            DataClass call = floor.compareTo(held) > 0 ? floor : held;
            if (call.compareTo(ceiling) > 0) {
                return new Ask.AboveCeiling(call);
            }
            if (call != held) {
                tx.update(RAISE_WATERMARK, call.wireName(), caller.applicationId(), conversationId);
            }
            Message question = tx.query(ADD_USER_MESSAGE, DatabaseMessages::message, Ids.next("msg"), text,
                    caller.applicationId(), conversationId).getFirst();
            Message answer = start(tx, caller, conversationId).getFirst();
            return new Asked(question, answer, call);
        });
    }

    private static void userText(String text) {
        if (text == null || text.isEmpty() || text.codePointCount(0, text.length()) > MAX_TEXT) {
            throw new IllegalArgumentException("A user message must have 1 to " + MAX_TEXT + " characters");
        }
        if (text.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("A user message cannot hold a NUL character");
        }
    }

    /**
     * An answer in progress with its message.started event and its writer's lease, or none when the conversation is out of
     * reach.
     */
    private static List<Message> start(Tx tx, Owner caller, String conversationId) {
        List<Message> started = tx.query(START_ASSISTANT_MESSAGE, DatabaseMessages::message,
                Ids.next("msg"), caller.applicationId(), conversationId);
        for (Message answer : started) {
            tx.update(ADD_STARTED, caller.tenantId(), answer.messageId());
            tx.update(ADD_LEASE, caller.tenantId(), answer.messageId(), LEASE_INTERVAL);
        }
        return started;
    }

    @Override
    public Written addDelta(Owner caller, String conversationId, String messageId, String text) {
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("A delta must have text");
        }
        if (text.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("A delta cannot hold a NUL character");
        }
        return write(caller, conversationId, messageId, EventType.MESSAGE_DELTA, text, null, null);
    }

    @Override
    public Written complete(Owner caller, String conversationId, String messageId, Usage usage) {
        return write(caller, conversationId, messageId, EventType.MESSAGE_COMPLETED, null, required(usage), null);
    }

    /**
     * The database fills in what was generated, from the deltas, under the same lock that numbers the event. It refuses
     * the event for an answer without a cancel request (V7); the answer is in progress then, so the refusal goes on.
     */
    @Override
    public Written cancel(Owner caller, String conversationId, String messageId, Usage usage) {
        return write(caller, conversationId, messageId, EventType.MESSAGE_CANCELLED, null, required(usage), null);
    }

    @Override
    public Written fail(Owner caller, String conversationId, String messageId, ErrorCode code) {
        if (code == null) {
            throw new IllegalArgumentException("A failed answer needs its code");
        }
        return write(caller, conversationId, messageId, EventType.ERROR, null, null, code);
    }

    private static Usage required(Usage usage) {
        if (usage == null) {
            throw new IllegalArgumentException("An answer that completes or is cancelled needs its usage");
        }
        return usage;
    }

    /**
     * Adds an event, or says why it could not. An answer that ended, or had a cancel request that refuses this event,
     * before this write began is left out by the statement. One that ends or gets a request while this write waits for
     * its lock makes the database refuse the event; a fresh look at the answer then tells that refusal from any other
     * failure.
     */
    private Written write(Owner caller, String conversationId, String messageId, EventType type, String text, Usage usage,
            ErrorCode code) {
        List<Long> numbers;
        try {
            numbers = database.inTenantTx(caller.tenantId(), tx -> tx.query(ADD_EVENT, row -> row.number("seq"),
                    type.wireName(), text, usage == null ? null : usage.inputTokens(),
                    usage == null ? null : usage.outputTokens(), code == null ? null : code.wireName(),
                    caller.applicationId(), conversationId, messageId, type.wireName()));
        } catch (DatabaseException refused) {
            Optional<Written> why = stateOf(caller, conversationId, messageId).flatMap(state -> state.refusal(type));
            if (why.isPresent()) {
                return why.get();
            }
            throw refused;
        }
        if (!numbers.isEmpty()) {
            signals.ring(messageId);
            return new Written.Added(numbers.getFirst());
        }
        Optional<AnswerState> state = stateOf(caller, conversationId, messageId);
        if (state.isEmpty()) {
            return new Written.NotFound();
        }
        return state.get().refusal(type).orElseThrow(
                () -> new IllegalStateException("Answer " + messageId + " is in progress, yet it took no event"));
    }

    /**
     * Records the request under the answer's lock, as an event goes in. An answer that ended before this request began is
     * left out by the statement, and so is one with a request already. One that ends while this request waits for its lock
     * makes the database refuse the request; a fresh look at the answer then tells that refusal from any other failure.
     */
    @Override
    public CancelRequest requestCancel(Owner caller, String conversationId, String messageId) {
        List<String> recorded;
        try {
            recorded = database.inTenantTx(caller.tenantId(), tx -> tx.query(REQUEST_CANCEL,
                    row -> row.string("message_id"), caller.applicationId(), conversationId, messageId));
        } catch (DatabaseException refused) {
            Optional<AnswerState> state = stateOf(caller, conversationId, messageId);
            if (state.isPresent() && state.get().status() != Status.IN_PROGRESS) {
                return new CancelRequest.AlreadyEnded(state.get().status());
            }
            throw refused;
        }
        if (!recorded.isEmpty()) {
            return new CancelRequest.Recorded();
        }
        Optional<AnswerState> state = stateOf(caller, conversationId, messageId);
        if (state.isEmpty()) {
            return new CancelRequest.NotFound();
        }
        if (state.get().status() != Status.IN_PROGRESS) {
            return new CancelRequest.AlreadyEnded(state.get().status());
        }
        if (!state.get().cancelRequested()) {
            throw new IllegalStateException("Answer " + messageId + " is in progress, yet it took no cancel request");
        }
        return new CancelRequest.Recorded();
    }

    /**
     * Renews under the answer's lock, as an event goes in. An answer that ended before this renewal began is left out by
     * the statement; one that ends while it waits for the lock makes the database refuse it, and a fresh look then tells
     * that refusal from any other failure.
     */
    @Override
    public Renewal renew(Owner caller, String conversationId, String messageId) {
        List<Boolean> renewed;
        try {
            renewed = database.inTenantTx(caller.tenantId(), tx -> tx.query(RENEW, row -> row.flag("cancel_requested"),
                    LEASE_INTERVAL, caller.applicationId(), conversationId, messageId));
        } catch (DatabaseException refused) {
            Optional<AnswerState> state = stateOf(caller, conversationId, messageId);
            if (state.isPresent() && state.get().status() != Status.IN_PROGRESS) {
                return new Renewal.AlreadyEnded(state.get().status());
            }
            throw refused;
        }
        if (!renewed.isEmpty()) {
            return new Renewal.Renewed(renewed.getFirst());
        }
        Optional<AnswerState> state = stateOf(caller, conversationId, messageId);
        if (state.isEmpty()) {
            return new Renewal.NotFound();
        }
        if (state.get().status() == Status.IN_PROGRESS) {
            throw new IllegalStateException("Answer " + messageId + " is in progress, yet it has no lease to renew");
        }
        return new Renewal.AlreadyEnded(state.get().status());
    }

    /**
     * Looks at the lease and writes the ending in one transaction, with the lease locked: a renewal that came first is
     * seen, and one that comes now waits, then finds the answer ended. A request to cancel that comes while this call
     * writes leaves message.cancelled as the only ending; when it comes too late for even that, the answer goes on, and a
     * later look ends it.
     */
    @Override
    public Expiry endIfExpired(Owner caller, String conversationId, String messageId) {
        Optional<Expiry> outcome;
        try {
            outcome = database.inTenantTx(caller.tenantId(), tx -> endExpired(tx, caller, conversationId, messageId));
        } catch (DatabaseException refused) {
            Optional<AnswerState> state = stateOf(caller, conversationId, messageId);
            if (state.isPresent() && state.get().status() != Status.IN_PROGRESS) {
                return new Expiry.AlreadyEnded(state.get().status());
            }
            if (state.isPresent() && state.get().cancelRequested()) {
                return new Expiry.Running();
            }
            throw refused;
        }
        if (outcome.isPresent()) {
            if (outcome.get() instanceof Expiry.Ended ended) {
                signals.ring(messageId);
                if (ended.ending() == EventType.MESSAGE_CANCELLED) {
                    LOG.warn("Answer {} was cancelled after its writer's lease ran out, so what its model used is not known"
                            + " and counts as 0", messageId);
                }
            }
            return outcome.get();
        }
        Optional<AnswerState> state = stateOf(caller, conversationId, messageId);
        if (state.isEmpty()) {
            return new Expiry.NotFound();
        }
        if (state.get().status() == Status.IN_PROGRESS) {
            throw new IllegalStateException("Answer " + messageId + " is in progress, yet it has no lease");
        }
        return new Expiry.AlreadyEnded(state.get().status());
    }

    /** Empty when the answer has no lease, or ended while this transaction wrote; a fresh look tells which. */
    private static Optional<Expiry> endExpired(Tx tx, Owner caller, String conversationId, String messageId) {
        List<Lease> held = tx.query(HOLD_LEASE, Lease::of, caller.applicationId(), conversationId, messageId);
        if (held.isEmpty()) {
            return Optional.empty();
        }
        Lease lease = held.getFirst();
        if (lease.status() != Status.IN_PROGRESS) {
            return Optional.of(new Expiry.AlreadyEnded(lease.status()));
        }
        if (!lease.expired()) {
            return Optional.of(new Expiry.Running());
        }
        if (!lease.cancelRequested() && addEnding(tx, caller, conversationId, messageId, EventType.ERROR)) {
            return Optional.of(new Expiry.Ended(EventType.ERROR));
        }
        if (addEnding(tx, caller, conversationId, messageId, EventType.MESSAGE_CANCELLED)) {
            return Optional.of(new Expiry.Ended(EventType.MESSAGE_CANCELLED));
        }
        return Optional.empty();
    }

    /**
     * The ending a reader writes: error with stream_interrupted, or message.cancelled with what the log holds and no
     * tokens, since the writer that knew them is gone. False when the statement left the answer out.
     */
    private static boolean addEnding(Tx tx, Owner caller, String conversationId, String messageId, EventType ending) {
        boolean cancelled = ending == EventType.MESSAGE_CANCELLED;
        return !tx.query(ADD_EVENT, row -> row.number("seq"), ending.wireName(), null, cancelled ? 0L : null,
                cancelled ? 0L : null, cancelled ? null : ErrorCode.STREAM_INTERRUPTED.wireName(),
                caller.applicationId(), conversationId, messageId, ending.wireName()).isEmpty();
    }

    /** An answer's lease as a reader holds it. */
    private record Lease(Status status, boolean expired, boolean cancelRequested) {

        static Lease of(Row row) {
            return new Lease(DatabaseMessages.status(row.string("status")), row.flag("expired"), row.flag("cancel_requested"));
        }
    }

    private Optional<AnswerState> stateOf(Owner caller, String conversationId, String messageId) {
        return database.inTenantTx(caller.tenantId(), tx -> tx.query(ANSWER_STATE,
                row -> new AnswerState(status(row.string("status")), row.flag("cancel_requested")),
                caller.applicationId(), conversationId, messageId)).stream().findFirst();
    }

    /** Where an answer stands, as a fresh look finds it. */
    private record AnswerState(Status status, boolean cancelRequested) {

        /** Why the answer takes no event of this type, or empty when it takes one. */
        Optional<Written> refusal(EventType type) {
            if (status != Status.IN_PROGRESS) {
                return Optional.of(new Written.AlreadyEnded(status));
            }
            if (cancelRequested && type != EventType.MESSAGE_CANCELLED) {
                return Optional.of(new Written.CancelRequested());
            }
            return Optional.empty();
        }
    }

    @Override
    public List<Event> eventsAfter(Owner caller, String conversationId, String messageId, long after, int limit) {
        if (after < 0) {
            throw new IllegalArgumentException("Events are numbered from 1, so after must be 0 or more");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("The limit must be 1 or more");
        }
        return database.inTenantTx(caller.tenantId(), tx -> tx.query(EVENTS_AFTER, DatabaseMessages::event,
                caller.applicationId(), conversationId, messageId, after, limit));
    }

    @Override
    public Optional<Message> find(Owner caller, String conversationId, String messageId) {
        return database.inTenantTx(caller.tenantId(), tx -> tx.query(
                FIND, DatabaseMessages::message, caller.applicationId(), conversationId, messageId)).stream().findFirst();
    }

    @Override
    public List<Message> list(Owner caller, String conversationId) {
        return database.inTenantTx(caller.tenantId(), tx -> tx.query(
                LIST, DatabaseMessages::message, caller.applicationId(), conversationId));
    }

    /**
     * The conversation, the position and the page, in one transaction. The page reads one message more than it holds, so
     * that it knows whether more come without counting them.
     */
    @Override
    public Page page(Owner caller, String conversationId, String after, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("A page holds 1 message or more");
        }
        return database.inTenantTx(caller.tenantId(), tx -> {
            if (tx.query(CONVERSATION, row -> row.string("conversation_id"), caller.applicationId(), conversationId).isEmpty()) {
                return new Page.NotFound();
            }
            long from = BEFORE_THE_FIRST;
            if (after != null) {
                List<Long> position = tx.query(POSITION, row -> row.number("ordinal"), caller.applicationId(), conversationId, after);
                if (position.isEmpty()) {
                    return new Page.NoSuchPosition();
                }
                from = position.getFirst();
            }
            List<Message> read = tx.query(
                    PAGE, DatabaseMessages::message, caller.applicationId(), conversationId, from, limit + 1L);
            return read.size() > limit ? new Page.Found(read.subList(0, limit), true) : new Page.Found(read, false);
        });
    }

    /** The database picks the last messages that count; the answers before the first user message among them go here. */
    @Override
    public List<Message> historyFor(Owner caller, String conversationId, String answerId) {
        List<Message> history = new ArrayList<>(database.inTenantTx(caller.tenantId(), tx -> tx.query(
                HISTORY, DatabaseMessages::message, caller.applicationId(), conversationId, answerId, MAX_HISTORY)));
        while (!history.isEmpty() && history.getFirst().role() != Role.USER) {
            history.removeFirst();
        }
        return List.copyOf(history);
    }

    private static Message message(Row row) {
        return new Message(row.string("message_id"), row.string("conversation_id"),
                Role.valueOf(row.string("role").toUpperCase(Locale.ROOT)), status(row.string("status")), row.string("text"),
                usage(row), code(row.string("code")), row.time("created_at"), row.time("completed_at"));
    }

    private static Event event(Row row) {
        String type = row.string("type");
        for (EventType known : EventType.values()) {
            if (known.wireName().equals(type)) {
                return new Event(row.string("message_id"), row.number("seq"), known, row.string("text"), usage(row),
                        code(row.string("code")), row.time("created_at"));
            }
        }
        throw new IllegalStateException("The event log holds an event type this engine does not know: " + type);
    }

    private static Status status(String wireName) {
        return Status.valueOf(wireName.toUpperCase(Locale.ROOT));
    }

    private static Usage usage(Row row) {
        return row.flag("has_usage") ? new Usage(row.number("input_tokens"), row.number("output_tokens")) : null;
    }

    private static ErrorCode code(String wireName) {
        if (wireName == null) {
            return null;
        }
        for (ErrorCode known : ErrorCode.values()) {
            if (known.wireName().equals(wireName)) {
                return known;
            }
        }
        throw new IllegalStateException("A message holds an error code this engine does not know: " + wireName);
    }
}
