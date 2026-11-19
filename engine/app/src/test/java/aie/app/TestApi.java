package aie.app;

import aie.core.tenancy.api.ApiKeyLookup;
import aie.core.tenancy.api.ApiKeyLookup.Owner;
import aie.core.tenancy.api.ApiKeyManagement;
import aie.core.tenancy.api.ApiKeyManagement.CreatedKey;
import aie.core.tenancy.api.EndUsers;
import aie.core.tenancy.api.IdempotencyKeys;
import aie.core.tenancy.api.LowerTenants;
import aie.core.tenancy.api.LowerTenants.Acting;
import aie.core.tenancy.api.SessionTokens;
import aie.core.tenancy.api.SiteKeyLookup;
import aie.core.tenancy.internal.SignedSessionTokens;
import aie.http.AnswerWait;
import aie.http.StreamLimit;
import aie.modules.conversation.api.Answers;
import aie.modules.conversation.api.Conversations;
import aie.modules.conversation.api.EventSignals;
import aie.modules.conversation.api.Messages;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/** Starts the web layer without a database, with stand-ins for the tenancy module. */
final class TestApi {

    /** Well formed, so it would pass the format check of a real lookup too. */
    static final String KEY = "aie_test_dev_" + "0123456789".repeat(4);
    static final Owner OWNER = new Owner("key_test", "ten_test", "app_test");
    static final String AUTHORIZATION = "Bearer " + KEY;
    /** 256 bits for tests that start the real api role, so its tokens are test tokens. */
    static final byte[] SESSION_KEY = sessionKey(7);
    static final String SESSION_KEY_SETTING = "--engine.session.key=" + Base64.getEncoder().encodeToString(SESSION_KEY);
    /** The region pack for tests that start the real api role, which needs one (M3-1). */
    static final String REGION_SETTING = "--deploy.region=jp";
    /** Real tokens signed with {@link #SESSION_KEY}, which need no database, so a test can check what the api issued. */
    static final SessionTokens SESSION_TOKENS = new SignedSessionTokens(Clock.systemUTC(), SESSION_KEY, List.of());
    /** Well formed, so it passes the format check of a real lookup too. Public, as every site key is. */
    static final String SITE_KEY = "aie_site_test_dev_" + "0123456789".repeat(4);
    static final Owner SITE_KEY_OWNER = new Owner("site_test", "ten_test", "app_test");
    /** Knows {@link #SITE_KEY} for https://shop.example.com and http://localhost:3000, and no other key. */
    static final SiteKeyLookup SITE_KEYS = (siteKey, origin) -> !SITE_KEY.equals(siteKey)
            ? new SiteKeyLookup.Checked.Unknown()
            : origin != null && Set.of("https://shop.example.com", "http://localhost:3000").contains(origin)
                    ? new SiteKeyLookup.Checked.Allowed(SITE_KEY_OWNER)
                    : new SiteKeyLookup.Checked.OtherOrigin();
    /** Knows {@link #KEY} and nothing else. */
    static final ApiKeyLookup LOOKUP = key -> KEY.equals(key) ? Optional.of(OWNER) : Optional.empty();
    /**
     * ten_lower is a lower tenant with one application of each environment, such as app_lowertest, and ten_empty one
     * without any. The IDs are well formed, so a session token for them checks out.
     */
    static final LowerTenants LOWER_TENANTS = (caller, environment, tenant) -> switch (tenant) {
        case "ten_lower" -> new Acting.As(new Owner(caller.keyId(), "ten_lower", "app_lower" + environment));
        case "ten_empty" -> new Acting.NoSingleApplication();
        default -> new Acting.NotALowerTenant();
    };
    /** Lets every request run and remembers nothing. IdempotencyTest checks the real keys on a database. */
    static final IdempotencyKeys NO_REPLAYS = new IdempotencyKeys() {

        @Override
        public Claim claim(Key key, String fingerprint) {
            return new Claim.Acquired();
        }

        @Override
        public void finish(Key key, int status, String body) {
        }

        @Override
        public void finishStreamed(Key key, String messageId) {
        }

        @Override
        public void release(Key key) {
        }
    };

    private TestApi() {
    }

    static ConfigurableApplicationContext start(String... args) {
        return start(LOOKUP, new StandInKeys(), args);
    }

    static ConfigurableApplicationContext start(ApiKeyLookup keys, String... args) {
        return start(keys, new StandInKeys(), args);
    }

