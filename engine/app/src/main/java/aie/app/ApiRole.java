package aie.app;

import aie.adapters.storepg.PgSelfCheck;
import aie.ports.DatabaseException;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** The api role: serves requests from the interface layer. */
@SpringBootApplication(scanBasePackages = "aie.http")
class ApiRole {

    private static final String APP_PASSWORD = "engine.app.password";

    static int run(String... args) {
        try {
            start(args);
            return 0;
        } catch (DatabaseException | IllegalArgumentException e) {
            System.err.println("Startup refused: " + e.getMessage());
            return EngineApplication.EXIT_FAILED;
        }
    }

    /** The self-check of blueprint 7.6 comes first, so a role that may not serve traffic never opens a port. */
    static ConfigurableApplicationContext start(String... args) {
        RoleSettings settings = RoleSettings.read(args);
        PgSelfCheck.run(settings.required(RoleSettings.DATABASE_URL), settings.required(APP_PASSWORD));
        return new SpringApplicationBuilder(ApiRole.class).web(WebApplicationType.SERVLET).run(args);
    }
}
