package aie.adapters.storepg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.awaitility.Awaitility.await;

import aie.ports.DatabaseException;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Runs throwaway scripts from src/test/resources/migrations. The real scripts are tested in engine/app. */
@Testcontainers
class PgMigrationsTest {

    private static final String WAITING_FOR_THE_LOCK = "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted";

    @Container
    private static final PostgreSQLContainer DATABASE = TestDatabase.container();

    @BeforeAll
    static void createRoles() throws IOException, InterruptedException {
        assertThat(TestDatabase.createRoles(
                        DATABASE, "owner_password=" + TestDatabase.OWNER_PASSWORD, "app_password=" + TestDatabase.APP_PASSWORD)
                .getExitCode()).isZero();
    }

    @BeforeEach
    void startFromAnEmptySchema() throws SQLException {
        execute("""
                DROP SCHEMA IF EXISTS engine CASCADE;
                CREATE SCHEMA engine AUTHORIZATION engine_owner;
                GRANT USAGE ON SCHEMA engine TO engine_system, engine_app;
                """);
    }

    private static PgMigrations migrations(String fixture) {
        return new PgMigrations(DATABASE.getJdbcUrl(), TestDatabase.OWNER_PASSWORD, "migrations/" + fixture);
    }

    private static void execute(String sql) throws SQLException {
        try (Connection admin = TestDatabase.admin(DATABASE); Statement statement = admin.createStatement()) {
            statement.execute(sql);
        }
    }

    private static List<String> firstColumn(String sql) throws SQLException {
        try (Connection admin = TestDatabase.admin(DATABASE);
                Statement statement = admin.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            List<String> values = new ArrayList<>();
            while (rows.next()) {
                values.add(rows.getString(1));
            }
            return values;
        }
    }

    private static List<String> tables() throws SQLException {
        return firstColumn("SELECT tablename FROM pg_tables WHERE schemaname = 'engine' ORDER BY tablename");
    }

    private static List<String> appliedVersions(String history) throws SQLException {
        return firstColumn("SELECT version FROM engine." + history + " WHERE type = 'SQL' ORDER BY installed_rank");
    }

    @Test
    void eachModuleKeepsItsOwnHistoryAndVersions() throws SQLException {
        assertThat(migrations("two-modules").run()).containsExactly(entry("alpha", 2), entry("beta", 1));

        assertThat(tables()).containsExactly("alpha_items", "beta_items", "flyway_history_alpha", "flyway_history_beta");
        assertThat(appliedVersions("flyway_history_alpha")).containsExactly("1", "2");
        assertThat(appliedVersions("flyway_history_beta")).containsExactly("1");
    }

    @Test
    void aSecondRunAppliesNothing() throws SQLException {
        migrations("two-modules").run();

        assertThat(migrations("two-modules").run()).containsExactly(entry("alpha", 0), entry("beta", 0));
        assertThat(appliedVersions("flyway_history_alpha")).containsExactly("1", "2");
    }

    @Test
    void twoRunsAtOnceApplyEachScriptOnce() throws SQLException {
        CompletableFuture<Map<String, Integer>> first = CompletableFuture.supplyAsync(migrations("two-modules")::run);
        CompletableFuture<Map<String, Integer>> second = CompletableFuture.supplyAsync(migrations("two-modules")::run);

        assertThat(first.join().get("alpha") + second.join().get("alpha")).isEqualTo(2);
        assertThat(appliedVersions("flyway_history_alpha")).containsExactly("1", "2");
        assertThat(appliedVersions("flyway_history_beta")).containsExactly("1");
    }

    @Test
    void aRunWaitsForTheOneBeforeItAndReleasesTheLock() throws SQLException {
        try (Connection first = TestDatabase.admin(DATABASE); Statement lock = first.createStatement()) {
            lock.execute("SELECT pg_advisory_lock(" + PgMigrations.RUN_LOCK + ")");

            CompletableFuture<Map<String, Integer>> second = CompletableFuture.supplyAsync(migrations("two-modules")::run);

            await().atMost(Duration.ofSeconds(8)).until(() -> firstColumn(WAITING_FOR_THE_LOCK).equals(List.of("1")));
            assertThat(tables()).isEmpty();

            lock.execute("SELECT pg_advisory_unlock(" + PgMigrations.RUN_LOCK + ")");
            assertThat(second.join()).containsExactly(entry("alpha", 2), entry("beta", 1));
        }
        assertThat(firstColumn("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory'")).containsExactly("0");
    }

    /** Each script records whether the run's lock is held at that moment, so an early release shows in the last module. */
    @Test
    void theLockStaysHeldWhileEveryModuleRuns() throws SQLException {
        migrations("holds-lock").run();

        assertThat(firstColumn("SELECT held FROM engine.alpha_lock_seen")).containsExactly("1");
        assertThat(firstColumn("SELECT held FROM engine.beta_lock_seen")).containsExactly("1");
    }