    static ConfigurableApplicationContext start(ApiKeyLookup keys, ApiKeyManagement management, String... args) {
        return start(keys, management, new StandInEndUsers(), args);
    }

    static ConfigurableApplicationContext start(
            ApiKeyLookup keys, ApiKeyManagement management, EndUsers endUsers, String... args) {
        return start(keys, management, endUsers, new StandInConversations(), args);
    }

    static ConfigurableApplicationContext start(ApiKeyLookup keys, ApiKeyManagement management, EndUsers endUsers,
            Conversations conversations, String... args) {
        return start(keys, management, endUsers, conversations, new StandInAnswers(), args);
    }

    /** With the stand-in a test steers its answers through. Messages finds what that stand-in answered. */
    static ConfigurableApplicationContext start(ApiKeyLookup keys, ApiKeyManagement management, EndUsers endUsers,
            Conversations conversations, StandInAnswers answers, String... args) {
        return start(keys, management, endUsers, conversations, answers, NO_REPLAYS, args);
    }

    /** With the Idempotency-Keys a test gives too, such as ones that remember what the filter asked of them. */
    static ConfigurableApplicationContext start(ApiKeyLookup keys, ApiKeyManagement management, EndUsers endUsers,
            Conversations conversations, StandInAnswers answers, IdempotencyKeys idempotency, String... args) {
        return start(keys, management, endUsers, conversations, answers, idempotency, StreamLimit.DEFAULT, args);
    }

    /** With a stream limit too, such as a small one a test can fill (point 40). */
    static ConfigurableApplicationContext start(ApiKeyLookup keys, ApiKeyManagement management, EndUsers endUsers,
            Conversations conversations, StandInAnswers answers, IdempotencyKeys idempotency, StreamLimit streams,
            String... args) {
        return start(keys, management, endUsers, conversations, answers, idempotency, streams, WAIT, args);
    }

    /** With a wait too, such as a long one that an answer must end within even on a busy machine (point 42). */
    static ConfigurableApplicationContext start(ApiKeyLookup keys, ApiKeyManagement management, EndUsers endUsers,
            Conversations conversations, StandInAnswers answers, IdempotencyKeys idempotency, StreamLimit streams,
            AnswerWait wait, String... args) {
        return ApiRole.serve(context -> {
            context.registerBean(StreamLimit.class, () -> streams);
            context.registerBean(ApiKeyLookup.class, () -> keys);
            context.registerBean(ApiKeyManagement.class, () -> management);
            context.registerBean(IdempotencyKeys.class, () -> idempotency);
            context.registerBean(LowerTenants.class, () -> LOWER_TENANTS);
            context.registerBean(EndUsers.class, () -> endUsers);
            context.registerBean(SiteKeyLookup.class, () -> SITE_KEYS);
            context.registerBean(SessionTokens.class, () -> SESSION_TOKENS);
            context.registerBean(Conversations.class, () -> conversations);
            context.registerBean(Answers.class, () -> answers);
            context.registerBean(Messages.class, () -> new StandInMessages(answers));
            context.registerBean(EventSignals.class, () -> EventSignals.NONE);
            context.registerBean(AnswerWait.class, () -> wait);
        }, args);
    }

    /** Short, so that an answer that does not end in time costs a test a moment, not the contract's 120 seconds. */
    static final AnswerWait WAIT = new AnswerWait(Duration.ofMillis(300));

    static int port(ConfigurableApplicationContext context) {
        return ((ServletWebServerApplicationContext) context).getWebServer().getPort();
    }

    /** Creates keys it makes up, revokes only the keys it was told about, each once, and remembers every call. */
    static final class StandInKeys implements ApiKeyManagement {

        static final Instant CREATED_AT = Instant.parse("2026-11-08T00:00:00Z");

        final List<String> calls = new CopyOnWriteArrayList<>();
        private final Set<String> revocable = ConcurrentHashMap.newKeySet();

        void allow(String... keyIds) {
            revocable.addAll(List.of(keyIds));
        }

        @Override
        public CreatedKey create(Owner caller, String name, Instant expiresAt) {
            calls.add(caller.keyId() + " of " + caller.tenantId() + "/" + caller.applicationId() + " creates " + name
                    + (expiresAt == null ? "" : " until " + expiresAt));
            return new CreatedKey("key_created" + calls.size(), "aie_test_dev_" + "5".repeat(40), name, CREATED_AT, expiresAt);
        }

