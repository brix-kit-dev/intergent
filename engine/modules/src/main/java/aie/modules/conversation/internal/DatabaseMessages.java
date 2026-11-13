package aie.modules.conversation.internal;

import aie.core.Ids;
import aie.core.tenancy.api.ApiKeyLookup.Owner;
import aie.modules.conversation.api.Messages;
import aie.ports.DatabasePort;
import aie.ports.Row;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@link Messages} on the database. Each write takes the conversation from the caller's application in the same
 * statement, so a conversation of another application adds nothing, and row-level security keeps each tenant to its own
 * rows. The table holds the rules itself as well, for SQL that goes around this class.
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
    private static final String OF_THE_APPLICATION = """
            SELECT m.message_id, m.conversation_id, m.role, m.status, m.text, m.created_at, m.completed_at
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
        return database.inTenantTx(caller.tenantId(), tx -> tx.query(START_ASSISTANT_MESSAGE, DatabaseMessages::message,
                Ids.next("msg"), caller.applicationId(), conversationId)).stream().findFirst();
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
}
