package aie.adapters.storepg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import aie.ports.DatabaseException;
import aie.ports.DatabasePort;
import aie.ports.Tx;
import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class PgRuntimeTest {

    private static final Duration SETTLES = Duration.ofSeconds(10);
    private static final String SEEN = "SELECT pg_backend_pid()::text AS backend, current_setting('app.tenant_id', true) AS tenant";

    @Container
    private static final PostgreSQLContainer DATABASE = TestDatabase.container();

    @BeforeAll
    static void createRoles() throws IOException, InterruptedException {
        assertThat(TestDatabase.createRoles(
                        DATABASE, "owner_password=" + TestDatabase.OWNER_PASSWORD, "app_password=" + TestDatabase.APP_PASSWORD)
                .getExitCode()).isZero();
    }

    /** Every test closes what it opens, so each one starts with no engine_app connection left. */
    @BeforeEach
    void startWithoutEngineAppConnections() {
        await().atMost(SETTLES).until(() -> engineAppHolds(0));
    }

    private static String currentUser(DatabasePort database) {
        return database.inSystemTx(tx -> tx.query("SELECT current_user AS who", row -> row.string("who")).get(0));
    }

    private static String setting(DatabasePort database, String name) {
        return database.inSystemTx(tx -> tx.query("SELECT current_setting(?, true) AS value", row -> row.string("value"), name).get(0));
    }

    /** What a transaction sees: its connection, as the server process behind it, and its tenant. */
    private record Seen(String backend, String tenant) {
    }

    private static Seen seen(Tx tx) {
        return tx.query(SEEN, row -> new Seen(row.string("backend"), row.string("tenant"))).get(0);
    }

    /** Connections the server holds for engine_app right now, from every pool and check. */
    private static long engineAppConnections() throws SQLException {
        return Long.parseLong(TestDatabase.firstColumn(DATABASE, "SELECT count(*) FROM pg_stat_activity WHERE usename = 'engine_app'").get(0));
    }

    private static boolean engineAppHolds(long connections) throws SQLException {
        return engineAppConnections() == connections;
    }

    @Test
    void itConnectsAsEngineApp() {
        DatabasePort database = PgRuntime.connect(DATABASE.getJdbcUrl(), TestDatabase.APP_PASSWORD);

        assertThat(currentUser(database)).isEqualTo("engine_app");
    }

    @Test
    void theAddressCannotChooseAnotherRole() {
        DatabasePort database = PgRuntime.connect(DATABASE.getJdbcUrl() + "&user=postgres", TestDatabase.APP_PASSWORD);

        assertThat(currentUser(database)).isEqualTo("engine_app");
    }

    /** A default tenant in the address would give every transaction without a tenant one after all. */
    @Test
    void theAddressCannotSetSessionOptions() {
        DatabasePort database = PgRuntime.connect(
                DATABASE.getJdbcUrl() + "&options=-c%20app.tenant_id%3Dten_a%20-c%20row_security%3Doff", TestDatabase.APP_PASSWORD);

        assertThat(setting(database, "app.tenant_id")).isNull();
        assertThat(setting(database, "row_security")).isEqualTo("on");
    }

    @Test
    void aRoleThatFailsTheSelfCheckGetsNoConnection() throws SQLException {
        TestDatabase.asAdmin(DATABASE, "ALTER ROLE engine_app BYPASSRLS");
        try {
            assertThatThrownBy(() -> PgRuntime.connect(DATABASE.getJdbcUrl(), TestDatabase.APP_PASSWORD))
                    .isInstanceOf(DatabaseException.class)
                    .hasMessageContaining("has BYPASSRLS");
        } finally {
            TestDatabase.asAdmin(DATABASE, "ALTER ROLE engine_app NOBYPASSRLS");
        }
    }

    @Test
    void aPoolConnectsAsEngineAppWhateverTheAddressSays() {
        try (PgPool pool = PgRuntime.pool(DATABASE.getJdbcUrl() + "&user=postgres", TestDatabase.APP_PASSWORD, 2)) {
            assertThat(currentUser(pool)).isEqualTo("engine_app");
        }
    }

    @Test
    void aPoolTakesNoSessionOptionsFromTheAddress() {
        try (PgPool pool = PgRuntime.pool(
                DATABASE.getJdbcUrl() + "&options=-c%20app.tenant_id%3Dten_a%20-c%20row_security%3Doff", TestDatabase.APP_PASSWORD, 2)) {
            assertThat(setting(pool, "app.tenant_id")).isNull();
            assertThat(setting(pool, "row_security")).isEqualTo("on");
        }
    }

    /** One connection, so the second transaction runs on the connection the first one used. */
    @Test
    void aTenantDoesNotOutliveItsTransactionOnAPooledConnection() {
        try (PgPool pool = PgRuntime.pool(DATABASE.getJdbcUrl(), TestDatabase.APP_PASSWORD, 1)) {
            Seen first = pool.inTenantTx("ten_a", PgRuntimeTest::seen);
            Seen second = pool.inSystemTx(PgRuntimeTest::seen);

            assertThat(first.tenant()).isEqualTo("ten_a");
            assertThat(second).isEqualTo(new Seen(first.backend(), ""));
        }
    }

    @Test
    void aPoolTellsWhetherTheThreadIsInATransaction() {
        try (PgPool pool = PgRuntime.pool(DATABASE.getJdbcUrl(), TestDatabase.APP_PASSWORD, 1)) {
            boolean before = pool.insideTx();
            boolean inTenantTx = pool.inTenantTx("ten_a", tx -> pool.insideTx());
            boolean inSystemTx = pool.inSystemTx(tx -> pool.insideTx());
            boolean after = pool.insideTx();

            assertThat(before).isFalse();
            assertThat(inTenantTx).isTrue();
            assertThat(inSystemTx).isTrue();
            assertThat(after).isFalse();
        }
    }

    /** Twelve transactions at once on a pool of three: the rest wait for a connection instead of opening one. */
    @Test
    void aPoolHoldsNoMoreConnectionsThanItsSize() throws Exception {
        List<String> backends = new ArrayList<>();
        try (PgPool pool = PgRuntime.pool(DATABASE.getJdbcUrl(), TestDatabase.APP_PASSWORD, 3);
                ExecutorService requests = Executors.newFixedThreadPool(12)) {
            List<Future<String>> running = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                running.add(requests.submit(() -> pool.inSystemTx(tx -> tx.query(
                        "SELECT pg_backend_pid()::text AS backend FROM pg_sleep(0.2)", row -> row.string("backend")).get(0))));
            }
            for (Future<String> request : running) {
                backends.add(request.get());
            }
            assertThat(engineAppConnections()).isLessThanOrEqualTo(3);
        }
        Set<String> distinct = new HashSet<>(backends);

        assertThat(backends).hasSize(12);
        assertThat(distinct).hasSizeBetween(1, 3);
    }

    @Test
    void closingAPoolClosesItsConnections() throws SQLException {
        PgPool pool = PgRuntime.pool(DATABASE.getJdbcUrl(), TestDatabase.APP_PASSWORD, 2);
        await().atMost(SETTLES).until(() -> engineAppHolds(2));

        pool.close();

        await().atMost(SETTLES).until(() -> engineAppHolds(0));
    }

    /** A pool opened before the check would keep its connections after the failure. */
    @Test
    void aRoleThatFailsTheSelfCheckGetsNoPool() throws SQLException {
        TestDatabase.asAdmin(DATABASE, "ALTER ROLE engine_app BYPASSRLS");
        try {
            assertThatThrownBy(() -> PgRuntime.pool(DATABASE.getJdbcUrl(), TestDatabase.APP_PASSWORD, 2))
                    .isInstanceOf(DatabaseException.class)
                    .hasMessageContaining("has BYPASSRLS");
        } finally {
            TestDatabase.asAdmin(DATABASE, "ALTER ROLE engine_app NOBYPASSRLS");
        }

        await().during(Duration.ofSeconds(1)).atMost(SETTLES).until(() -> engineAppHolds(0));
    }
}