        @Override
        public boolean revoke(Owner caller, String keyId) {
            calls.add(caller.keyId() + " of " + caller.tenantId() + "/" + caller.applicationId() + " revokes " + keyId);
            return revocable.remove(keyId);
        }
    }

    /**
     * Gives each host ID of each application, and each anonymous session, an end user of its own, and remembers every
     * call.
     */
    static final class StandInEndUsers implements EndUsers {

        final List<String> calls = new CopyOnWriteArrayList<>();
        private final Map<String, String> ids = new ConcurrentHashMap<>();
        private final AtomicInteger made = new AtomicInteger();

        @Override
        public String resolve(Owner caller, String externalId) {
            String application = caller.tenantId() + "/" + caller.applicationId();
            calls.add(application + " resolves " + externalId);
            return ids.computeIfAbsent(application + " " + externalId, unused -> "eu_stand" + made.incrementAndGet());
        }

        @Override
        public String anonymous(Owner caller) {
            calls.add(caller.tenantId() + "/" + caller.applicationId() + " makes an anonymous end user");
            return "eu_stand" + made.incrementAndGet();
        }
    }

    /**
     * Starts conversations with IDs it makes up, finds a conversation only for the application that started it, and
     * remembers every call.
     */
    static final class StandInConversations implements Conversations {

        static final Instant STARTED_AT = Instant.parse("2026-11-13T00:00:00Z");

        final List<String> calls = new CopyOnWriteArrayList<>();
        private final Map<String, Conversation> started = new ConcurrentHashMap<>();
        private final AtomicInteger made = new AtomicInteger();

        @Override
        public Conversation start(Owner caller, String endUserId, String locale) {
            String application = caller.tenantId() + "/" + caller.applicationId();
            calls.add(application + " starts for " + endUserId + " in " + locale);
            Conversation conversation = new Conversation("conv_stand" + made.incrementAndGet(), endUserId, locale, STARTED_AT);
            started.put(application + " " + conversation.conversationId(), conversation);
            return conversation;
        }

        @Override
        public Optional<Conversation> find(Owner caller, String conversationId) {
            String application = caller.tenantId() + "/" + caller.applicationId();
            calls.add(application + " finds " + conversationId);
            return Optional.ofNullable(started.get(application + " " + conversationId));
        }
    }

    /**
     * Answers each ask the way a test tells it, and keeps every answer and its events for StandInMessages to find: in
     * progress first, then as it ended. Cancels an answer in progress the way a test tells it too. Remembers every call.
     */
    static final class StandInAnswers implements Answers {

        /** How the asks go from now on. */
        enum Outcome {
            /** "Hello!", at once. */
            COMPLETED,
            /** "Hel", then the model fails, at once. */
            FAILED,
            /** "Hello!", {@link StandInAnswers#soon} after the ask: a tenth of a second unless a test sets it. */
            COMPLETES_SOON,
            /** "Hel" so far, and no end. */
            NEVER_ENDS,
            /** The instance is full. */
            BUSY,
            /** No such conversation. */
            MISSING
        }

        /** How a cancel of an answer in progress goes from now on. Each answer ends at most once. */
        enum Cancelling {
            /** Its writer ends it as cancelled at once, as a writer on this instance does. */
            AT_ONCE,
            /** Its writer ends it as cancelled 300 ms later, as one on another instance does at its next piece. */
            SOON,
            /** The request is kept, but its writer does not end the answer, as one whose model went quiet. */
            NEVER
        }

        static final Instant ASKED_AT = Instant.parse("2026-11-14T00:00:00Z");
        static final Instant ENDED_AT = Instant.parse("2026-11-14T00:00:01Z");

        final List<String> calls = new CopyOnWriteArrayList<>();
        final Map<String, Messages.Message> answers = new ConcurrentHashMap<>();
        /** What was asked, which StandInMessages finds too, so that a test can name a user message. */
        final Map<String, Messages.Message> questions = new ConcurrentHashMap<>();
        final Map<String, List<Messages.Event>> events = new ConcurrentHashMap<>();
        volatile Outcome outcome = Outcome.COMPLETED;
        /** How long after its ask an answer that COMPLETES_SOON ends (point 48). */
        volatile Duration soon = Duration.ofMillis(100);
        volatile Cancelling cancelling = Cancelling.AT_ONCE;
        /** The answers a cancel was asked for, each once. */
        final Set<String> requested = ConcurrentHashMap.newKeySet();
        /** Answers whose writers have gone, as when their instance died, so their leases have run out. */
        final Set<String> gone = ConcurrentHashMap.newKeySet();
        /** The IDs of the questions and answers, in the order they were made, which a page of StandInMessages follows. */
        final List<String> order = new CopyOnWriteArrayList<>();
        /** The conversations that have a history: each one asked in, and any a test adds, empty. */
        final Set<String> conversations = ConcurrentHashMap.newKeySet();
        private final AtomicInteger made = new AtomicInteger();

