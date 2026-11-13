package aie.http;

import aie.core.tenancy.api.ApiKeyLookup.Owner;
import aie.core.tenancy.api.EndUsers;
import aie.http.api.ConversationsApi;
import aie.http.api.model.Conversation;
import aie.http.api.model.CreateConversationRequest;
import aie.http.api.model.CreateMessageRequest;
import aie.http.api.model.Message;
import aie.http.api.model.MessageList;
import aie.modules.conversation.api.Conversations;
import jakarta.servlet.http.HttpServletRequest;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The conversation operations of the contract. The generated interface has no defaults, so every operation has to be
 * here. Starting and reading a conversation work; the message operations answer 404, as their paths did before this
 * controller, until their points of M2 come.
 */
@RestController
class ConversationController implements ConversationsApi {

    /** The contract's ConversationId: this prefix, then letters and digits, at most 64 characters in all. */
    private static final String CONVERSATION_PREFIX = "conv_";
    private static final int MAX_CONVERSATION_ID = 64;
    private static final Pattern CONVERSATION_ID = Pattern.compile(CONVERSATION_PREFIX + "[0-9A-Za-z]+");

    private final EndUsers endUsers;
    private final Conversations conversations;
    private final HttpServletRequest request;

    ConversationController(EndUsers endUsers, Conversations conversations, HttpServletRequest request) {
        this.endUsers = endUsers;
        this.conversations = conversations;
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

    @Override
    public ResponseEntity<Message> createMessage(String conversationId, CreateMessageRequest body,
            @Nullable String idempotencyKey, @Nullable String engineVersion, @Nullable String traceparent,
            @Nullable String onBehalfOf) {
        throw notYet();
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
