package aie.http;

import aie.core.tenancy.api.ApiKeyLookup.Owner;
import aie.core.tenancy.api.EndUsers;
import aie.http.api.ConversationsApi;
import aie.http.api.model.Conversation;
import aie.http.api.model.CreateConversationRequest;
import aie.http.api.model.CreateMessageRequest;
import aie.http.api.model.Message;
import aie.http.api.model.MessageList;
import aie.http.api.model.Usage;
import aie.modules.conversation.api.Answers;
import aie.modules.conversation.api.Conversations;
import aie.modules.conversation.api.Messages;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The conversation operations of the contract. The generated interface has no defaults, so every operation has to be
 * here. Starting and reading a conversation work, and so does sending a message without streaming; the other message
 * operations answer 404, as their paths did before this controller, until their points of M2 come.
 */
@RestController
class ConversationController implements ConversationsApi {

    /** The contract's ConversationId: this prefix, then letters and digits, at most 64 characters in all. */
    private static final String CONVERSATION_PREFIX = "conv_";
    private static final int MAX_CONVERSATION_ID = 64;
    private static final Pattern CONVERSATION_ID = Pattern.compile(CONVERSATION_PREFIX + "[0-9A-Za-z]+");
    /** Retry-After of a full instance: an answer takes seconds, so a slot is likely free by then. */
    static final Duration RETRY_AFTER = Duration.ofSeconds(5);

    private final EndUsers endUsers;
    private final Conversations conversations;
    private final Answers answers;
    private final Messages messages;
    private final Duration wait;
    private final HttpServletRequest request;

    /** The wait is the contract's 120 seconds unless a test registers a shorter one. */
    ConversationController(EndUsers endUsers, Conversations conversations, Answers answers, Messages messages,
            ObjectProvider<AnswerWait> wait, HttpServletRequest request) {
        this.endUsers = endUsers;
        this.conversations = conversations;
        this.answers = answers;
        this.messages = messages;
        this.wait = wait.getIfAvailable(() -> AnswerWait.CONTRACT).limit();
        this.request = request;
    }

    /**
     * A conversation for the host's user in the caller's application, with the end user made the first time the ID is
     * seen. For On-Behalf-Of, the caller is already the lower tenant's application. Only server keys come this far so
     * far, and a server key names no user, so external_user_id is required; browser tokens name their own user and come
     * in M2-12. The body is checked before anything is made. Finding the end user and starting the conversation are two
     * transactions, one per module (A-4). An end user left behind by a failed start does no harm, because the same ID
     * finds it again.
     */
    @Override
    public ResponseEntity<Conversation> createConversation(CreateConversationRequest body, @Nullable String idempotencyKey,
            @Nullable String engineVersion, @Nullable String traceparent, @Nullable String onBehalfOf) {
        String externalId = externalId(body.getExternalUserId());
        String locale = SessionRequests.locale(body.getLocale());
        Owner caller = caller();
        Conversations.Conversation started = conversations.start(caller, endUsers.resolve(caller, externalId), locale);
        return ResponseEntity.status(HttpStatus.CREATED).body(answer(started));
    }

