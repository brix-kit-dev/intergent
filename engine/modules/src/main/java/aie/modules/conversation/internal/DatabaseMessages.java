package aie.modules.conversation.internal;

import aie.core.Ids;
import aie.core.tenancy.api.ApiKeyLookup.Owner;
import aie.modules.conversation.api.Messages;
import aie.ports.DatabasePort;
import aie.ports.Row;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * {@link Messages} on the database. Each write takes the conversation from the caller's application in the same
 * statement, so a conversation of another application adds nothing, and row-level security keeps each tenant to its own
 * rows. The tables hold the rules themselves as well, for SQL that goes around this class: the database numbers the
 * events, and a message changes only through them.
 */
public final class DatabaseMessages implements Messages {

    private static final String COLUMNS = "message_id, conversation_id, role, status, text, created_at, completed_at";
    private static final String ADD_USER_MESSAGE = """
            INSERT INTO conversation_messages (message_id, tenant_id, conversation_id, role, status, text, completed_at)
            SELECT ?, tenant_id, conversation_id, 'user', 'completed', ?, now()
            FROM conversation_conversations
            WHERE application_id = ? AND conversation_id = ?
            RETURNING %s
            """.formatted(COLUMNS);
    private static final String START_ASSISTANT_MESSAGE = """
            INSERT INTO conversation_messages (message_id, tenant_id, conversation_id, role, status)
            SELECT ?, tenant_id, conversation_id, 'assistant', 'in_progress'
            FROM conversation_conversations
            WHERE application_id = ? AND conversation_id = ?
            RETURNING %s
            """.formatted(COLUMNS);
    private static final String ADD_STARTED = """
            INSERT INTO conversation_events (tenant_id, message_id, type) VALUES (?, ?, 'message.started')
            """;
    private static final String ADD_DELTA = """
            INSERT INTO conversation_events (tenant_id, message_id, type, text)
            SELECT m.tenant_id, m.message_id, 'message.delta', ?
            FROM conversation_messages m
            JOIN conversation_conversations c ON c.tenant_id = m.tenant_id AND c.conversation_id = m.conversation_id
            WHERE c.application_id = ? AND c.conversation_id = ? AND m.message_id = ? AND m.role = 'assistant'
            RETURNING seq
            """;
    private static final String EVENTS_AFTER = """
            SELECT e.message_id, e.seq, e.type, e.text, e.created_at
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
                m.created_at, m.completed_at
            FROM conversation_messages m
            JOIN conversation_conversations c ON c.tenant_id = m.tenant_id AND c.conversation_id = m.conversation_id
            WHERE c.application_id = ? AND c.conversation_id = ?
            """;
    private static final String FIND = OF_THE_APPLICATION + "AND m.message_id = ?";
    private static final String LIST = OF_THE_APPLICATION + "ORDER BY m.ordinal";

    private final DatabasePort database;

    public DatabaseMessages(DatabasePort database) {
        this.database = database;
    }

    @Override
    public Optional<Message> addUserMessage(Owner caller, String conversationId, String text) {
        if (text == null || text.isEmpty() || text.codePointCount(0, text.length()) > MAX_TEXT) {
            throw new IllegalArgumentException("A user message must have 1 to " + MAX_TEXT + " characters");
        }
        if (text.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("A user message cannot hold a NUL character");
        }
        return database.inTenantTx(caller.tenantId(), tx -> tx.query(ADD_USER_MESSAGE, DatabaseMessages::message,
                Ids.next("msg"), text, caller.applicationId(), conversationId)).stream().findFirst();
    }

    @Override
    public Optional<Message> startAssistantMessage(Owner caller, String conversationId) {
        return database.inTenantTx(caller.tenantId(), tx -> {
            List<Message> started = tx.query(START_ASSISTANT_MESSAGE, DatabaseMessages::message,
                    Ids.next("msg"), caller.applicationId(), conversationId);
            for (Message answer : started) {
                tx.update(ADD_STARTED, caller.tenantId(), answer.messageId());
            }
            return started;
        }).stream().findFirst();
    }

    @Override
    public OptionalLong addDelta(Owner caller, String conversationId, String messageId, String text) {
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("A delta must have text");
        }
        if (text.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("A delta cannot hold a NUL character");
        }
        List<Long> numbers = database.inTenantTx(caller.tenantId(), tx -> tx.query(ADD_DELTA, row -> row.number("seq"),
                text, caller.applicationId(), conversationId, messageId));
        return numbers.isEmpty() ? OptionalLong.empty() : OptionalLong.of(numbers.getFirst());
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

    private static Message message(Row row) {
        return new Message(row.string("message_id"), row.string("conversation_id"),
                Role.valueOf(row.string("role").toUpperCase(Locale.ROOT)),
                Status.valueOf(row.string("status").toUpperCase(Locale.ROOT)), row.string("text"),
                row.time("created_at"), row.time("completed_at"));
    }

    private static Event event(Row row) {
        String type = row.string("type");
        for (EventType known : EventType.values()) {
            if (known.wireName().equals(type)) {
                return new Event(row.string("message_id"), row.number("seq"), known, row.string("text"), row.time("created_at"));
            }
        }
        throw new IllegalStateException("The event log holds an event type this engine does not know: " + type);
    }
}
