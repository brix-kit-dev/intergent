package aie.modules.conversation.api;

import aie.core.tenancy.api.ApiKeyLookup.Owner;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The messages of a conversation (blueprint 6.3), and the event log of each answer (blueprint 6.4). What the end user
 * says is stored whole. An answer starts in progress with its message.started event, and grows by message.delta events.
 * Every call works within the caller's application, as {@link Conversations} does: a conversation of another
 * application or tenant is treated as one that does not exist.
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
     * Starts an answer: an assistant message, in progress and empty, with its message.started event as number 1, both in
     * one transaction.
     *
     * @return the message, or empty when the conversation is not one of the caller's application
     */
    Optional<Message> startAssistantMessage(Owner caller, String conversationId);

    /**
     * Adds a message.delta event to an answer. The database gives it the next number of the answer.
     *
     * @return the event's number, or empty when the message is not an answer in this conversation of the caller's
     *     application
     * @throws IllegalArgumentException for text that is empty or holds a NUL
     */
    OptionalLong addDelta(Owner caller, String conversationId, String messageId, String text);

    /**
     * The events of an answer with a number above {@code after}, in order, at most {@code limit} of them. Empty when the
     * message is not an answer in this conversation of the caller's application.
     *
     * @throws IllegalArgumentException for an {@code after} below 0 or a {@code limit} below 1
     */
    List<Event> eventsAfter(Owner caller, String conversationId, String messageId, long after, int limit);

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

    /** The kinds of event, by the names the contract gives them. */
    enum EventType {
        MESSAGE_STARTED, MESSAGE_DELTA;

        /** The contract's name, such as message.delta. */
        public String wireName() {
            return name().toLowerCase(Locale.ROOT).replace('_', '.');
        }
    }

    /**
     * A message, with a completedAt of null while it is in progress. The text of an answer in progress is its deltas so
     * far.
     */
    record Message(String messageId, String conversationId, Role role, Status status, String text, Instant createdAt,
            Instant completedAt) {
    }

    /** An event of an answer, with its number. Only a delta has text. */
    record Event(String messageId, long seq, EventType type, String text, Instant createdAt) {
    }
}
