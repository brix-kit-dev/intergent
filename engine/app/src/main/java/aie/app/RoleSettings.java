package aie.app;

import java.util.Locale;
import java.util.Optional;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

/** A role's settings, read the way Spring Boot reads them, before the role starts anything. */
final class RoleSettings {

    static final String DATABASE_URL = "engine.database.url";
    static final String APP_PASSWORD = "engine.app.password";
    /** Signs every new session token. */
    static final String SESSION_KEY = "engine.session.key";
    /** Only checks session tokens. It keeps the tokens already out working while the key changes. */
    static final String SESSION_PREVIOUS_KEY = "engine.session.previous.key";

    private final Environment environment;

    private RoleSettings(Environment environment) {
        this.environment = environment;
    }

    static RoleSettings read(String... args) {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(Empty.class)
                .web(WebApplicationType.NONE)
                .logStartupInfo(false)
                .run(args)) {
            return new RoleSettings(context.getEnvironment());
        }
    }

    /** Fails with the name of the environment variable, because that is where an operator sets it. */
    String required(String name) {
        return optional(name).orElseThrow(() -> new IllegalArgumentException(variable(name) + " is required"));
    }

    /** The setting, or empty when it is missing or blank. */
    Optional<String> optional(String name) {
        String value = environment.getProperty(name);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    /** The environment variable an operator sets for a setting, such as ENGINE_SESSION_KEY for engine.session.key. */
    static String variable(String name) {
        return name.toUpperCase(Locale.ROOT).replace('.', '_');
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    static final class Empty {
    }
}
