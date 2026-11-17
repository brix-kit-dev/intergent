package aie.http;

import aie.core.tenancy.api.ApiKeyLookup.Owner;
import aie.core.tenancy.api.EndUsers;
import aie.core.tenancy.api.SessionTokens;
import aie.http.api.ConversationsApi;
import aie.http.api.StreamsApi;
import aie.http.api.model.Conversation;
import aie.http.api.model.CreateConversationRequest;
import aie.http.api.model.CreateMessageRequest;
import aie.http.api.model.Message;
import aie.http.api.model.MessageList;
import aie.http.api.model.Usage;
import aie.modules.conversation.api.Answers;
import aie.modules.conversation.api.Conversations;
import aie.modules.conversation.api.EventSignals;
import aie.modules.conversation.api.Messages;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

/**
 * The conversation operations of the contract, and the stream of an answer's events. The generated interfaces have no
 * defaults, so every operation has to be here. Starting and reading a conversation work, and so do sending a message,
 * with or without streaming, resuming an answer's stream and cancelling an answer; reading messages answers 404, as its
 * paths did before this controller, until its point of M2 comes. A browser's session token starts and reads its own end
 * user's conversations; ApiKeyFilter lets it through to no other operation yet (M2-12-2).
 */
@RestController
class ConversationController implements ConversationsApi, StreamsApi {

    /** The contract's ConversationId: this prefix, then letters and digits, at most 64 characters in all. */
    private static final String CONVERSATION_PREFIX = "conv_";
    private static final int MAX_CONVERSATION_ID = 64;
    private static final Pattern CONVERSATION_ID = Pattern.compile(CONVERSATION_PREFIX + "[0-9A-Za-z]+");
    /** The contract's MessageId, made the same way. */
    private static final String MESSAGE_PREFIX = "msg_";
    private static final int MAX_MESSAGE_ID = 64;
    private static final Pattern MESSAGE_ID = Pattern.compile(MESSAGE_PREFIX + "[0-9A-Za-z]+");
    /**
     * An id the event stream sends: an event's number, from 1, in decimal without a sign or a leading zero. The database
     * keeps the numbers as an integer, so ten digits hold every one of them.
     */
    private static final Pattern EVENT_ID = Pattern.compile("[1-9][0-9]{0,9}");
    /** Retry-After of a full instance: an answer takes seconds, so a slot is likely free by then. */
    static final Duration RETRY_AFTER = Duration.ofSeconds(5);
    /**
     * How long a cancel waits for the answer to end (M2-9 decision 3). A writer stops at once on this instance, and at its
     * next piece on another, so the ending comes well within it, unless the model has gone quiet.
     */
    static final Duration CANCEL_WAIT = Duration.ofSeconds(5);

    private final EndUsers endUsers;
    private final Conversations conversations;
    private final Answers answers;
    private final Messages messages;
    private final Duration wait;
    private final HttpServletRequest request;
    private final EventStream stream;

    /**
     * The wait is the contract's 120 seconds unless a test registers a shorter one. The stream wakes on the signals the
     * role's messages ring.
     */
    ConversationController(EndUsers endUsers, Conversations conversations, Answers answers, Messages messages,
            EventSignals signals, ObjectProvider<AnswerWait> wait, HttpServletRequest request) {
        this.endUsers = endUsers;
        this.conversations = conversations;
        this.answers = answers;
        this.messages = messages;
        this.wait = wait.getIfAvailable(() -> AnswerWait.CONTRACT).limit();
        this.stream = new EventStream(messages, signals);
        this.request = request;
    }

    /**
     * A conversation for an end user of the caller's application. A server key names no user, so external_user_id is
     * required, and the end user is made the first time the ID is seen. For On-Behalf-Of, the caller is already the lower
     * tenant's application. A browser's session token names its own end user, so it may not send external_user_id, and
     * its language is the conversation's when the body gives none (M2-12-2). The body is checked before anything is
     * made. Finding the end user and starting the conversation are two transactions, one per module (A-4). An end user
     * left behind by a failed start does no harm, because the same ID finds it again.
     */
    @Override
    public ResponseEntity<Conversation> createConversation(CreateConversationRequest body, @Nullable String idempotencyKey,
            @Nullable String engineVersion, @Nullable String traceparent, @Nullable String onBehalfOf) {
        SessionTokens.Session browser = browser();
        if (browser != null && body.getExternalUserId() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A session token names its own end user, so leave external_user_id out");
        }
        String externalId = browser == null ? externalId(body.getExternalUserId()) : null;
        String locale = SessionRequests.locale(body.getLocale());
        Owner caller = caller();
        Conversations.Conversation started = browser == null
                ? conversations.start(caller, endUsers.resolve(caller, externalId), locale)
                : conversations.start(caller, browser.endUserId(), locale == null ? browser.locale() : locale);
        return ResponseEntity.status(HttpStatus.CREATED).body(answer(started));
    }

