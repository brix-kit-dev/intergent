package aie.adapters.storepg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import aie.ports.DatabaseException;
import aie.ports.Tx;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Runs as engine_app on one pooled connection, so anything left behind reaches the next transaction. */
@Testcontainers
class PgDatabaseTest {

    private static final String TENANT_SETTING = "SELECT current_setting('app.tenant_id', true) AS value";
    private static final String TENANT_A = "ten_a";
    private static final String TENANT_B = "ten_b";

    @Container
    private static final PostgreSQLContainer DATABASE = TestDatabase.container();

    private static HikariDataSource pool;
    private static PgDatabase database;

    @BeforeAll
    static void createRolesAndATable() throws IOException, InterruptedException, SQLException {
        assertThat(TestDatabase.createRoles(
                        DATABASE, "owner_password=" + TestDatabase.OWNER_PASSWORD, "app_password=" + TestDatabase.APP_PASSWORD)
                .getExitCode()).isZero();
        try (Connection admin = TestDatabase.admin(DATABASE); Statement statement = admin.createStatement()) {
            statement.execute("""
                    CREATE TABLE engine.notes (tenant_id text NOT NULL, body text, amount bigint, done boolean, at timestamptz);
                    ALTER TABLE engine.notes ENABLE ROW LEVEL SECURITY;
                    ALTER TABLE engine.notes FORCE ROW LEVEL SECURITY;
                    CREATE POLICY tenant ON engine.notes
                        USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''))
                        WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''));
                    GRANT SELECT, INSERT, UPDATE, DELETE ON engine.notes TO engine_app;
                    """);
        }
        pool = pool(1);
        database = new PgDatabase(pool, Duration.ofSeconds(30));
    }

