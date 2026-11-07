package aie.adapters.storepg;

import aie.ports.DatabaseException;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Runs the migrations of blueprint 5.6. A module keeps its scripts in its own directory and its own history table,
 * so a module that leaves takes both with it, and two modules can use the same version numbers.
 */
public final class PgMigrations {

    private static final String DEFAULT_ROOT = "db/migration";
    private static final String OWNER = "engine_owner";
    private static final String SCHEMA = "engine";
    private static final String HISTORY_PREFIX = "flyway_history_";

    /**
     * With row_security off, a data migration that forgets to lift FORCE fails instead of updating 0 rows (blueprint
     * 7.6). The lock timeout makes a blocked ALTER TABLE give up, because every request queues behind it while it waits.
     */
    private static final String SESSION_OPTIONS = "-c row_security=off -c lock_timeout=10s";

    /**
     * Flyway locks through a module's history table, which does not exist on the module's first run. Two runs at once
     * would race to create it, so a whole run holds this lock. It goes away with the connection.
     */
    static final long RUN_LOCK = 20261105L;

    private final PGSimpleDataSource dataSource;
    private final String root;

    public PgMigrations(String url, String ownerPassword) {
        this(url, ownerPassword, DEFAULT_ROOT);
    }

    PgMigrations(String url, String ownerPassword, String root) {
        this.root = root;
        this.dataSource = PgConnections.as(OWNER, url, ownerPassword);
        dataSource.setOptions(SESSION_OPTIONS);
    }

    /** Migrates every module and returns how many scripts each one applied. */
    public Map<String, Integer> run() {
        SortedSet<String> modules = modules();
        if (modules.isEmpty()) {
            throw new DatabaseException("No migrations found under " + root, null);
        }
        try (Connection connection = dataSource.getConnection(); Statement lock = connection.createStatement()) {
            lock.execute("SELECT pg_advisory_lock(" + RUN_LOCK + ")");
            Map<String, Integer> applied = new LinkedHashMap<>();
            for (String module : modules) {
                applied.put(module, migrate(module));
            }
            return applied;
        } catch (SQLException e) {
            throw new DatabaseException("The migrations could not start: " + e.getMessage(), e);
        }
    }

    private int migrate(String module) {
        try {
            return flyway(module).migrate().migrationsExecuted;
        } catch (FlywayException e) {
            throw new DatabaseException("Migrating " + module + " failed: " + e.getMessage(), e);
        }
    }

    /** Each directory under the root is a module, so a new module cannot be left out of a list. */
    private SortedSet<String> modules() {
        SortedSet<String> modules = new TreeSet<>();
        try {
            for (Resource script : new PathMatchingResourcePatternResolver().getResources("classpath*:" + root + "/*/*.sql")) {
                String[] path = script.getURL().getPath().split("/");
                modules.add(path[path.length - 2]);
            }
        } catch (IOException e) {
            throw new DatabaseException("Cannot read the migrations under " + root, e);
        }
        return modules;
    }

    /** The baseline is what lets a module start its history in a schema that already holds other modules' tables. */
    private Flyway flyway(String module) {
        return Flyway.configure()
                .dataSource(dataSource)
                .schemas(SCHEMA)
                .createSchemas(false)
                .table(HISTORY_PREFIX + module)
                .locations("classpath:" + root + "/" + module)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .validateMigrationNaming(true)
                .load();
    }
}
