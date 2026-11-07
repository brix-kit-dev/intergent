package aie.adapters.storepg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import aie.ports.DatabaseException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.SQLException;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Starts from the roles of create-roles.sql, and each test leaves them as it found them. */
@Testcontainers
class PgSelfCheckTest {

    private static final String REFUSED = "engine_app may not serve traffic: it ";
    private static final String OWNED_TABLE = "CREATE TABLE %1$s (id int); ALTER TABLE %1$s OWNER TO engine_app";

    @Container
    private static final PostgreSQLContainer DATABASE = TestDatabase.container();

    @BeforeAll
    static void createRoles() throws IOException, InterruptedException {
        assertThat(TestDatabase.createRoles(
                        DATABASE, "owner_password=" + TestDatabase.OWNER_PASSWORD, "app_password=" + TestDatabase.APP_PASSWORD)
                .getExitCode()).isZero();
    }

    private static void check() {
        PgSelfCheck.run(DATABASE.getJdbcUrl(), TestDatabase.APP_PASSWORD);
    }

    static Stream<Arguments> breaches() {
        return Stream.of(
                arguments("ALTER ROLE engine_app BYPASSRLS", "ALTER ROLE engine_app NOBYPASSRLS", "has BYPASSRLS"),
                arguments(OWNED_TABLE.formatted("engine.sample_owned"), "DROP TABLE engine.sample_owned", "owns tables"),
                arguments("CREATE TABLE engine.sample_parts (id int) PARTITION BY RANGE (id);"
                                + "ALTER TABLE engine.sample_parts OWNER TO engine_app",
                        "DROP TABLE engine.sample_parts", "owns tables"),
                arguments("CREATE SCHEMA sample;" + OWNED_TABLE.formatted("sample.owned"), "DROP SCHEMA sample CASCADE", "owns tables"),
                arguments("GRANT engine_system TO engine_app", "REVOKE engine_system FROM engine_app", "belongs to engine_system"),
                arguments("CREATE ROLE sample_middle IN ROLE engine_system; GRANT sample_middle TO engine_app",
                        "DROP ROLE sample_middle", "belongs to engine_system"),
                arguments("ALTER ROLE engine_app SUPERUSER", "ALTER ROLE engine_app NOSUPERUSER",
                        "is a superuser, belongs to engine_system"),
                arguments("ALTER ROLE engine_app BYPASSRLS;" + OWNED_TABLE.formatted("engine.sample_owned"),
                        "ALTER ROLE engine_app NOBYPASSRLS; DROP TABLE engine.sample_owned", "has BYPASSRLS, owns tables"));
    }

    @Test
    void theRoleFromTheRoleScriptPasses() {
        assertThatCode(PgSelfCheckTest::check).doesNotThrowAnyException();
    }

    @Test
    void usingATableOfTheOwnerIsFine() throws SQLException {
        TestDatabase.asAdmin(DATABASE, """
                CREATE TABLE engine.sample_used (id int);
                ALTER TABLE engine.sample_used OWNER TO engine_owner;
                GRANT SELECT, INSERT, UPDATE, DELETE ON engine.sample_used TO engine_app;
                """);
        try {
            assertThatCode(PgSelfCheckTest::check).doesNotThrowAnyException();
        } finally {
            TestDatabase.asAdmin(DATABASE, "DROP TABLE engine.sample_used");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("breaches")
    void aRoleThatBreaksTheRulesIsRefused(String change, String undo, String problems) throws SQLException {
        TestDatabase.asAdmin(DATABASE, change);
        try {
            assertThatThrownBy(PgSelfCheckTest::check).isInstanceOf(DatabaseException.class).hasMessage(REFUSED + problems);
        } finally {
            TestDatabase.asAdmin(DATABASE, undo);
        }
    }

    /** It connects as engine_app with the app password, which the address cannot change. As postgres, this would fail. */
    @Test
    void theAddressCannotChangeTheRole() {
        assertThatCode(() -> PgSelfCheck.run(DATABASE.getJdbcUrl() + "&user=postgres", TestDatabase.APP_PASSWORD))
                .doesNotThrowAnyException();
    }

    @Test
    void aWrongPasswordIsRefusedWithoutPrintingIt() {
        assertThatThrownBy(() -> PgSelfCheck.run(DATABASE.getJdbcUrl(), "wrong-secret-value"))
                .isInstanceOf(DatabaseException.class)
                .hasMessageContaining("password authentication failed")
                .hasMessageNotContaining("wrong-secret-value");
    }

    @Test
    void aBadAddressIsRefusedWithoutPrintingIt() {
        assertThatThrownBy(() -> PgSelfCheck.run("mysql://secret-host/engine", TestDatabase.APP_PASSWORD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The database address is not a PostgreSQL JDBC URL");
    }

    /** Nothing can be checked, and a role that cannot be checked does not start. */
    @Test
    void aCellWithoutEngineSystemIsRefused() throws SQLException {
        TestDatabase.asAdmin(DATABASE, "ALTER ROLE engine_system RENAME TO sample_renamed");
        try {
            assertThatThrownBy(PgSelfCheckTest::check)
                    .isInstanceOf(DatabaseException.class)
                    .hasMessageContaining("could not be checked")
                    .hasMessageContaining("engine_system");
        } finally {
            TestDatabase.asAdmin(DATABASE, "ALTER ROLE sample_renamed RENAME TO engine_system");
        }
    }

    @Test
    void aDatabaseOutOfReachIsRefused() throws IOException {
        int port;
        try (ServerSocket free = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = free.getLocalPort();
        }
        int closed = port;

        assertThatThrownBy(() -> PgSelfCheck.run("jdbc:postgresql://127.0.0.1:" + closed + "/engine", TestDatabase.APP_PASSWORD))
                .isInstanceOf(DatabaseException.class)
                .hasMessageContaining("could not be checked");
    }

    /** A server that accepts and never answers would otherwise hang the start for good. A socket read ignores interrupts. */
    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aDatabaseThatNeverAnswersIsRefused() throws IOException {
        try (ServerSocket silent = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread.ofVirtual().start(() -> holdTheConnection(silent));
            String url = "jdbc:postgresql://127.0.0.1:" + silent.getLocalPort() + "/engine";

            assertThatThrownBy(() -> PgSelfCheck.run(url, TestDatabase.APP_PASSWORD, 1))
                    .isInstanceOf(DatabaseException.class)
                    .hasMessageContaining("could not be checked");
        }
    }

    private static void holdTheConnection(ServerSocket server) {
        try (Socket held = server.accept()) {
            held.getInputStream().readAllBytes();
        } catch (IOException e) {
            // The test has ended and closed the socket.
        }
    }
}
