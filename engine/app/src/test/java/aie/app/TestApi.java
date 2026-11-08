package aie.app;

import aie.core.tenancy.api.ApiKeyLookup;
import aie.core.tenancy.api.ApiKeyLookup.Owner;
import aie.core.tenancy.api.ApiKeyManagement;
import aie.core.tenancy.api.IdempotencyKeys;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/** Starts the web layer without a database, with stand-ins for the tenancy module. */
final class TestApi {

    /** Well formed, so it would pass the format check of a real lookup too. */
    static final String KEY = "aie_test_dev_" + "0123456789".repeat(4);
    static final Owner OWNER = new Owner("key_test", "ten_test", "app_test");
    static final String AUTHORIZATION = "Bearer " + KEY;
    /** Knows {@link #KEY} and nothing else. */
    static final ApiKeyLookup LOOKUP = key -> KEY.equals(key) ? Optional.of(OWNER) : Optional.empty();
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
        return ApiRole.serve(context -> {
            context.registerBean(ApiKeyLookup.class, () -> keys);
            context.registerBean(ApiKeyManagement.class, () -> management);
            context.registerBean(IdempotencyKeys.class, () -> NO_REPLAYS);
        }, args);
    }

    static int port(ConfigurableApplicationContext context) {
        return ((ServletWebServerApplicationContext) context).getWebServer().getPort();
    }

    /** Revokes only the keys it was told about, each once, and remembers every call. */
    static final class StandInKeys implements ApiKeyManagement {

        final List<String> calls = new CopyOnWriteArrayList<>();
        private final Set<String> revocable = ConcurrentHashMap.newKeySet();

        void allow(String... keyIds) {
            revocable.addAll(List.of(keyIds));
        }

        @Override
        public boolean revoke(Owner caller, String keyId) {
            calls.add(caller.keyId() + " of " + caller.tenantId() + "/" + caller.applicationId() + " revokes " + keyId);
            return revocable.remove(keyId);
        }
    }
}
