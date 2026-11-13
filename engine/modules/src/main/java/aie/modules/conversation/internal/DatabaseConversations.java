package aie.modules.conversation.internal;

import aie.core.Ids;
import aie.core.tenancy.api.ApiKeyLookup.Owner;
import aie.core.tenancy.api.SessionTokens;
import aie.modules.conversation.api.Conversations;
import aie.ports.DatabasePort;
import aie.ports.Row;
import java.time.Instant;
import java.util.Optional;

/**
 * {@link Conversations} on the database. Row-level security keeps each tenant to its own rows, and every lookup also
 * names the caller's application.
 */
public final class DatabaseConversations implements Conversations {

    private static final String ADD = """
            INSERT INTO conversation_conversations (conversation_id, tenant_id, application_id, end_user_id, locale)
            VALUES (?, ?, ?, ?, ?)
            RETURNING created_at
            """;
    private static final String FIND = """
            SELECT conversation_id, end_user_id, locale, created_at
            FROM conversation_conversations
            WHERE application_id = ? AND conversation_id = ?
            """;

    private final DatabasePort database;

    public DatabaseConversations(DatabasePort database) {
        this.database = database;
    }

    /** The locale has the same rule as the session token's, so a token's locale can start a conversation as it is. */
    @Override
    public Conversation start(Owner caller, String endUserId, String locale) {
        if (!SessionTokens.isId(SessionTokens.END_USER_ID, endUserId)) {
            throw new IllegalArgumentException(
                    "A conversation's end user ID must match " + SessionTokens.END_USER_ID + ", and " + endUserId + " does not");
        }
        if (locale != null && !SessionTokens.standardLocale(locale).equals(Optional.of(locale))) {
            throw new IllegalArgumentException("A conversation's locale must be a language tag in standard form, and "
                    + locale + " is not");
        }
        String conversationId = Ids.next("conv");
        Instant createdAt = database.inTenantTx(caller.tenantId(), tx -> tx.query(ADD, row -> row.time("created_at"),
                conversationId, caller.tenantId(), caller.applicationId(), endUserId, locale).getFirst());
        return new Conversation(conversationId, endUserId, locale, createdAt);
    }

    @Override
    public Optional<Conversation> find(Owner caller, String conversationId) {
        return database.inTenantTx(caller.tenantId(), tx -> tx.query(
                FIND, DatabaseConversations::conversation, caller.applicationId(), conversationId)).stream().findFirst();
    }

    private static Conversation conversation(Row row) {
        return new Conversation(row.string("conversation_id"), row.string("end_user_id"), row.string("locale"),
                row.time("created_at"));
    }
}