    private static HikariDataSource pool(int size) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(DATABASE.getJdbcUrl());
        config.setUsername("engine_app");
        config.setPassword(TestDatabase.APP_PASSWORD);
        config.setMaximumPoolSize(size);
        return new HikariDataSource(config);
    }

    @AfterAll
    static void closeThePool() {
        pool.close();
    }

    @BeforeEach
    void emptyTheTable() throws SQLException {
        try (Connection admin = TestDatabase.admin(DATABASE); Statement statement = admin.createStatement()) {
            statement.execute("TRUNCATE engine.notes");
        }
    }

    private static void insert(String tenant, String body) {
        database.inTenantTx(tenant, tx -> tx.update("INSERT INTO notes (tenant_id, body) VALUES (?, ?)", tenant, body));
    }

    private static List<String> bodies(Tx tx) {
        return tx.query("SELECT body FROM notes ORDER BY body", row -> row.string("body"));
    }

    private static String tenantSetting(Tx tx) {
        return tx.query(TENANT_SETTING, row -> row.string("value")).get(0);
    }

    private static List<String> bodiesOf(String tenant) {
        return database.inTenantTx(tenant, PgDatabaseTest::bodies);
    }

    private static List<String> systemBodies() {
        return database.inSystemTx(PgDatabaseTest::bodies);
    }

    private static String settingOf(String tenant) {
        return database.inTenantTx(tenant, PgDatabaseTest::tenantSetting);
    }

    private static String systemSetting() {
        return database.inSystemTx(PgDatabaseTest::tenantSetting);
    }

    @Test
    void aTenantSeesOnlyItsOwnRows() {
        insert(TENANT_A, "a");
        insert(TENANT_B, "b");

        assertThat(bodiesOf(TENANT_A)).containsExactly("a");
        assertThat(bodiesOf(TENANT_B)).containsExactly("b");
    }

    @Test
    void aTenantCannotWriteARowForAnotherTenant() {
        assertThatThrownBy(() -> database.inTenantTx(TENANT_A, tx -> tx.update("INSERT INTO notes (tenant_id) VALUES (?)", TENANT_B)))
                .isInstanceOf(DatabaseException.class);
    }

    /** Virtual threads, as in the api role, with far fewer connections than work, so tenants keep taking them over. */
    @Test
    void tenantsWorkingAtOnceStayApart() throws Exception {
        insert(TENANT_A, "note of a");
        insert(TENANT_B, "note of b");
        try (HikariDataSource twoConnections = pool(2); ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            PgDatabase shared = new PgDatabase(twoConnections, Duration.ofSeconds(30));
            List<Future<List<String>>> seenByA = new ArrayList<>();
            List<Future<List<String>>> seenByB = new ArrayList<>();
            for (int round = 0; round < 100; round++) {
                seenByA.add(threads.submit(() -> shared.inTenantTx(TENANT_A, PgDatabaseTest::bodies)));
                seenByB.add(threads.submit(() -> shared.inTenantTx(TENANT_B, PgDatabaseTest::bodies)));
            }
            for (Future<List<String>> seen : seenByA) {
                assertThat(seen.get()).containsExactly("note of a");
            }
            for (Future<List<String>> seen : seenByB) {
                assertThat(seen.get()).containsExactly("note of b");
            }
        }
    }

    @Test
    void aSystemTransactionSeesNoBusinessRows() {
        insert(TENANT_A, "a");

        assertThat(systemBodies()).isEmpty();
    }

    @Test
    void aSystemTransactionHasNoTenant() {
        assertThat(systemSetting()).isNullOrEmpty();
    }

    @Test
    void theTenantIsSetInsideTheTransaction() {
        assertThat(settingOf(TENANT_A)).isEqualTo(TENANT_A);
    }

    @Test
    void theTenantEndsWithACommit() {
        insert(TENANT_A, "a");

        assertThat(systemSetting()).isNullOrEmpty();
        assertThat(systemBodies()).isEmpty();
    }

    @Test
    void theTenantEndsWithARollback() {
        assertThatThrownBy(() -> database.inTenantTx(TENANT_A, tx -> {
            throw new IllegalArgumentException("stop");
        })).hasMessage("stop");

        assertThat(systemSetting()).isNullOrEmpty();
    }

    @Test
    void aTenantIdIsDataNotSql() {
        String hostile = "x', true); DROP TABLE notes; --";

        assertThat(settingOf(hostile)).isEqualTo(hostile);
        assertThat(bodiesOf(hostile)).isEmpty();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " "})
    void aTenantTransactionNeedsATenant(String tenant) {
        assertThatThrownBy(() -> database.inTenantTx(tenant, tx -> 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aTransactionDoesNotNest() {
        assertThatThrownBy(() -> database.inTenantTx(TENANT_A, outer -> database.inTenantTx(TENANT_B, inner -> 1)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("nest");
        assertThatThrownBy(() -> database.inTenantTx(TENANT_A, outer -> database.inSystemTx(inner -> 1)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("nest");
        assertThatThrownBy(() -> database.inSystemTx(outer -> database.inTenantTx(TENANT_B, inner -> 1)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("nest");
    }

    @Test
    void aRefusedNestingLeavesTheTenantAlone() {
        String setting = database.inTenantTx(TENANT_A, outer -> {
            assertThatThrownBy(() -> database.inTenantTx(TENANT_B, inner -> 1)).isInstanceOf(IllegalStateException.class);
            return tenantSetting(outer);
        });

        assertThat(setting).isEqualTo(TENANT_A);
    }

    @Test
    void anExceptionRollsTheWorkBackAndComesOutUnchanged() {
        IllegalArgumentException stop = new IllegalArgumentException("stop");

        assertThatThrownBy(() -> database.inTenantTx(TENANT_A, tx -> {
            tx.update("INSERT INTO notes (tenant_id, body) VALUES (?, 'gone')", TENANT_A);
            throw stop;
        })).isSameAs(stop);

        assertThat(bodiesOf(TENANT_A)).isEmpty();
    }

    @Test
    void workIsCommittedWhenItEnds() {
        insert(TENANT_A, "kept");

        assertThat(bodiesOf(TENANT_A)).containsExactly("kept");
    }

    @Test
    void aFinishedTransactionCannotBeUsed() {
        AtomicReference<Tx> escaped = new AtomicReference<>();
        database.inTenantTx(TENANT_A, tx -> {
            escaped.set(tx);
            return 1;
        });

        assertThatThrownBy(() -> escaped.get().update("DELETE FROM notes")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> bodies(escaped.get())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anotherThreadCannotUseATransaction() {
        assertThatThrownBy(() -> database.inTenantTx(TENANT_A, tx -> CompletableFuture.supplyAsync(() -> bodies(tx)).join()))
                .isInstanceOf(CompletionException.class).hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void updateCountsTheRows() {
        int rows = database.inTenantTx(TENANT_A, tx -> tx.update("INSERT INTO notes (tenant_id) VALUES (?), (?)", TENANT_A, TENANT_A));

        assertThat(rows).isEqualTo(2);
    }

    @Test
    void columnsReadBackAsTheyWentIn() {
        Instant at = Instant.now().truncatedTo(ChronoUnit.MICROS);
        database.inTenantTx(TENANT_A, tx -> tx.update(
                "INSERT INTO notes (tenant_id, body, amount, done, at) VALUES (?, ?, ?, ?, ?)", TENANT_A, "text", 42L, true, at));

        List<Object> values = database.inTenantTx(TENANT_A, tx -> tx.query("SELECT * FROM notes", row -> List.<Object>of(
                row.string("body"), row.number("amount"), row.flag("done"), row.time("at"))).get(0));

        assertThat(values).containsExactly("text", 42L, true, at);
    }

    @Test
    void nullTextAndTimeReadAsNull() {
        insert(TENANT_A, null);

        List<Integer> rows = database.inTenantTx(TENANT_A, tx -> tx.query("SELECT body, at FROM notes", row -> {
            assertThat(row.string("body")).isNull();
            assertThat(row.time("at")).isNull();
            return 1;
        }));

        assertThat(rows).hasSize(1);
    }

    @Test
    void aNullNumberOrFlagFailsInsteadOfReadingAsZero() {
        insert(TENANT_A, "a");

        assertThatThrownBy(() -> database.inTenantTx(TENANT_A, tx -> tx.query("SELECT amount FROM notes", row -> row.number("amount"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("amount");
        assertThatThrownBy(() -> database.inTenantTx(TENANT_A, tx -> tx.query("SELECT done FROM notes", row -> row.flag("done"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("done");
    }

    @Test
    void anSqlErrorBecomesADatabaseException() {
        assertThatThrownBy(() -> database.inTenantTx(TENANT_A, tx -> tx.update("INSERT INTO missing VALUES (1)")))
                .isInstanceOf(DatabaseException.class);
        assertThatThrownBy(() -> database.inTenantTx(TENANT_A, tx -> tx.query("SELECT * FROM missing", row -> 1)))
                .isInstanceOf(DatabaseException.class);
    }

    @Test
    void workThatCatchesAFailureSeesADatabaseException() {
        database.inTenantTx(TENANT_A, tx -> {
            assertThatThrownBy(() -> tx.update("NOT SQL")).isInstanceOf(DatabaseException.class);
            return 1;
        });
        database.inTenantTx(TENANT_A, tx -> {
            assertThatThrownBy(() -> tx.query("NOT SQL", row -> 1)).isInstanceOf(DatabaseException.class);
            return 1;
        });
    }

    @Test
    void aDatabaseOutOfReachGivesADatabaseException() {
        PgDatabase unreachable = new PgDatabase(new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/none"), Duration.ofSeconds(5));

        assertThatThrownBy(() -> unreachable.inSystemTx(tx -> 1)).isInstanceOf(DatabaseException.class);
    }

    @Test
    void aConnectionWorksAfterAFailure() {
        assertThatThrownBy(() -> database.inTenantTx(TENANT_A, tx -> tx.update("NOT SQL"))).isInstanceOf(DatabaseException.class);

        insert(TENANT_A, "after");

        assertThat(bodiesOf(TENANT_A)).containsExactly("after");
    }

    @Test
    void aSlowTransactionIsStopped() {
        PgDatabase impatient = new PgDatabase(pool, Duration.ofSeconds(1));

        assertThatThrownBy(() -> impatient.inTenantTx(TENANT_A, tx -> tx.query("SELECT pg_sleep(20)", row -> 1)))
                .isInstanceOf(DatabaseException.class);
        insert(TENANT_A, "after");
    }
}