        @Override
        public Messages.CancelRequest cancel(Owner caller, String conversationId, String messageId) {
            calls.add(caller.tenantId() + "/" + caller.applicationId() + " cancels " + messageId + " in " + conversationId);
            Messages.Message answer = answers.get(messageId);
            if (answer == null || !answer.conversationId().equals(conversationId)) {
                return new Messages.CancelRequest.NotFound();
            }
            if (answer.status() != Messages.Status.IN_PROGRESS) {
                return new Messages.CancelRequest.AlreadyEnded(answer.status());
            }
            if (requested.add(messageId)) {
                switch (cancelling) {
                    case AT_ONCE -> cancelled(answer);
                    case SOON -> CompletableFuture.delayedExecutor(300, TimeUnit.MILLISECONDS).execute(() -> cancelled(answer));
                    case NEVER -> {
                    }
                }
            }
            return new Messages.CancelRequest.Recorded();
        }

        /** message.cancelled into the log, with what the answer had; then the answer as it ended. */
        private void cancelled(Messages.Message answer) {
            List<Messages.Event> log = events.get(answer.messageId());
            Messages.Usage usage = new Messages.Usage(6, 3);
            log.add(event(answer, log.size() + 1, Messages.EventType.MESSAGE_CANCELLED, answer.text(), usage, null));
            answers.put(answer.messageId(), new Messages.Message(answer.messageId(), answer.conversationId(),
                    Messages.Role.ASSISTANT, Messages.Status.CANCELLED, answer.text(), usage, null, ASKED_AT, ENDED_AT));
        }

        /** An error with stream_interrupted into the log, as a reader writes it for a writer that has gone; then the answer. */
        private void interrupted(Messages.Message answer) {
            List<Messages.Event> log = events.get(answer.messageId());
            Messages.ErrorCode code = Messages.ErrorCode.STREAM_INTERRUPTED;
            log.add(event(answer, log.size() + 1, Messages.EventType.ERROR, null, null, code));
            answers.put(answer.messageId(), new Messages.Message(answer.messageId(), answer.conversationId(),
                    Messages.Role.ASSISTANT, Messages.Status.FAILED, answer.text(), null, code, ASKED_AT, ENDED_AT));
        }

        @Override
        public Start ask(Owner caller, String conversationId, String text) {
            calls.add(caller.tenantId() + "/" + caller.applicationId() + " asks in " + conversationId + ": " + text);
            Outcome now = outcome;
            if (now == Outcome.BUSY) {
                return new Start.Busy();
            }
            if (now == Outcome.MISSING) {
                return new Start.NotFound();
            }
            String number = Integer.toString(made.incrementAndGet());
            Messages.Message question = new Messages.Message("msg_q" + number, conversationId, Messages.Role.USER,
                    Messages.Status.COMPLETED, text, null, null, ASKED_AT, ASKED_AT);
            Messages.Message started = new Messages.Message("msg_a" + number, conversationId, Messages.Role.ASSISTANT,
                    Messages.Status.IN_PROGRESS, "", null, null, ASKED_AT, null);
            questions.put(question.messageId(), question);
            answers.put(started.messageId(), started);
            order.add(question.messageId());
            order.add(started.messageId());
            conversations.add(conversationId);
            List<Messages.Event> log = new CopyOnWriteArrayList<>();
            log.add(event(started, 1, Messages.EventType.MESSAGE_STARTED, null, null, null));
            events.put(started.messageId(), log);
            CompletableFuture<Void> ended = new CompletableFuture<>();
            switch (now) {
                case COMPLETED -> end(started, Messages.Status.COMPLETED, "Hello!", new Messages.Usage(6, 2), null, ended);
                case FAILED -> end(started, Messages.Status.FAILED, "Hel", null, Messages.ErrorCode.MODEL_FAILED, ended);
                case COMPLETES_SOON -> CompletableFuture.delayedExecutor(soon.toMillis(), TimeUnit.MILLISECONDS).execute(() ->
                        end(started, Messages.Status.COMPLETED, "Hello!", new Messages.Usage(6, 2), null, ended));
                default -> {
                    answers.put(started.messageId(), new Messages.Message(started.messageId(), conversationId,
                            Messages.Role.ASSISTANT, Messages.Status.IN_PROGRESS, "Hel", null, null, ASKED_AT, null));
                    log.add(event(started, 2, Messages.EventType.MESSAGE_DELTA, "Hel", null, null));
                }
            }
            return new Start.Started(new Messages.Asked(question, started), ended);
        }

