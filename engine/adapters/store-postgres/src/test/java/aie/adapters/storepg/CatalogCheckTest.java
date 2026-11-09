package aie.adapters.storepg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Builds a schema from the templates the way migrate does, then breaks one thing at a time. */
@Testcontainers
class CatalogCheckTest {

    private static final Set<String> SYSTEM_TABLES = Set.of("sample_schedule", "flyway_history_sample");
    private static final Set<String> SYSTEM_FUNCTIONS = Set.of("sample_find");
    private static final String NO_TEMPLATE_POLICY = "T-1 sample_items: has no tenant policy from the template";
    private static final String OTHER_POLICY =
            "T-1 sample_items: has a policy that is neither the template nor a read policy for engine_system";
    private static final String TOO_MANY_GRANTS = "T-1 sample_items: grants more than the template";
    private static final String NO_ROW_SECURITY = "T-1 sample_items: does not enable and force row-level security";
    private static final String UNFIXED_SEARCH_PATH = "T-5 sample_find: does not fix search_path to engine, pg_temp";
    private static final String DEFINER_OF_ANOTHER_ROLE = "T-5 sample_find: is not owned by engine_system";
    private static final String OPEN_TO_PUBLIC = "T-5 sample_find: is open to PUBLIC";
    private static final String NOT_A_DEFINER =
            "T-5 sample_find: is on the system function list but is not a SECURITY DEFINER function";
    private static final String TENANTLESS_KEY =
            "T-1 sample_items: has a foreign key to a tenant table that does not pair tenant_id with tenant_id";
    /** Lets a foreign key name the tenant and the item together, as the real tables do. */
    private static final String TENANT_AND_ITEM = "ALTER TABLE sample_items ADD UNIQUE (tenant_id, item_id), ADD COLUMN parent_id text;";

    @Container
    private static final PostgreSQLContainer DATABASE = TestDatabase.container();

    @BeforeAll
    static void createRoles() throws IOException, InterruptedException {
        assertThat(TestDatabase.createRoles(
                        DATABASE, "owner_password=" + TestDatabase.OWNER_PASSWORD, "app_password=" + TestDatabase.APP_PASSWORD)
                .getExitCode()).isZero();
    }

    @BeforeEach
    void buildTheTemplates() throws SQLException {
        TestDatabase.asAdmin(DATABASE, """
                DROP SCHEMA IF EXISTS engine CASCADE;
                CREATE SCHEMA engine AUTHORIZATION engine_owner;
                GRANT USAGE ON SCHEMA engine TO engine_system, engine_app;
                """);
        new PgMigrations(DATABASE.getJdbcUrl(), TestDatabase.OWNER_PASSWORD, "migrations/templates").run();
    }

    private static List<String> violations() throws SQLException {
        return CatalogCheck.violations(DATABASE, SYSTEM_TABLES, SYSTEM_FUNCTIONS);
    }

    private static void change(String sql) throws SQLException {
        TestDatabase.asAdmin(DATABASE, "SET search_path = engine; " + sql);
    }

    private static Arguments breach(String sql, String... violations) {
        return arguments(sql, List.of(violations));
    }

