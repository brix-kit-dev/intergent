package aie.modules.conversation.api;

import aie.core.tenancy.api.ApiKeyLookup.Owner;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The messages of a conversation (blueprint 6.3). What the end user says is stored whole, and an answer starts in
 * progress and empty. Every call works within the caller's application, as {@link Conversations} does: a conversation
 * of another application or tenant is treated as one that does not exist.
 */
public interface Messages {

    /** The contract's limit for what the end user says, in characters, counted as JSON Schema counts them. */
    int MAX_TEXT = 32000;

    /**
     * Stores what the end user said, completed as it is stored.
     *
     * @return the message, or empty when the conversation is not one of the caller's application
     * @throws IllegalArgumentException for text that is empty, over {@link #MAX_TEXT} characters, or holds a NUL, which
     *     the database cannot store
     */
    Optional<Message> addUserMessage(Owner caller, String conversationId, String text);

    /**
     * Starts an answer: an assistant message, in progress and empty.
     *
     * @return the message, or empty when the conversation is not one of the caller's application
     */
    Optional<Message> startAssistantMessage(Owner caller, String conversationId);

    /** The message with this ID in this conversation of the caller's application. */
    Optional<Message> find(Owner caller, String conversationId, String messageId);

    /** The conversation's messages, oldest first. Empty for a conversation that is not one of the caller's application. */
    List<Message> list(Owner caller, String conversationId);

    /** Who wrote a message. */
    enum Role {
        USER, ASSISTANT
    }

    /** Where a message stands. Only an answer is ever in progress. */
    enum Status {
        IN_PROGRESS, COMPLETED, CANCELLED, FAILED
    }

    /** A message, with a completedAt of null while it is in progress. */
    record Message(String messageId, String conversationId, Role role, Status status, String text, Instant createdAt,
            Instant completedAt) {
    }
}
