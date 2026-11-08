package aie.app;

import aie.adapters.storepg.PgPool;
import aie.adapters.storepg.PgRuntime;
import aie.ports.DatabaseException;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;

/** The api role: serves requests from the interface layer. */
@SpringBootApplication(scanBasePackages = "aie.http")
class ApiRole {

    /** Requests run on virtual threads, so the pool, not the thread count, caps the database connections. */
    static final int POOL_SIZE = 10;

    static int run(String... args) {
        try {
            start(args);
            return 0;
        } catch (DatabaseException | IllegalArgumentException e) {
            System.err.println("Startup refused: " + e.getMessage());
            return EngineApplication.EXIT_FAILED;
        }
    }

    /**
     * The pool runs the self-check of blueprint 7.6 before its first connection, so a role that may not serve traffic
     * never opens a port. The context closes the pool as it closes, because the pool is AutoCloseable. A context that
     * fails before it has made its beans would not, so the role closes the pool itself then.
     */
    static ConfigurableApplicationContext start(String... args) {
        RoleSettings settings = RoleSettings.read(args);
        PgPool database = PgRuntime.pool(
                settings.required(RoleSettings.DATABASE_URL), settings.required(RoleSettings.APP_PASSWORD), POOL_SIZE);
        try {
            return new SpringApplicationBuilder(ApiRole.class)
                    .web(WebApplicationType.SERVLET)
                    .initializers(context -> ((GenericApplicationContext) context).registerBean(PgPool.class, () -> database))
                    .run(args);
        } catch (RuntimeException e) {
            database.close();
            throw e;
        }
    }
}