        /** The delta, then the ending, into the log; then the answer as it ended. */
        private void end(Messages.Message started, Messages.Status status, String text, Messages.Usage usage,
                Messages.ErrorCode code, CompletableFuture<Void> ended) {
            List<Messages.Event> log = events.get(started.messageId());
            log.add(event(started, 2, Messages.EventType.MESSAGE_DELTA, text, null, null));
            log.add(code == null ? event(started, 3, Messages.EventType.MESSAGE_COMPLETED, null, usage, null)
                    : event(started, 3, Messages.EventType.ERROR, null, null, code));
            answers.put(started.messageId(), new Messages.Message(started.messageId(), started.conversationId(),
                    Messages.Role.ASSISTANT, status, text, usage, code, ASKED_AT, ENDED_AT));
            ended.complete(null);
        }

        private static Messages.Event event(Messages.Message answer, long seq, Messages.EventType type, String text,
                Messages.Usage usage, Messages.ErrorCode code) {
            return new Messages.Event(answer.messageId(), seq, type, text, usage, code, ASKED_AT);
        }
    }

    /**
     * Finds the answers StandInAnswers made, their questions and their events, each only in its own conversation. The
     * web layer needs nothing else yet. A test can make it fail after some reads of the log, as a database that goes
     * away mid-stream, and count the reads, and the looks at a writer's lease.
     */
    static final class StandInMessages implements Messages {

        private final StandInAnswers answers;
        /** Reads of the log that still work; below 0, all of them do. */
        final AtomicInteger readsLeft = new AtomicInteger(-1);
        /** The messages whose writer's lease a reader looked at, once for each look. */
        final List<String> leaseLooks = new CopyOnWriteArrayList<>();
        /** The conversations a page was read of, once for each page. */
        final List<String> pageReads = new CopyOnWriteArrayList<>();
        /** The reads of each message's log so far, kept apart so that one test's stream cannot add to another's. */
        private final Map<String, AtomicInteger> reads = new ConcurrentHashMap<>();

        StandInMessages(StandInAnswers answers) {
            this.answers = answers;
        }

        /** How often the log of this message was read. */
        int reads(String messageId) {
            AtomicInteger count = reads.get(messageId);
            return count == null ? 0 : count.get();
        }

        @Override
        public Optional<Message> find(Owner caller, String conversationId, String messageId) {
            return Optional.ofNullable(answers.answers.getOrDefault(messageId, answers.questions.get(messageId)))
                    .filter(message -> message.conversationId().equals(conversationId));
        }

        @Override
        public Optional<Message> addUserMessage(Owner caller, String conversationId, String text) {
            throw notNeeded();
        }

        @Override
        public Optional<Message> startAssistantMessage(Owner caller, String conversationId) {
            throw notNeeded();
        }

        @Override
        public Optional<Asked> ask(Owner caller, String conversationId, String text) {
            throw notNeeded();
        }

        @Override
        public Written addDelta(Owner caller, String conversationId, String messageId, String text) {
            throw notNeeded();
        }

        @Override
        public Written complete(Owner caller, String conversationId, String messageId, Usage usage) {
            throw notNeeded();
        }

        @Override
        public Written cancel(Owner caller, String conversationId, String messageId, Usage usage) {
            throw notNeeded();
        }

        @Override
        public Written fail(Owner caller, String conversationId, String messageId, ErrorCode code) {
            throw notNeeded();
        }

        @Override
        public CancelRequest requestCancel(Owner caller, String conversationId, String messageId) {
            throw notNeeded();
        }

        @Override
        public Renewal renew(Owner caller, String conversationId, String messageId) {
            throw notNeeded();
        }

