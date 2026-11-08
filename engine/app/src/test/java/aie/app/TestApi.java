package aie.app;

import aie.core.tenancy.api.ApiKeyLookup;
import aie.core.tenancy.api.ApiKeyLookup.Owner;
import java.util.Optional;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/** Starts the web layer without a database, with a stand-in for the key lookup that knows one key. */
final class TestApi {

    /** Well formed, so it would pass the format check of a real lookup too. */
    static final String KEY = "aie_test_dev_" + "0123456789".repeat(4);
    static final Owner OWNER = new Owner("key_test", "ten_test", "app_test");
    static final String AUTHORIZATION = "Bearer " + KEY;

    private TestApi() {
    }

    static ConfigurableApplicationContext start(String... args) {
        return start(key -> KEY.equals(key) ? Optional.of(OWNER) : Optional.empty(), args);
    }

    static ConfigurableApplicationContext start(ApiKeyLookup keys, String... args) {
        return ApiRole.serve(context -> context.registerBean(ApiKeyLookup.class, () -> keys), args);
    }

    static int port(ConfigurableApplicationContext context) {
        return ((ServletWebServerApplicationContext) context).getWebServer().getPort();
    }
}
