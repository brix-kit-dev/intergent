package aie.app;

import aie.adapters.storepg.PgMigrations;
import aie.ports.DatabaseException;

/**
 * The migrate role: applies every module's migrations once per deployment, then exits (blueprint 12.5). It always
 * connects as engine_owner, so a wrong setting cannot turn it into a superuser run.
 */
final class MigrateRole {

    private static final String OWNER_PASSWORD = "engine.owner.password";

    private MigrateRole() {
    }

    static int run(String... args) {
        try {
            RoleSettings settings = RoleSettings.read(args);
            new PgMigrations(settings.required(RoleSettings.DATABASE_URL), settings.required(OWNER_PASSWORD)).run()
                    .forEach((module, applied) -> System.out.println(module + ": " + applied + " applied"));
            return 0;
        } catch (DatabaseException | IllegalArgumentException e) {
            System.err.println("Migration failed: " + e.getMessage());
            return EngineApplication.EXIT_FAILED;
        }
    }
}