    static Stream<Arguments> breaches() {
        return Stream.of(
                breach("ALTER TABLE sample_items ALTER COLUMN tenant_id DROP NOT NULL",
                        "T-1 sample_items: has no NOT NULL tenant_id"),
                breach("CREATE TABLE sample_bare (id text); ALTER TABLE sample_bare OWNER TO engine_owner",
                        "T-1 sample_bare: has no NOT NULL tenant_id",
                        "T-1 sample_bare: does not enable and force row-level security",
                        "T-1 sample_bare: has no tenant policy from the template"),
                breach("ALTER TABLE sample_items NO FORCE ROW LEVEL SECURITY", NO_ROW_SECURITY),
                breach("ALTER TABLE sample_items DISABLE ROW LEVEL SECURITY", NO_ROW_SECURITY),
                breach("DROP POLICY tenant_isolation ON sample_items", NO_TEMPLATE_POLICY),
                breach("ALTER POLICY tenant_isolation ON sample_items USING (true)", NO_TEMPLATE_POLICY),
                breach("ALTER POLICY tenant_isolation ON sample_items WITH CHECK (true)", NO_TEMPLATE_POLICY),
                breach("""
                        ALTER POLICY tenant_isolation ON sample_items USING (
                            tenant_id = NULLIF(current_setting('app.tenant_id', true), '') OR item_id = 'open')
                        """, NO_TEMPLATE_POLICY),
                breach("""
                        ALTER POLICY tenant_isolation ON sample_items WITH CHECK (
                            tenant_id = NULLIF(current_setting('app.tenant_id', true), '') OR item_id = 'open')
                        """, NO_TEMPLATE_POLICY),
                breach("ALTER POLICY tenant_isolation ON sample_items USING (tenant_id = current_setting('app.tenant_id', true))",
                        NO_TEMPLATE_POLICY),
                breach("ALTER POLICY tenant_isolation ON sample_items TO engine_app", NO_TEMPLATE_POLICY),
                breach("""
                        DROP POLICY tenant_isolation ON sample_items;
                        CREATE POLICY tenant_isolation ON sample_items FOR SELECT
                            USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''))
                        """, NO_TEMPLATE_POLICY),
                breach("""
                        DROP POLICY tenant_isolation ON sample_items;
                        CREATE POLICY tenant_isolation ON sample_items FOR UPDATE
                            USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''))
                            WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''))
                        """, NO_TEMPLATE_POLICY),
                breach("""
                        DROP POLICY tenant_isolation ON sample_items;
                        CREATE POLICY tenant_isolation ON sample_items AS RESTRICTIVE
                            USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''))
                            WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''))
                        """, NO_TEMPLATE_POLICY),
                breach("ALTER POLICY tenant_isolation ON sample_items RENAME TO renamed", NO_TEMPLATE_POLICY, OTHER_POLICY),
                breach("CREATE POLICY extra ON sample_items USING (true)", OTHER_POLICY),
                breach("CREATE POLICY extra ON sample_items FOR SELECT TO engine_app USING (true)", OTHER_POLICY),
                breach("CREATE POLICY extra ON sample_items FOR UPDATE TO engine_system USING (true)", OTHER_POLICY),
                breach("GRANT TRUNCATE ON sample_items TO engine_app", TOO_MANY_GRANTS),
                breach("GRANT ALL ON sample_items TO engine_app", TOO_MANY_GRANTS),
                breach("GRANT SELECT ON sample_items TO PUBLIC", TOO_MANY_GRANTS),
                breach("CREATE MATERIALIZED VIEW sample_copy AS SELECT * FROM sample_items WITH NO DATA;"
                                + "ALTER MATERIALIZED VIEW sample_copy OWNER TO engine_owner",
                        "T-1 sample_copy: is a materialized view, which row-level security cannot cover"),
                breach("DROP TABLE sample_schedule", "T-1 sample_schedule: is on the system table list but does not exist"),
                breach("ALTER TABLE sample_items OWNER TO engine_app",
                        TOO_MANY_GRANTS, "T-5 sample_items: is not owned by engine_owner"),
                breach("ALTER TABLE sample_schedule OWNER TO engine_app", "T-5 sample_schedule: is not owned by engine_owner"),
                breach("""
                        CREATE FUNCTION sample_other() RETURNS int
                            LANGUAGE sql SECURITY DEFINER SET search_path = engine, pg_temp AS 'SELECT 1';
                        REVOKE ALL ON FUNCTION sample_other() FROM PUBLIC;
                        ALTER FUNCTION sample_other() OWNER TO engine_system
                        """, "T-5 sample_other: is a SECURITY DEFINER function that is not on the list"),
                breach("ALTER FUNCTION sample_find(text) RESET search_path", UNFIXED_SEARCH_PATH),
                breach("ALTER FUNCTION sample_find(text) SET search_path = public", UNFIXED_SEARCH_PATH),
                breach("ALTER FUNCTION sample_find(text) SET search_path = engine", UNFIXED_SEARCH_PATH),
                breach("ALTER FUNCTION sample_find(text) SET search_path = pg_temp, engine", UNFIXED_SEARCH_PATH),
                breach("GRANT EXECUTE ON FUNCTION sample_find(text) TO PUBLIC", OPEN_TO_PUBLIC),
                breach("""
                        DROP FUNCTION sample_find(text);
                        CREATE FUNCTION sample_find(wanted text) RETURNS text
                            LANGUAGE sql STABLE SECURITY DEFINER SET search_path = engine, pg_temp AS 'SELECT wanted';
                        ALTER FUNCTION sample_find(text) OWNER TO engine_system
                        """, OPEN_TO_PUBLIC),
                breach("ALTER FUNCTION sample_find(text) OWNER TO engine_owner", DEFINER_OF_ANOTHER_ROLE),
                breach("ALTER FUNCTION sample_find(text) SECURITY INVOKER", NOT_A_DEFINER),
                breach("DROP FUNCTION sample_find(text)", NOT_A_DEFINER),
                breach("ALTER TABLE sample_items ADD COLUMN parent_id text REFERENCES sample_items (item_id)", TENANTLESS_KEY),
                breach(TENANT_AND_ITEM + "ALTER TABLE sample_items ADD UNIQUE (item_id, tenant_id);"
                        + "ALTER TABLE sample_items ADD FOREIGN KEY (tenant_id, parent_id) REFERENCES sample_items (item_id, tenant_id)",
                        TENANTLESS_KEY),
                breach("GRANT CREATE ON SCHEMA engine TO engine_system",
                        "T-5 engine: lets engine_system create objects, which it needs only during a handover"));
    }