    @Test
    void everythingBelongsToTheOwner() throws SQLException {
        migrations("two-modules").run();

        assertThat(firstColumn("SELECT DISTINCT tableowner FROM pg_tables WHERE schemaname = 'engine'"))
                .containsExactly("engine_owner");
        assertThat(firstColumn("SELECT DISTINCT installed_by FROM engine.flyway_history_alpha"))
                .containsExactly("engine_owner");
    }

    @Test
    void theHistoryIsClosedToTheApp() throws SQLException {
        migrations("two-modules").run();

        try (Connection app = TestDatabase.connect(DATABASE, "engine_app", TestDatabase.APP_PASSWORD);
                Statement statement = app.createStatement()) {
            assertThatThrownBy(() -> statement.executeQuery("SELECT * FROM flyway_history_alpha"))
                    .hasMessageContaining("permission denied");
        }
    }

    @Test
    void aFailedScriptLeavesNothingBehind() throws SQLException {
        assertThatThrownBy(() -> migrations("broken").run())
                .isInstanceOf(DatabaseException.class)
                .hasMessageContaining("Migrating alpha failed")
                .hasMessageContaining("division by zero");

        assertThat(tables()).doesNotContain("alpha_half");
    }

    @Test
    void aChangedScriptIsRefused() {
        migrations("changed-before").run();

        assertThatThrownBy(() -> migrations("changed-after").run())
                .isInstanceOf(DatabaseException.class)
                .hasMessageContaining("checksum");
    }

    @Test
    void aWronglyNamedScriptIsRefusedNotSkipped() {
        assertThatThrownBy(() -> migrations("misnamed").run())
                .isInstanceOf(DatabaseException.class)
                .hasMessageContaining("v1-create.sql");
    }

    @Test
    void findingNoScriptsIsAnError() {
        assertThatThrownBy(() -> migrations("nothing-here").run())
                .isInstanceOf(DatabaseException.class)
                .hasMessageContaining("No migrations found");
    }

    @Test
    void aMissingSchemaIsAnErrorBecauseTheAdministratorCreatesIt() throws SQLException {
        execute("DROP SCHEMA engine CASCADE");

        assertThatThrownBy(() -> migrations("two-modules").run())
                .isInstanceOf(DatabaseException.class)
                .hasMessageContaining("Migrating alpha failed")
                .hasMessageNotContaining("permission denied");
    }

    @Test
    void aDataMigrationThatForgetsForceFailsInsteadOfUpdatingNothing() throws SQLException {
        assertThatThrownBy(() -> migrations("forgot-force").run())
                .isInstanceOf(DatabaseException.class)
                .hasMessageContaining("row-level security");

        assertThat(appliedVersions("flyway_history_alpha")).containsExactly("1");
    }

    @Test
    void aDataMigrationThatLiftsForceUpdatesTheRows() throws SQLException {
        migrations("lifted-force").run();

        assertThat(firstColumn("SELECT note FROM engine.alpha_items")).containsExactly("changed");
        assertThat(firstColumn("SELECT relforcerowsecurity FROM pg_class WHERE oid = 'engine.alpha_items'::regclass"))
                .containsExactly("t");
    }

    @Test
    void aBlockedMigrationGivesUp() throws SQLException {
        execute("""
                CREATE TABLE engine.alpha_items (id text PRIMARY KEY);
                ALTER TABLE engine.alpha_items OWNER TO engine_owner;
                """);
        try (Connection admin = TestDatabase.admin(DATABASE); Statement statement = admin.createStatement()) {
            admin.setAutoCommit(false);
            statement.execute("LOCK TABLE engine.alpha_items");

            CompletableFuture<Map<String, Integer>> run = CompletableFuture.supplyAsync(migrations("blocked")::run);

            assertThat(run).failsWithin(Duration.ofSeconds(30)).withThrowableThat()
                    .withMessageContaining("Migrating alpha failed")
                    .withMessageContaining("lock timeout");
            admin.rollback();
        }
    }

    @Test
    void theAddressCannotChangeTheRoleOrTheSession() throws SQLException {
        String address = DATABASE.getJdbcUrl() + "&user=postgres&options=-c%20row_security%3Don";
        PgMigrations migrations = new PgMigrations(address, TestDatabase.OWNER_PASSWORD, "migrations/forgot-force");

        assertThatThrownBy(migrations::run).hasMessageContaining("row-level security");

        assertThat(firstColumn("SELECT DISTINCT installed_by FROM engine.flyway_history_alpha"))
                .containsExactly("engine_owner");
    }

    @Test
    void aWrongPasswordFailsWithoutShowingIt() {
        PgMigrations migrations = new PgMigrations(DATABASE.getJdbcUrl(), "wrong-secret-value", "migrations/two-modules");

        assertThatThrownBy(migrations::run)
                .isInstanceOf(DatabaseException.class)
                .hasMessageContaining("password authentication failed")
                .hasMessageNotContaining("wrong-secret-value");
    }

    @Test
    void aBadAddressIsRefusedWithoutRepeatingIt() {
        assertThatThrownBy(() -> new PgMigrations("jdbc:mysql://host/db?password=secret-value", "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("secret-value");
    }
}
