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
import aie.modules.conversation.api.Answers;
import aie.modules.conversation.api.Conversations;
import aie.modules.conversation.api.Messages;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
        return ApiRole.serve(context -> {
            context.registerBean(ApiKeyLookup.class, () -> keys);
            context.registerBean(ApiKeyManagement.class, () -> management);
            context.registerBean(IdempotencyKeys.class, () -> NO_REPLAYS);
            context.registerBean(LowerTenants.class, () -> LOWER_TENANTS);
            context.registerBean(EndUsers.class, () -> endUsers);
            context.registerBean(SiteKeyLookup.class, () -> SITE_KEYS);
            context.registerBean(SessionTokens.class, () -> SESSION_TOKENS);
            context.registerBean(Conversations.class, () -> conversations);
            context.registerBean(Answers.class, () -> answers);
            context.registerBean(Messages.class, () -> new StandInMessages(answers));
            context.registerBean(AnswerWait.class, () -> WAIT);
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
     * progress first, then as it ended. Remembers every call.
     */
    static final class StandInAnswers implements Answers {

        /** How the asks go from now on. */
        enum Outcome {
            /** "Hello!", at once. */
            COMPLETED,
            /** "Hel", then the model fails, at once. */
            FAILED,
            /** "Hello!", a tenth of a second after the ask. */
            COMPLETES_SOON,
            /** "Hel" so far, and no end. */
            NEVER_ENDS,
            /** The instance is full. */
            BUSY,
            /** No such conversation. */
            MISSING
        }

        static final Instant ASKED_AT = Instant.parse("2026-11-14T00:00:00Z");
        static final Instant ENDED_AT = Instant.parse("2026-11-14T00:00:01Z");

        final List<String> calls = new CopyOnWriteArrayList<>();
        final Map<String, Messages.Message> answers = new ConcurrentHashMap<>();
        final Map<String, List<Messages.Event>> events = new ConcurrentHashMap<>();
        volatile Outcome outcome = Outcome.COMPLETED;
        private final AtomicInteger made = new AtomicInteger();

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
            answers.put(started.messageId(), started);
            List<Messages.Event> log = new CopyOnWriteArrayList<>();
            log.add(event(started, 1, Messages.EventType.MESSAGE_STARTED, null, null, null));
            events.put(started.messageId(), log);
            CompletableFuture<Void> ended = new CompletableFuture<>();
            switch (now) {
                case COMPLETED -> end(started, Messages.Status.COMPLETED, "Hello!", new Messages.Usage(6, 2), null, ended);
                case FAILED -> end(started, Messages.Status.FAILED, "Hel", null, Messages.ErrorCode.MODEL_FAILED, ended);
                case COMPLETES_SOON -> CompletableFuture.delayedExecutor(100, TimeUnit.MILLISECONDS).execute(() ->
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
     * Finds the answers StandInAnswers made, and their events, each only in its own conversation. The web layer needs
     * nothing else yet. A test can make it fail after some reads of the log, as a database that goes away mid-stream.
     */
    static final class StandInMessages implements Messages {

        private final StandInAnswers answers;
        /** Reads of the log that still work; below 0, all of them do. */
        final AtomicInteger readsLeft = new AtomicInteger(-1);

        StandInMessages(StandInAnswers answers) {
            this.answers = answers;
        }

        @Override
        public Optional<Message> find(Owner caller, String conversationId, String messageId) {
            return Optional.ofNullable(answers.answers.get(messageId))
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
        public List<Event> eventsAfter(Owner caller, String conversationId, String messageId, long after, int limit) {
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
}