    @Test
    void everythingBuiltFromTheTemplatesPasses() throws SQLException {
        assertThat(violations()).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("breaches")
    void everyBreachIsNamed(String sql, List<String> expected) throws SQLException {
        change(sql);

        assertThat(violations()).containsExactlyElementsOf(expected);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "REVOKE UPDATE, DELETE ON sample_items FROM engine_app",
        "CREATE POLICY other_read ON sample_items FOR SELECT TO engine_system USING (true)",
        "CREATE FUNCTION sample_plain() RETURNS int LANGUAGE sql AS 'SELECT 1'",
        TENANT_AND_ITEM + "ALTER TABLE sample_items ADD FOREIGN KEY (tenant_id, parent_id) REFERENCES sample_items (tenant_id, item_id)",
        "ALTER TABLE sample_schedule ADD UNIQUE (job_id); ALTER TABLE sample_items ADD COLUMN job_id text REFERENCES sample_schedule (job_id)"
    })
    void theseAreAllowed(String sql) throws SQLException {
        change(sql);

        assertThat(violations()).isEmpty();
    }

    /** Without a tenant the app reads nothing, yet the system function still finds the row: the path of blueprint 7.6. */
    @Test
    void aSystemFunctionFindsWhatTheAppCannotRead() throws SQLException {
        change("INSERT INTO sample_items (item_id, tenant_id) VALUES ('item_1', 'ten_a')");

        try (Connection app = TestDatabase.connect(DATABASE, "engine_app", TestDatabase.APP_PASSWORD);
                Statement statement = app.createStatement()) {
            assertThat(firstValue(statement, "SELECT count(*) FROM sample_items")).isEqualTo("0");
            assertThat(firstValue(statement, "SELECT sample_find('item_1')")).isEqualTo("item_1");
        }
    }

    private static String firstValue(Statement statement, String sql) throws SQLException {
        try (ResultSet rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }
}
