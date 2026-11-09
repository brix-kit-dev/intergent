package aie.adapters.storepg;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The migration check of red lines T-1 and T-5. It reads the catalog once every script has run, so it judges what the
 * scripts add up to, and a later script cannot undo an earlier one unnoticed.
 *
 * <p>It compares the policy text too. A test with sample rows sees only the values in those rows, so it cannot notice a
 * policy that lets some other value through.
 *
 * <p>A foreign key between tenant tables must pair tenant_id with tenant_id. PostgreSQL checks a foreign key without
 * row-level security, so a key on the parent's ID alone would let a row point at another tenant's parent.
 */
public final class CatalogCheck {

    /** Blueprint 7.2, as PostgreSQL prints it. */
    private static final String TENANT_CONDITION = "$$(tenant_id = NULLIF(current_setting('app.tenant_id'::text, true), ''::text))$$";

    private static final String OBJECTS = """
            WITH tables AS (
                SELECT c.*
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'engine' AND c.relkind IN ('r', 'p')
            ),
            tenant_tables AS (
                SELECT * FROM tables WHERE relname::text <> ALL ({system_tables})
            ),
            definers AS (
                SELECT p.*
                FROM pg_proc p
                JOIN pg_namespace n ON n.oid = p.pronamespace
                WHERE n.nspname = 'engine' AND p.prosecdef
            )
            """;

    private static final List<Rule> RULES = List.of(
            new Rule("T-1", "has no NOT NULL tenant_id", """
                    SELECT relname FROM tenant_tables t
                    WHERE NOT EXISTS (
                        SELECT FROM pg_attribute a WHERE a.attrelid = t.oid AND a.attname = 'tenant_id' AND a.attnotnull)
                    """),
            new Rule("T-1", "does not enable and force row-level security", """
                    SELECT relname FROM tenant_tables WHERE NOT (relrowsecurity AND relforcerowsecurity)
                    """),
            new Rule("T-1", "has no tenant policy from the template", """
                    SELECT relname FROM tenant_tables t
                    WHERE NOT EXISTS (
                        SELECT FROM pg_policy p
                        WHERE p.polrelid = t.oid
                            AND p.polname = 'tenant_isolation' AND p.polpermissive AND p.polcmd = '*' AND p.polroles = '{0}'
                            AND pg_get_expr(p.polqual, p.polrelid) = %1$s
                            AND pg_get_expr(p.polwithcheck, p.polrelid) = %1$s)
                    """.formatted(TENANT_CONDITION)),
            new Rule("T-1", "has a policy that is neither the template nor a read policy for engine_system", """
                    SELECT DISTINCT t.relname
                    FROM tenant_tables t
                    JOIN pg_policy p ON p.polrelid = t.oid
                    WHERE p.polname <> 'tenant_isolation'
                        AND NOT (p.polcmd = 'r' AND p.polroles = ARRAY['engine_system'::regrole::oid])
                    """),
            new Rule("T-1", "grants more than the template", """
                    SELECT DISTINCT t.relname
                    FROM tenant_tables t, aclexplode(t.relacl) g
                    WHERE g.grantee = 0
                        OR (g.grantee = 'engine_app'::regrole AND g.privilege_type NOT IN ('SELECT', 'INSERT', 'UPDATE', 'DELETE'))
                    """),
            new Rule("T-1", "has a foreign key to a tenant table that does not pair tenant_id with tenant_id", """
                    SELECT DISTINCT t.relname
                    FROM tenant_tables t
                    JOIN pg_constraint c ON c.conrelid = t.oid AND c.contype = 'f'
                    JOIN tenant_tables r ON r.oid = c.confrelid
                    WHERE NOT EXISTS (
                        SELECT FROM unnest(c.conkey, c.confkey) AS k (own, theirs)
                        JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.own
                        JOIN pg_attribute b ON b.attrelid = c.confrelid AND b.attnum = k.theirs
                        WHERE a.attname = 'tenant_id' AND b.attname = 'tenant_id')
                    """),
            new Rule("T-1", "is a materialized view, which row-level security cannot cover", """
                    SELECT c.relname
                    FROM pg_class c
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = 'engine' AND c.relkind = 'm'
                    """),
            new Rule("T-1", "is on the system table list but does not exist", """
                    SELECT name FROM unnest({system_tables}) AS listed (name)
                    WHERE name NOT IN (SELECT relname::text FROM tables)
                    """),
            new Rule("T-5", "is not owned by engine_owner", """
                    SELECT relname FROM tables WHERE relowner <> 'engine_owner'::regrole
                    """),
            new Rule("T-5", "is a SECURITY DEFINER function that is not on the list", """
                    SELECT proname FROM definers WHERE proname::text <> ALL ({system_functions})
                    """),
            new Rule("T-5", "does not fix search_path to engine, pg_temp", """
                    SELECT proname FROM definers
                    WHERE proconfig IS NULL OR NOT 'search_path=engine, pg_temp' = ANY (proconfig)
                    """),
            new Rule("T-5", "is open to PUBLIC", """
                    SELECT proname FROM definers d
                    WHERE EXISTS (
                        SELECT FROM aclexplode(COALESCE(d.proacl, acldefault('f', d.proowner))) g WHERE g.grantee = 0)
                    """),
            new Rule("T-5", "is not owned by engine_system", """
                    SELECT proname FROM definers WHERE proowner <> 'engine_system'::regrole
                    """),
            new Rule("T-5", "is on the system function list but is not a SECURITY DEFINER function", """
                    SELECT name FROM unnest({system_functions}) AS listed (name)
                    WHERE name NOT IN (SELECT proname::text FROM definers)
                    """),
            new Rule("T-5", "lets engine_system create objects, which it needs only during a handover", """
                    SELECT 'engine' WHERE has_schema_privilege('engine_system', 'engine', 'CREATE')
                    """));

    private CatalogCheck() {
    }

    private record Rule(String redLine, String problem, String names) {
    }

    /** Lists every breach, as the red line, the object and the problem. An empty list means the catalog passes. */
    public static List<String> violations(PostgreSQLContainer database, Set<String> systemTables, Set<String> systemFunctions)
            throws SQLException {
        List<String> found = new ArrayList<>();
        try (Connection admin = TestDatabase.admin(database); Statement statement = admin.createStatement()) {
            for (Rule rule : RULES) {
                String sql = (OBJECTS + rule.names() + " ORDER BY 1")
                        .replace("{system_tables}", array(systemTables))
                        .replace("{system_functions}", array(systemFunctions));
                try (ResultSet names = statement.executeQuery(sql)) {
                    while (names.next()) {
                        found.add(rule.redLine() + " " + names.getString(1) + ": " + rule.problem());
                    }
                }
            }
        }
        return found;
    }

    private static String array(Set<String> names) {
        return names.stream().map(name -> "'" + name + "'").collect(Collectors.joining(", ", "ARRAY[", "]::text[]"));
    }
}
