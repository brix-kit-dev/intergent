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
import aie.core.tenancy.internal.SignedSessionTokens;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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
        return ApiRole.serve(context -> {
            context.registerBean(ApiKeyLookup.class, () -> keys);
            context.registerBean(ApiKeyManagement.class, () -> management);
            context.registerBean(IdempotencyKeys.class, () -> NO_REPLAYS);
            context.registerBean(LowerTenants.class, () -> LOWER_TENANTS);
            context.registerBean(EndUsers.class, () -> endUsers);
            context.registerBean(SessionTokens.class, () -> SESSION_TOKENS);
        }, args);
    }

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
