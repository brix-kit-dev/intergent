package aie.app;

import aie.adapters.storepg.PgMigrations;
import aie.ports.DatabaseException;
import java.util.Locale;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

/**
 * The migrate role: applies every module's migrations once per deployment, then exits (blueprint 12.5). It always
 * connects as engine_owner, so a wrong setting cannot turn it into a superuser run.
 */
@SpringBootConfiguration(proxyBeanMethods = false)
final class MigrateRole {

    private static final String DATABASE_URL = "engine.database.url";
    private static final String OWNER_PASSWORD = "engine.owner.password";

    private MigrateRole() {
    }

    static int run(String... args) {
        try (ConfigurableApplicationContext context =
                new SpringApplicationBuilder(MigrateRole.class).web(WebApplicationType.NONE).run(args)) {
            Environment settings = context.getEnvironment();
            new PgMigrations(required(settings, DATABASE_URL), required(settings, OWNER_PASSWORD)).run()
                    .forEach((module, applied) -> System.out.println(module + ": " + applied + " applied"));
            return 0;
        } catch (DatabaseException | IllegalArgumentException e) {
            System.err.println("Migration failed: " + e.getMessage());
            return EngineApplication.EXIT_FAILED;
        }
    }

    private static String required(Environment settings, String name) {
        String value = settings.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name.toUpperCase(Locale.ROOT).replace('.', '_') + " is required");
        }
        return value;
    }
}
