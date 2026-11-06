package aie.adapters.storepg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** The roles of blueprint 7.6, checked on a real database created by the real script. */
@Testcontainers
class DatabaseRolesTest {

    @Container
    private static final PostgreSQLContainer DATABASE = TestDatabase.container();

    @BeforeAll
    static void createRoles() throws IOException, InterruptedException {
        ExecResult result = TestDatabase.createRoles(
                DATABASE, "owner_password=" + TestDatabase.OWNER_PASSWORD, "app_password=" + TestDatabase.APP_PASSWORD);
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
    }

    private static Connection owner() throws SQLException {
        return TestDatabase.connect(DATABASE, "engine_owner", TestDatabase.OWNER_PASSWORD);
    }

    private static Connection app() throws SQLException {
        return TestDatabase.connect(DATABASE, "engine_app", TestDatabase.APP_PASSWORD);
    }

    private static boolean flag(String sql) throws SQLException {
        try (Connection connection = TestDatabase.admin(DATABASE);
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).as(sql).isTrue();
            return result.getBoolean(1);
        }
    }

    private static void run(Connection connection, String sql) throws SQLException {
        try (connection; Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    @Test
    void theTestDatabaseListensOnLocalhostOnly() {
        Map<ExposedPort, Ports.Binding[]> bindings = DATABASE.getContainerInfo().getNetworkSettings().getPorts().getBindings();

        assertThat(bindings.values()).isNotEmpty().allSatisfy(ports -> assertThat(ports)
                .isNotEmpty()
                .allSatisfy(port -> assertThat(port.getHostIp()).isEqualTo(TestDatabase.LOCALHOST)));
    }

    /** The connection opens outside the assertion, so a role that cannot log in fails instead of passing. */
    private static void assertRefused(Connection connection, String sql) {
        assertThatThrownBy(() -> run(connection, sql)).hasMessageContaining("permission denied");
    }

    @ParameterizedTest
    @ValueSource(strings = {"engine_owner", "engine_app", "engine_system"})
    void noRoleHoldsARightThatBeatsRowLevelSecurity(String role) throws SQLException {
        assertThat(flag("""
                SELECT rolsuper OR rolbypassrls OR rolcreaterole OR rolcreatedb OR rolreplication
                FROM pg_roles WHERE rolname = '%s'
                """.formatted(role))).isFalse();
    }

    @Test
    void engineSystemCannotLogIn() throws SQLException {
        assertThat(flag("SELECT rolcanlogin FROM pg_roles WHERE rolname = 'engine_system'")).isFalse();
    }

    @Test
    void engineOwnerDoesNotInherit() throws SQLException {
        assertThat(flag("SELECT rolinherit FROM pg_roles WHERE rolname = 'engine_owner'")).isFalse();
    }

    @Test
    void engineOwnerCanHandFunctionsToEngineSystem() throws SQLException {
        assertThat(flag("SELECT pg_has_role('engine_owner', 'engine_system', 'SET')")).isTrue();
    }

    @Test
    void engineOwnerGetsNothingFromEngineSystemWithoutSwitchingRole() throws SQLException {
        run(TestDatabase.admin(DATABASE), """
                CREATE TABLE engine.only_system (id int);
                ALTER TABLE engine.only_system OWNER TO engine_system;
                """);

        assertRefused(owner(), "SELECT * FROM engine.only_system");
        run(owner(), "SET ROLE engine_system; SELECT * FROM engine.only_system");

        run(TestDatabase.admin(DATABASE), "DROP TABLE engine.only_system");
    }

    @Test
    void engineAppIsOutsideEngineSystem() throws SQLException {
        assertThat(flag("SELECT pg_has_role('engine_app', 'engine_system', 'MEMBER')")).isFalse();
    }

    @Test
    void engineAppCannotSwitchToEngineSystem() throws SQLException {
        assertRefused(app(), "SET ROLE engine_system");
    }

    @Test
    void engineOwnerCanCreateTablesInTheSchema() throws SQLException {
        run(owner(), "CREATE TABLE owned (id int); DROP TABLE owned");
    }

    @Test
    void engineAppCanUseTheSchema() throws SQLException {
        assertThat(flag("SELECT has_schema_privilege('engine_app', 'engine', 'USAGE')")).isTrue();
    }

    @Test
    void engineAppCannotCreateTables() throws SQLException {
        assertRefused(app(), "CREATE TABLE engine.mine (id int)");
    }

    @Test
    void engineAppCannotCreateTemporaryTables() throws SQLException {
        assertRefused(app(), "CREATE TEMPORARY TABLE shadow (id int)");
    }

    @Test
    void thePublicSchemaIsClosed() throws SQLException {
        assertRefused(app(), "CREATE TABLE public.mine (id int)");
        assertRefused(owner(), "CREATE TABLE public.mine (id int)");
    }

    @Test
    void unqualifiedNamesResolveToTheEngineSchema() throws SQLException {
        assertThat(flag("""
                SELECT setconfig @> ARRAY['search_path=engine']
                FROM pg_db_role_setting JOIN pg_roles r ON r.oid = setrole WHERE rolname = 'engine_app'
                """)).isTrue();
    }

    @Test
    void aWrongPasswordIsRefused() {
        assertThatThrownBy(() -> TestDatabase.connect(DATABASE, "engine_app", "wrong")).hasMessageContaining("password authentication failed");
    }

    @Test
    void theDatabaseIsClosedToEveryoneElse() throws SQLException {
        run(TestDatabase.admin(DATABASE), "CREATE ROLE stranger LOGIN PASSWORD 'stranger'");

        assertThatThrownBy(() -> TestDatabase.connect(DATABASE, "stranger", "stranger")).hasMessageContaining("permission denied");
    }

    @Test
    void theScriptRefusesToRunWithoutPasswords() throws IOException, InterruptedException {
        assertThat(TestDatabase.createRoles(DATABASE).getExitCode()).isNotZero();
    }
}