    /**
     * A conversation of the caller's application. One of another application or another tenant reads as missing, the
     * same as one that does not exist, so a 404 tells nothing about other callers. For On-Behalf-Of, the caller is
     * already the lower tenant's application. A browser reaches only its own end user's conversations, so another end
     * user's reads as missing too (blueprint 6.3).
     */
    @Override
    public ResponseEntity<Conversation> getConversation(String conversationId, @Nullable String engineVersion,
            @Nullable String traceparent, @Nullable String onBehalfOf) {
        Conversations.Conversation found = conversations.find(caller(), conversationId(conversationId))
                .filter(this::reachable)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return ResponseEntity.ok(answer(found));
    }

    /**
     * Stores what the end user said and starts the answer. A caller that names text/event-stream gets the answer as a
     * stream of its events, from the first to the ending. Any other caller waits for it to end, for as long as the wait
     * at most, and gets it as it is then: whole once it ended, or in progress with its text so far, and it goes on. A
     * failed answer is a 200 with its code, or an error event, since the answer was stored before the model was asked.
     *
     * <p>Everything about the request is checked before anything is stored, and a refusal is a plain problem, before any
     * stream starts. A full instance answers 429 and stores nothing, so the same Idempotency-Key may come back after
     * Retry-After. A streamed answer is kept with its Idempotency-Key as its message, and a retry with the key gets the
     * same answer's events again, from the first, without asking anything (M2-10-2). The locale is checked but not used
     * yet: it is the language to answer in, which the prompts of M3 need. data_class is checked by its enum and waits for
     * the trust layer of M3.
     */
    @Override
    public ResponseEntity<Message> createMessage(String conversationId, CreateMessageRequest body,
            @Nullable String idempotencyKey, @Nullable String engineVersion, @Nullable String traceparent,
            @Nullable String onBehalfOf) {
        List<MediaType> accepted = accepted();
        boolean streamed = StreamedRequests.wanted(accepted);
        if (!streamed) {
            takesJson(accepted, "The answer comes as application/json, or as text/event-stream when Accept names it");
        }
        String conversation = conversationId(conversationId);
        String text = messageText(body.getText());
        SessionRequests.locale(body.getLocale());
        Owner caller = caller();
        if (request.getAttribute(IdempotencyFilter.RESEND) instanceof String answer) {
            send(caller, conversation, answer, 0, eventStream(currentResponse()));
            return null;
        }
        return switch (answers.ask(caller, conversation, text)) {
            case Answers.Start.Started started when streamed -> streamed(caller, conversation, started);
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

    /**
     * Stops the generation of an answer (blueprint 6.4), whichever instance writes it, and returns the answer. The request
     * is kept with the answer, so its writer stops: at once on this instance, at its next piece on another. This call then
     * waits for the ending, for as long as the cancel wait at most (M2-9 decision 3), and returns the answer as it is
     * then: cancelled with what was generated and its usage, or still in progress when its writer has not ended it yet,
     * which it will. A writer that has gone ends nothing, so the wait looks at its lease too: once the lease has run out,
     * the answer ends as cancelled at once, with no tokens counted (M2-11-3). An answer that had ended comes back as it
     * ended, unchanged, so the call is safe to repeat.
     *
     * <p>Everything is checked before the request is kept, and a refusal is a plain problem. A user message is no answer,
     * so it is as missing as an unknown message, one of another application and one of another tenant. A retry with the
     * same Idempotency-Key gets the first answer, as for any POST.
     */
    @Override
    public ResponseEntity<Message> cancelMessage(String conversationId, String messageId, @Nullable String idempotencyKey,
            @Nullable String engineVersion, @Nullable String traceparent, @Nullable String onBehalfOf) {
        takesJson(accepted(), "The answer comes as application/json");
        String conversation = conversationId(conversationId);
        String answer = messageId(messageId);
        Owner caller = caller();
        Messages.Message now = switch (answers.cancel(caller, conversation, answer)) {
            case Messages.CancelRequest.NotFound missing -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            case Messages.CancelRequest.AlreadyEnded ended -> found(caller, conversation, answer);
            case Messages.CancelRequest.Recorded recorded -> awaitEnding(caller, conversation, answer);
        };
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(message(now));
    }

    /**
     * Resumes an answer's stream (blueprint 6.4): its events after the one the caller got last, until the ending, as
     * Server-Sent Events. Without Last-Event-ID the stream starts from the first event, so an answer that ended is sent
     * again whole. The log is in the database and its numbers never go back, so every instance sends the same events,
     * with none repeated or missed. An instance other than the writer's finds each new event at its next look, within
     * 200 milliseconds. When the writer has gone, the stream ends the answer for it once its lease has run out (M2-11-3).
     *
     * <p>Everything is checked before the stream starts, and a refusal is a plain problem. A user message has no events,
     * so it is as missing as an unknown message, one of another application and one of another tenant. Last-Event-ID
     * must be an id the stream sent (M2-8 decision 1), and an empty one is none, as the SSE standard's last event ID is
     * empty until the first event came. A caller that already has the ending gets the headers and nothing else (M2-8
     * decision 2), and so does HEAD, which has no body to wait for.
     */
    @Override
    public @Nullable ResponseEntity<String> streamMessageEvents(String conversationId, String messageId,
            @Nullable String lastEventID, @Nullable String engineVersion, @Nullable String traceparent,
            @Nullable String onBehalfOf) {
        takesEvents(accepted());
        String conversation = conversationId(conversationId);
        String answer = messageId(messageId);
        long after = lastEventId(lastEventID);
        Owner caller = caller();
        messages.find(caller, conversation, answer).filter(found -> found.role() == Messages.Role.ASSISTANT)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        boolean hasEnding = after > 0 && hasEnding(caller, conversation, answer, after);
        HttpServletResponse response = eventStream(currentResponse());
        if (!hasEnding && !HttpMethod.HEAD.matches(request.getMethod())) {
            send(caller, conversation, answer, after, response);
        }
        return null;
    }

    private List<MediaType> accepted() {
        try {
            return MediaType.parseMediaTypes(Collections.list(request.getHeaders(HttpHeaders.ACCEPT)));
        } catch (InvalidMediaTypeException e) {
            throw new ResponseStatusException(HttpStatus.NOT_ACCEPTABLE, "Accept is not a list of media types");
        }
    }

    /**
     * Otherwise the answer is JSON. Spring turns away an Accept that none of the operation's types meet, but text/* meets
     * the stream's, so that one is turned away here, before anything is asked. The answer then says application/json
     * itself: left to Spring, an Accept that names the stream first would pick it, and fail to write the answer only
     * after it was generated.
     */
    private static void takesJson(List<MediaType> accepted, String detail) {
        if (!accepted.isEmpty() && accepted.stream().noneMatch(type -> type.isCompatibleWith(MediaType.APPLICATION_JSON))) {
            throw new ResponseStatusException(HttpStatus.NOT_ACCEPTABLE, detail);
        }
    }

    /**
     * The events come as text/event-stream. Spring turns away an Accept that none of the operation's types meet, but
     * application/problem+json alone meets one, so the stream's own type is checked here, and a quality of 0 refuses it.
     */
    private static void takesEvents(List<MediaType> accepted) {
        if (!accepted.isEmpty() && accepted.stream().noneMatch(type -> type.getQualityValue() > 0
                && type.isCompatibleWith(MediaType.TEXT_EVENT_STREAM))) {
            throw new ResponseStatusException(HttpStatus.NOT_ACCEPTABLE, "The events come as text/event-stream");
        }
    }

    /**
     * The answer as Server-Sent Events, from its first event to its ending. With an Idempotency-Key, the key keeps the
     * answer's message before the first event, and the events go out unbuffered (M2-10-2).
     */
    private @Nullable ResponseEntity<Message> streamed(Owner caller, String conversationId, Answers.Start.Started started) {
        String answer = started.asked().answer().messageId();
        HttpServletResponse response = currentResponse();
        if (request.getAttribute(IdempotencyFilter.STREAMING) instanceof IdempotencyFilter.Streaming streaming) {
            response = streaming.start(answer);
        }
        send(caller, conversationId, answer, 0, eventStream(response));
        return null;
    }

    /** The response of this request, as Spring MVC holds it. */
    private static HttpServletResponse currentResponse() {
        return Objects.requireNonNull(((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getResponse(),
                "A request in Spring MVC has its response");
    }

    /**
     * Starts the response as Server-Sent Events. It says that it streams before the first event goes out, and asks
     * proxies not to hold it back.
     */
    private static HttpServletResponse eventStream(HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("text/event-stream;charset=UTF-8");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        return response;
    }

    /**
     * Sends an answer's events after number {@code after}, until its ending, on this request's own thread. A caller that
     * goes away stops the sending, not the answer. A failure from here on cannot become a problem: it leaves through
     * ProblemAdvice to RequestIdFilter, which logs it once and cuts the connection (code point 34).
     */
    private void send(Owner caller, String conversationId, String answerId, long after, HttpServletResponse response) {
        try {
            stream.send(caller, conversationId, answerId, after, response.getOutputStream());
        } catch (IOException gone) {
            // The caller went away before the first event; the answer goes on.
        }
    }

    /**
     * The number of the event the caller got last, or 0 for none. Only an id the stream sent passes, so an id written
     * another way, such as 05 for 5, is refused rather than read as a number.
     */
    private static long lastEventId(@Nullable String lastEventId) {
        if (lastEventId == null || lastEventId.isEmpty()) {
            return 0;
        }
        if (!EVENT_ID.matcher(lastEventId).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Last-Event-ID must be the id of an event the stream sent, a number from 1");
        }
        return Long.parseLong(lastEventId);
    }

    /**
     * Whether the caller has the answer's ending already: whether the event it got last is the ending, after which
     * nothing comes. The numbers never go back, so a log that has not come as far as the id never sent it, and a stream
     * after it would wait for a number that never comes. When content is withdrawn, its deltas go and the ending stays
     * (blueprint 6.4), so an event the caller got may be gone while a later one is still to come.
     */
    private boolean hasEnding(Owner caller, String conversationId, String answerId, long received) {
        List<Messages.Event> next = messages.eventsAfter(caller, conversationId, answerId, received - 1, 1);
        if (next.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Last-Event-ID names no event of this answer");
        }
        Messages.Event event = next.getFirst();
        return event.seq() == received && EventStream.ending(event.type());
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

    /**
     * Waits for a cancelled answer to end, for as long as the cancel wait at most, and returns it as it is then. It looks
     * every 200 milliseconds, as the event stream does, so an ending is seen as soon after as on any instance. Before each
     * look it looks at the writer's lease too: a writer whose lease has run out has gone, and its answer ends as cancelled
     * at once, with no tokens known (M2-11-3).
     */
    private Messages.Message awaitEnding(Owner caller, String conversationId, String answerId) {
        long deadline = System.nanoTime() + CANCEL_WAIT.toNanos();
        while (true) {
            messages.endIfExpired(caller, conversationId, answerId);
            Messages.Message now = found(caller, conversationId, answerId);
            long left = deadline - System.nanoTime();
            if (now.status() != Messages.Status.IN_PROGRESS || left <= 0) {
                return now;
            }
            try {
                Thread.sleep(Duration.ofNanos(Math.min(left, EventStream.POLL.toNanos())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return found(caller, conversationId, answerId);
            }
        }
    }

    /** A message the database has just said is there, as cancelling it found it. */
    private Messages.Message found(Owner caller, String conversationId, String messageId) {
        return messages.find(caller, conversationId, messageId).orElseThrow(() -> new IllegalStateException(
                "Answer " + messageId + " was there for its cancel, yet it cannot be found"));
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

    /** The message ID of the path, as the contract's MessageId allows it. The whole ID has to match here too. */
    private static String messageId(String messageId) {
        if (!MESSAGE_ID.matcher(messageId).matches() || messageId.length() > MAX_MESSAGE_ID) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message_id must be " + MESSAGE_PREFIX
                    + " followed by 1 to " + (MAX_MESSAGE_ID - MESSAGE_PREFIX.length()) + " letters or digits");
        }
        return messageId;
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

    /** The session of a request made with a browser's token, or null for one made with a server key. */
    private SessionTokens.@Nullable Session browser() {
        return request.getAttribute(ApiKeyFilter.BROWSER) instanceof SessionTokens.Session session ? session : null;
    }

    /**
     * Whether the caller may reach the conversation. A server key reaches every one of its application, a browser only
     * those of its own end user. Who a conversation is for never changes, so the answer holds for the whole request.
     */
    private boolean reachable(Conversations.Conversation conversation) {
        SessionTokens.Session browser = browser();
        return browser == null || browser.endUserId().equals(conversation.endUserId());
    }
}