    /**
     * A conversation of the caller's application. One of another application or another tenant reads as missing, the
     * same as one that does not exist, so a 404 tells nothing about other callers. For On-Behalf-Of, the caller is
     * already the lower tenant's application.
     */
    @Override
    public ResponseEntity<Conversation> getConversation(String conversationId, @Nullable String engineVersion,
            @Nullable String traceparent, @Nullable String onBehalfOf) {
        Conversations.Conversation found = conversations.find(caller(), conversationId(conversationId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return ResponseEntity.ok(answer(found));
    }

    /**
     * Stores what the end user said, starts the answer, and waits for it to end, for as long as the wait at most. Then
     * the answer comes back as it is: whole once it ended, or in progress with its text so far, and it goes on. A failed
     * answer is a 200 with its code, since the answer was stored before the model was asked.
     *
     * <p>Everything about the request is checked before anything is stored. A full instance answers 429 and stores
     * nothing, so the same Idempotency-Key may come back after Retry-After. Streaming comes in M2-7; until then a caller
     * that takes no JSON gets 406. The locale is checked but not used yet: it is the language to answer in, which the
     * prompts of M3 need. data_class is checked by its enum and waits for the trust layer of M3.
     */
    @Override
    public ResponseEntity<Message> createMessage(String conversationId, CreateMessageRequest body,
            @Nullable String idempotencyKey, @Nullable String engineVersion, @Nullable String traceparent,
            @Nullable String onBehalfOf) {
        takesJson();
        String conversation = conversationId(conversationId);
        String text = messageText(body.getText());
        SessionRequests.locale(body.getLocale());
        Owner caller = caller();
        return switch (answers.ask(caller, conversation, text)) {
            case Answers.Start.Started started -> ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                    .body(answered(caller, conversation, started));
            case Answers.Start.Busy busy -> throw full();
            case Answers.Start.NotFound missing -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        };
    }

    @Override
    public ResponseEntity<Message> getMessage(String conversationId, String messageId, @Nullable String engineVersion,
            @Nullable String traceparent, @Nullable String onBehalfOf) {
        throw notYet();
    }

    @Override
    public ResponseEntity<MessageList> listMessages(String conversationId, Integer limit, @Nullable String cursor,
            @Nullable String engineVersion, @Nullable String traceparent, @Nullable String onBehalfOf) {
        throw notYet();
    }

    @Override
    public ResponseEntity<Message> cancelMessage(String conversationId, String messageId, @Nullable String idempotencyKey,
            @Nullable String engineVersion, @Nullable String traceparent, @Nullable String onBehalfOf) {
        throw notYet();
    }

    /**
     * Until streaming comes, the answer is JSON. Spring turns away an Accept that none of the operation's types meet, but
     * text/event-stream is one of them, so that one is turned away here, before anything is asked. The answer then says
     * application/json itself: left to Spring, an Accept that names the stream first would pick it, and fail to write the
     * answer only after it was generated.
     */
    private void takesJson() {
        List<MediaType> accepted;
        try {
            accepted = MediaType.parseMediaTypes(Collections.list(request.getHeaders(HttpHeaders.ACCEPT)));
        } catch (InvalidMediaTypeException e) {
            throw new ResponseStatusException(HttpStatus.NOT_ACCEPTABLE, "Accept is not a list of media types");
        }
        if (!accepted.isEmpty() && accepted.stream().noneMatch(type -> type.isCompatibleWith(MediaType.APPLICATION_JSON))) {
            throw new ResponseStatusException(HttpStatus.NOT_ACCEPTABLE,
                    "The answer comes as application/json; it does not stream yet");
        }
    }

    /** What the end user said, as the contract's CreateMessageRequest.text allows it and the database can store it. */
    private static String messageText(@Nullable String text) {
        if (text == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "text is required");
        }
        if (text.isEmpty() || text.codePointCount(0, text.length()) > Messages.MAX_TEXT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "text must have 1 to " + Messages.MAX_TEXT + " characters");
        }
        if (text.indexOf('\0') >= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "text cannot hold a NUL character");
        }
        return text;
    }

    /** The instance generates as many answers as it may. Nothing was stored, so the same Idempotency-Key may come back. */
    private static ErrorResponseException full() {
        ErrorResponseException full = new ErrorResponseException(HttpStatus.TOO_MANY_REQUESTS, ProblemDetail.forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS, "This instance answers as many messages as it can, so try again after Retry-After"),
                null);
        full.getHeaders().set(HttpHeaders.RETRY_AFTER, Long.toString(RETRY_AFTER.toSeconds()));
        return full;
    }

    /** Waits for the answer to end, for as long as the wait at most, then reads the answer as it is now. */
    private Message answered(Owner caller, String conversationId, Answers.Start.Started started) {
        try {
            started.ended().toCompletableFuture().get(wait.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException stillGoing) {
            // The answer goes on without this caller, and comes back in progress.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            throw new IllegalStateException("The end of an answer does not fail", e);
        }
        String answerId = started.asked().answer().messageId();
        return message(messages.find(caller, conversationId, answerId)
                .orElseThrow(() -> new IllegalStateException("Answer " + answerId + " was started, yet it cannot be found")));
    }

    private static Message message(Messages.Message message) {
        Message answer = new Message(message.messageId(), message.conversationId(),
                        message.role().name().toLowerCase(Locale.ROOT), message.status().name().toLowerCase(Locale.ROOT),
                        message.text(), message.createdAt().atOffset(ZoneOffset.UTC))
                .code(message.code() == null ? null : message.code().wireName())
                .completedAt(message.completedAt() == null ? null : message.completedAt().atOffset(ZoneOffset.UTC));
        return message.usage() == null ? answer
                : answer.usage(new Usage(message.usage().inputTokens(), message.usage().outputTokens()));
    }

    /** The host's ID, as the contract's ExternalUserId allows it. The contract counts characters, not UTF-16 units. */
    private static String externalId(@Nullable String externalId) {
        if (externalId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "external_user_id is required with a server key");
        }
        if (externalId.isEmpty() || externalId.codePointCount(0, externalId.length()) > SessionRequests.MAX_EXTERNAL_ID) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "external_user_id must have 1 to " + SessionRequests.MAX_EXTERNAL_ID + " characters");
        }
        return externalId;
    }

    /**
     * The conversation ID of the path, as the contract's ConversationId allows it. The whole ID has to match, so a line
     * break at its end does not pass either.
     */
    private static String conversationId(String conversationId) {
        if (!CONVERSATION_ID.matcher(conversationId).matches() || conversationId.length() > MAX_CONVERSATION_ID) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "conversation_id must be " + CONVERSATION_PREFIX
                    + " followed by 1 to " + (MAX_CONVERSATION_ID - CONVERSATION_PREFIX.length()) + " letters or digits");
        }
        return conversationId;
    }

    private static Conversation answer(Conversations.Conversation conversation) {
        return new Conversation(conversation.conversationId(), conversation.endUserId(),
                        conversation.createdAt().atOffset(ZoneOffset.UTC))
                .locale(conversation.locale());
    }

    /** An operation whose point of M2 has not come yet. */
    private static ResponseStatusException notYet() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    /** ApiKeyFilter has put the caller here before any /v1 request reaches a controller. */
    private Owner caller() {
        return Objects.requireNonNull((Owner) request.getAttribute(ApiKeyFilter.CALLER), "No caller on a /v1 request");
    }
}
