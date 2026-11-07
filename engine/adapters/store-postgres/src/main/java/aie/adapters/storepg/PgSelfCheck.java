package aie.adapters.storepg;

import aie.ports.DatabaseException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * The startup self-check of blueprint 7.6. Row-level security does nothing for a superuser or a role with BYPASSRLS, a
 * table's owner can lift it, and engine_system reads across tenants. Any of these would work and leak quietly, so the
 * runtime role must be none of them.
 */
public final class PgSelfCheck {

    private static final int TIMEOUT_SECONDS = 10;

    private static final String ROLE_FACTS = """
            SELECT r.rolsuper,
                   r.rolbypassrls,
                   EXISTS (
                       SELECT FROM pg_class c
                       JOIN pg_namespace n ON n.oid = c.relnamespace
                       WHERE c.relowner = r.oid
                           AND c.relkind IN ('r', 'p')
                           AND n.nspname NOT IN ('pg_catalog', 'information_schema')) AS owns_tables,
                   pg_has_role(r.oid, 'engine_system', 'MEMBER') AS in_engine_system
            FROM pg_roles r
            WHERE r.rolname = current_user
            """;

    private PgSelfCheck() {
    }

    /** Returns if the runtime role may serve traffic, and otherwise throws a {@link DatabaseException} that says why. */
    public static void run(String url, String appPassword) {
        run(url, appPassword, TIMEOUT_SECONDS);
    }

    static void run(String url, String appPassword, int timeoutSeconds) {
        PGSimpleDataSource dataSource = PgConnections.as(PgConnections.RUNTIME_ROLE, url, appPassword);
        dataSource.setSocketTimeout(timeoutSeconds);
        List<String> problems;
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet facts = statement.executeQuery(ROLE_FACTS)) {
            facts.next();
            problems = problems(facts);
        } catch (SQLException e) {
            throw new DatabaseException("The database role could not be checked: " + e.getMessage(), e);
        }
        if (!problems.isEmpty()) {
            throw new DatabaseException(PgConnections.RUNTIME_ROLE + " may not serve traffic: it " + String.join(", ", problems), null);
        }
    }

    private static List<String> problems(ResultSet facts) throws SQLException {
        List<String> problems = new ArrayList<>();
        if (facts.getBoolean("rolsuper")) {
            problems.add("is a superuser");
        }
        if (facts.getBoolean("rolbypassrls")) {
            problems.add("has BYPASSRLS");
        }
        if (facts.getBoolean("owns_tables")) {
            problems.add("owns tables");
        }
        if (facts.getBoolean("in_engine_system")) {
            problems.add("belongs to engine_system");
        }
        return problems;
    }
}
