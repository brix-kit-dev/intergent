package aie.app;

import java.util.Locale;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

/** A role's settings, read the way Spring Boot reads them, before the role starts anything. */
final class RoleSettings {

    static final String DATABASE_URL = "engine.database.url";
    static final String APP_PASSWORD = "engine.app.password";

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
        String value = environment.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name.toUpperCase(Locale.ROOT).replace('.', '_') + " is required");
        }
        return value;
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    static final class Empty {
    }
}
