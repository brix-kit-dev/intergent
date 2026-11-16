package aie.modules.conversation.api;

import aie.core.tenancy.api.ApiKeyLookup.Owner;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The messages of a conversation (blueprint 6.3), and the event log of each answer (blueprint 6.4). What the end user
 * says is stored whole. An answer starts in progress with its message.started event, grows by message.delta events, and
 * ends with exactly one ending event: completed, cancelled or error. Every call works within the caller's application, as
 * {@link Conversations} does: a conversation of another application or tenant is treated as one that does not exist.
 */
public interface Messages {

    /** The contract's limit for what the end user says, in characters, counted as JSON Schema counts them. */
    int MAX_TEXT = 32000;

    /** How many messages of a conversation the model is shown at most, with each answer it writes. */
    int MAX_HISTORY = 50;

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
     * Stores what the end user said and starts the answer to it, in one transaction: the user message, completed as it is
     * stored, then the answer, in progress and empty, with its message.started event as number 1. Asks in one conversation
     * take turns, so that each question comes right before its own answer.
     *
     * @return the question and its answer, or empty when the conversation is not one of the caller's application; then
     *     nothing is stored
     * @throws IllegalArgumentException for text that is empty, over {@link #MAX_TEXT} characters, or holds a NUL, which
     *     the database cannot store
     */
    Optional<Asked> ask(Owner caller, String conversationId, String text);

    /**
     * Adds a message.delta event to an answer in progress. The database gives it the next number of the answer.
     *
     * @throws IllegalArgumentException for text that is empty or holds a NUL
     */
    Written addDelta(Owner caller, String conversationId, String messageId, String text);

    /** Ends an answer with message.completed and its usage. */
    Written complete(Owner caller, String conversationId, String messageId, Usage usage);

    /**
     * Ends an answer with message.cancelled, which carries what the deltas so far say, and the usage so far. It is the
     * one event an answer with a cancel request still takes.
     */
    Written cancel(Owner caller, String conversationId, String messageId, Usage usage);

    /** Ends an answer with an error event and its code. */
    Written fail(Owner caller, String conversationId, String messageId, ErrorCode code);

    /**
     * Asks for an answer in progress to be cancelled (blueprint 6.4), whichever instance writes it. The request is kept
     * with the answer: from then on, the answer takes no more deltas, and message.cancelled is the only ending it can
     * get, so its writer meets {@link Written.CancelRequested} at its next write. Asking again changes nothing. Of a
     * request and an ending, the one that comes first wins: an answer that ended before stays as it ended.
     */
    CancelRequest requestCancel(Owner caller, String conversationId, String messageId);

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

    /**
     * What the model is shown to write an answer: the messages before the answer, oldest first. Every user message counts,
     * and so does an answer that completed or was cancelled with some text, since the end user saw that text. A failed
     * answer, one still in progress and one without text do not count. Of those that count, only the last
     * {@link #MAX_HISTORY} are shown, and they start with a user message.
     *
     * @return the messages, or empty when no answer has this ID in this conversation of the caller's application
     */
    List<Message> historyFor(Owner caller, String conversationId, String answerId);

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
        MESSAGE_STARTED, MESSAGE_DELTA, MESSAGE_COMPLETED, MESSAGE_CANCELLED, ERROR;

        /** The contract's name, such as message.delta. */
        public String wireName() {
            return name().toLowerCase(Locale.ROOT).replace('_', '.');
        }
    }

    /** Why an answer failed after it started (blueprint 6.10). */
    enum ErrorCode {
        /** The model failed partway. */
        MODEL_FAILED,
        /** The writer stopped answering, and a reader ended the answer for it. */
        STREAM_INTERRUPTED;

        /** The contract's code, such as model_failed. */
        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The tokens an answer took. */
    record Usage(long inputTokens, long outputTokens) {

        public Usage {
            if (inputTokens < 0 || outputTokens < 0) {
                throw new IllegalArgumentException("A usage counts tokens, so it is 0 or more");
            }
        }
    }

    /** What the end user asked, and the answer started for it. */
    record Asked(Message question, Message answer) {
    }

    /** What became of an event written to an answer. */
    sealed interface Written {

        /** The answer took the event, as this number. */
        record Added(long seq) implements Written {
        }

        /** The answer had ended already, in this status, so it took no event. */
        record AlreadyEnded(Status status) implements Written {
        }

        /**
         * The answer is still in progress, but someone asked for it to be cancelled, so it took no event: from the request
         * on, it takes message.cancelled alone.
         */
        record CancelRequested() implements Written {
        }

        /** No answer has this ID in this conversation of the caller's application. */
        record NotFound() implements Written {
        }
    }

    /** What became of a request to cancel an answer. */
    sealed interface CancelRequest {

        /** The answer is in progress, and the request is kept with it, made now or before. */
        record Recorded() implements CancelRequest {
        }

        /** The answer had ended already, in this status, so the request changed nothing. */
        record AlreadyEnded(Status status) implements CancelRequest {
        }

        /** No answer has this ID in this conversation of the caller's application. A user message is no answer. */
        record NotFound() implements CancelRequest {
        }
    }

    /**
     * A message. While it is in progress, its completedAt, usage and code are null, and the text of an answer is its
     * deltas so far. An ended answer has usage when it completed or was cancelled, and a code when it failed.
     */
    record Message(String messageId, String conversationId, Role role, Status status, String text, Usage usage,
            ErrorCode code, Instant createdAt, Instant completedAt) {
    }

    /**
     * An event of an answer, with its number. A delta has text, and message.cancelled has what was generated. Usage comes
     * with message.completed and message.cancelled, a code with error.
     */
    record Event(String messageId, long seq, EventType type, String text, Usage usage, ErrorCode code, Instant createdAt) {
    }
}
