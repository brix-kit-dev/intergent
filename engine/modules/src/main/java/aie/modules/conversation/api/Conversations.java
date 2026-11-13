package aie.modules.conversation.api;

import aie.core.tenancy.api.ApiKeyLookup.Owner;
import java.time.Instant;
import java.util.Optional;

/**
 * The conversations of blueprint 6.3. A conversation belongs to one end user of one application, and is read only
 * within that application: keys and end users are per application, so a test key never reads a live conversation.
 */
public interface Conversations {

    /**
     * Starts a conversation for an end user of the caller's application.
     *
     * @param endUserId the end user, as tenancy's EndUsers gave it for the same application
     * @param locale a language tag in standard form, or null when the caller gave none
     * @throws IllegalArgumentException for an end user ID or a locale that is not well formed
     */
    Conversation start(Owner caller, String endUserId, String locale);

    /** The conversation with this ID in the caller's application. One of another tenant or application is not found. */
    Optional<Conversation> find(Owner caller, String conversationId);

    /** A conversation, with a locale of null when none was given. */
    record Conversation(String conversationId, String endUserId, String locale, Instant createdAt) {
    }
}