        /**
         * A writer stays unless the test says it has gone, and so does its lease: an answer in progress goes on. The answer
         * of a writer that has gone is ended for it, with error and stream_interrupted.
         */
        @Override
        public Expiry endIfExpired(Owner caller, String conversationId, String messageId) {
            leaseLooks.add(messageId);
            Message answer = answers.answers.get(messageId);
            if (answer == null || !answer.conversationId().equals(conversationId)) {
                return new Expiry.NotFound();
            }
            if (answer.status() != Status.IN_PROGRESS) {
                return new Expiry.AlreadyEnded(answer.status());
            }
            if (!answers.gone.contains(messageId)) {
                return new Expiry.Running();
            }
            answers.interrupted(answer);
            return new Expiry.Ended(EventType.ERROR);
        }

        @Override
        public List<Event> eventsAfter(Owner caller, String conversationId, String messageId, long after, int limit) {
            reads.computeIfAbsent(messageId, unused -> new AtomicInteger()).incrementAndGet();
            if (readsLeft.getAndDecrement() == 0) {
                throw new IllegalStateException("The stand-in's database went away");
            }
            Message answer = answers.answers.get(messageId);
            if (answer == null || !answer.conversationId().equals(conversationId)) {
                return List.of();
            }
            return answers.events.get(messageId).stream().filter(event -> event.seq() > after).limit(limit).toList();
        }

        @Override
        public List<Message> list(Owner caller, String conversationId) {
            throw notNeeded();
        }

        /** The conversation's questions and answers in the order StandInAnswers made them, as the database numbers them. */
        @Override
        public Page page(Owner caller, String conversationId, String after, int limit) {
            pageReads.add(conversationId);
            if (!answers.conversations.contains(conversationId)) {
                return new Page.NotFound();
            }
            List<Message> history = answers.order.stream()
                    .map(id -> find(caller, conversationId, id)).flatMap(Optional::stream).toList();
            int from = 0;
            if (after != null) {
                List<String> ids = history.stream().map(Message::messageId).toList();
                if (!ids.contains(after)) {
                    return new Page.NoSuchPosition();
                }
                from = ids.indexOf(after) + 1;
            }
            List<Message> rest = history.subList(from, history.size());
            return rest.size() > limit ? new Page.Found(rest.subList(0, limit), true) : new Page.Found(rest, false);
        }

        @Override
        public List<Message> historyFor(Owner caller, String conversationId, String answerId) {
            throw notNeeded();
        }

        private static UnsupportedOperationException notNeeded() {
            return new UnsupportedOperationException("The web layer does not use this yet");
        }
    }

    /** A key of the given length, filled with one byte, so each test can tell its keys apart. */
    static byte[] sessionKey(int seed, int length) {
        byte[] key = new byte[length];
        Arrays.fill(key, (byte) seed);
        return key;
    }

    static byte[] sessionKey(int seed) {
        return sessionKey(seed, 32);
    }

    /** A valid token for an end user of {@link #OWNER}'s application, as the api issues it. */
    static String sessionToken(String endUserId) {
        return SESSION_TOKENS.issue(session(endUserId)).token();
    }

    /** The same, for an end user whose host gave this language, in standard form. */
    static String sessionToken(String endUserId, String locale) {
        return SESSION_TOKENS.issue(new SessionTokens.Session(OWNER.tenantId(), OWNER.applicationId(), endUserId,
                SessionTokens.IdentityLevel.VERIFIED, List.of(), Map.of(), locale)).token();
    }

    /** A token the api signed for {@link #OWNER}'s application that ran out a minute ago. */
    static String expiredSessionToken() {
        Clock issuedThen = Clock.fixed(Instant.now().minus(SessionTokens.LIFETIME).minusSeconds(60), ZoneOffset.UTC);
        return new SignedSessionTokens(issuedThen, SESSION_KEY, List.of()).issue(session("eu_1")).token();
    }

    /** A token for {@link #OWNER}'s application, signed with a key this api does not know. */
    static String foreignSessionToken() {
        return new SignedSessionTokens(Clock.systemUTC(), sessionKey(8), List.of()).issue(session("eu_1")).token();
    }

    private static SessionTokens.Session session(String endUserId) {
        return new SessionTokens.Session(OWNER.tenantId(), OWNER.applicationId(), endUserId,
                SessionTokens.IdentityLevel.VERIFIED, List.of(), Map.of(), null);
    }
}
