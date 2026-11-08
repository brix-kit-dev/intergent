package aie.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import aie.adapters.storepg.PgDatabase;
import aie.adapters.storepg.TestDatabase;
import aie.ports.DatabaseException;
import aie.ports.Tx;
import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Red line T-2. Builds a database with the real scripts, finds every tenant table in its catalog, and works on each one
 * as engine_app through the port, so a new table is covered without anyone listing it here.
 *
 * <p>A connection with no tenant is in one of two states, so those tests run on both: a reused one holds an empty
 * setting, like a pooled connection after a tenant transaction, and a fresh one holds no setting at all.
 */
@Testcontainers
class TenantIsolationTest {

    private static final String TENANT_A = "ten_a";
    private static final String TENANT_B = "ten_b";
    private static final String TENANT_C = "ten_c";
    private static final String EMPTY_TENANT = "";

    /**
     * How to add one row for any tenant, parents first. A tenant table with no sample fails every test here.
     *
     * <p>Every sample tenant is a child of ten_a, so reading only its own rows also proves a parent sees no more.
     */
    private static final List<Sample> SAMPLES = List.of(
            new Sample("tenancy_tenants", """
                    INSERT INTO tenancy_tenants (tenant_id, parent_tenant_id, cell_id) VALUES ('{tenant}', 'ten_a', 'jp1')
                    """),
            new Sample("tenancy_applications", """
                    INSERT INTO tenancy_applications (application_id, tenant_id, environment, api_version, default_data_class)
                    VALUES ('app_{tenant}', '{tenant}', 'test', '2026-11-05', 'internal')
                    """),
            new Sample("tenancy_idempotency_keys", """
                    INSERT INTO tenancy_idempotency_keys (tenant_id, application_id, idempotency_key, fingerprint)
                    VALUES ('{tenant}', 'app_{tenant}', 'key_1', 'fingerprint')
                    """),
            new Sample("tenancy_api_keys", """
                    INSERT INTO tenancy_api_keys (key_id, tenant_id, application_id, key_hash)
                    VALUES ('key_{tenant}', '{tenant}', 'app_{tenant}', md5('{tenant}') || md5('{tenant}'))
                    """),
            new Sample("tenancy_end_users", """
                    INSERT INTO tenancy_end_users (end_user_id, tenant_id, application_id, external_id)
                    VALUES ('eu_{tenant}', '{tenant}', 'app_{tenant}', 'user-1')
                    """));

    private static final String TENANT_TABLES = """
            SELECT c.relname
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            JOIN pg_attribute a ON a.attrelid = c.oid
            WHERE n.nspname = 'engine' AND c.relkind IN ('r', 'p') AND a.attname = 'tenant_id' AND NOT a.attisdropped
            ORDER BY c.relname
            """;

    @Container
    private static final PostgreSQLContainer DATABASE = TestDatabase.container();

    private static PgDatabase reused;
    private static PgDatabase fresh;

    private record Sample(String table, String insert) {

        String insertFor(String tenant) {
            return insert.replace("{tenant}", tenant);
        }
    }

    @BeforeAll
    static void createRolesAndMigrate() throws IOException, InterruptedException {
        assertThat(TestDatabase.createRoles(
                        DATABASE, "owner_password=" + TestDatabase.OWNER_PASSWORD, "app_password=" + TestDatabase.APP_PASSWORD)
                .getExitCode()).isZero();
        assertThat(MigrateRole.run(
                        "--engine.database.url=" + DATABASE.getJdbcUrl(), "--engine.owner.password=" + TestDatabase.OWNER_PASSWORD))
                .isZero();
        Duration timeout = Duration.ofSeconds(30);
        reused = new PgDatabase(
                new SingleConnectionDataSource(DATABASE.getJdbcUrl(), "engine_app", TestDatabase.APP_PASSWORD, true), timeout);
        fresh = new PgDatabase(
                new DriverManagerDataSource(DATABASE.getJdbcUrl(), "engine_app", TestDatabase.APP_PASSWORD), timeout);
    }

    /** The rows go in through the port as each tenant, so this also proves a tenant can add its own rows. */
    @BeforeEach
    void giveBothTenantsARowInEveryTable() throws SQLException {
        for (String table : tenantTables()) {
            TestDatabase.asAdmin(DATABASE, "TRUNCATE engine." + table + " CASCADE");
        }
        for (String tenant : List.of(TENANT_A, TENANT_B)) {
            for (Sample sample : SAMPLES) {
                asTenant(tenant, sample.insertFor(tenant));
            }
        }
        for (String table : tenantTables()) {
            assertThat(rowsIn(table)).as("Rows in %s. A new tenant table needs an entry in SAMPLES", table)
                    .containsExactly(TENANT_A, TENANT_B);
        }
    }

    static List<String> tenantTables() throws SQLException {
        return TestDatabase.firstColumn(DATABASE, TENANT_TABLES).stream()
                .filter(table -> !SystemObjects.TABLES.contains(table))
                .toList();
    }

    /** What is really in the table, whatever row-level security lets the engine see. */
    private static List<String> rowsIn(String table) throws SQLException {
        return TestDatabase.firstColumn(DATABASE, "SELECT tenant_id FROM engine." + table + " ORDER BY tenant_id");
    }

    private static List<String> tenantIds(Tx tx, String table) {
        return tx.query("SELECT tenant_id FROM " + table + " ORDER BY tenant_id", row -> row.string("tenant_id"));
    }

    private static List<String> seenBy(String tenant, String table) {
        return reused.inTenantTx(tenant, tx -> tenantIds(tx, table));
    }

    private static List<String> seenWithoutAContext(PgDatabase connection, String table) {
        return connection.inSystemTx(tx -> tenantIds(tx, table));
    }

    private static int asTenant(String tenant, String sql) {
        return reused.inTenantTx(tenant, tx -> tx.update(sql));
    }

    private static int withoutAContext(PgDatabase connection, String sql) {
        return connection.inSystemTx(tx -> tx.update(sql));
    }

    private static String settingOn(PgDatabase connection) {
        return connection.inSystemTx(tx -> tx.query(
                "SELECT current_setting('app.tenant_id', true) AS value", row -> row.string("value")).get(0));
    }

    private static Sample sampleOf(String table) {
        return SAMPLES.stream().filter(sample -> sample.table().equals(table)).findFirst().orElseThrow();
    }

    private static void assertRefusedByRowSecurity(ThrowingCallable write) {
        assertThatThrownBy(write).isInstanceOf(DatabaseException.class).hasStackTraceContaining("row-level security");
    }

    /** The statements have no filter, so only row-level security decides what they reach. */
    private static void assertNothingInReach(PgDatabase connection, String table) {
        assertThat(seenWithoutAContext(connection, table)).isEmpty();
        assertThat(withoutAContext(connection, "UPDATE " + table + " SET tenant_id = 'ten_c'")).isZero();
        assertThat(withoutAContext(connection, "DELETE FROM " + table)).isZero();
    }

    /** The rows go in as the superuser, because nothing else may write them. */
    private static void addRowsWithAnEmptyTenantId() throws SQLException {
        for (Sample sample : SAMPLES) {
            TestDatabase.asAdmin(DATABASE, "SET search_path = engine; " + sample.insertFor(EMPTY_TENANT));
        }
    }

    @Test
    void everyTenantTableHasASampleAndEverySampleATable() throws SQLException {
        assertThat(tenantTables()).containsExactlyInAnyOrderElementsOf(SAMPLES.stream().map(Sample::table).toList());
    }

    @Test
    void theTwoConnectionsAreInTheTwoStates() {
        assertThat(settingOn(reused)).isEmpty();
        assertThat(settingOn(fresh)).isNull();
    }

    @ParameterizedTest
    @MethodSource("tenantTables")
    void aTenantSeesOnlyItsOwnRows(String table) {
        assertThat(seenBy(TENANT_A, table)).containsExactly(TENANT_A);
        assertThat(seenBy(TENANT_B, table)).containsExactly(TENANT_B);
    }

    /** With no filter it would take over every row, so changing exactly one proves both directions. */
    @ParameterizedTest
    @MethodSource("tenantTables")
    void anUpdateReachesOnlyTheTenantsOwnRows(String table) throws SQLException {
        assertThat(asTenant(TENANT_A, "UPDATE " + table + " SET tenant_id = 'ten_a'")).isOne();

        assertThat(rowsIn(table)).containsExactly(TENANT_A, TENANT_B);
    }

    /** Children first, because a parent row cannot go while its children stay. */
    @Test
    void aDeleteReachesOnlyTheTenantsOwnRows() throws SQLException {
        for (Sample sample : SAMPLES.reversed()) {
            assertThat(asTenant(TENANT_A, "DELETE FROM " + sample.table())).isOne();

            assertThat(rowsIn(sample.table())).containsExactly(TENANT_B);
        }
    }

    @ParameterizedTest
    @MethodSource("tenantTables")
    void aTenantCannotAddARowForAnotherTenant(String table) {
        assertRefusedByRowSecurity(() -> asTenant(TENANT_A, sampleOf(table).insertFor(TENANT_C)));
    }

    @ParameterizedTest
    @MethodSource("tenantTables")
    void aTenantCannotMoveItsRowToAnotherTenant(String table) {
        assertRefusedByRowSecurity(() -> asTenant(TENANT_A, "UPDATE " + table + " SET tenant_id = 'ten_b'"));
    }

    /** Row-level security does not apply to TRUNCATE, so only the missing grant stops one tenant emptying the table. */
    @ParameterizedTest
    @MethodSource("tenantTables")
    void aTenantCannotEmptyATable(String table) {
        assertThatThrownBy(() -> asTenant(TENANT_A, "TRUNCATE " + table))
                .isInstanceOf(DatabaseException.class)
                .hasStackTraceContaining("permission denied");
    }

    @ParameterizedTest
    @MethodSource("tenantTables")
    void withoutAContextNoRowIsInReach(String table) {
        assertNothingInReach(reused, table);
        assertNothingInReach(fresh, table);
    }

    @ParameterizedTest
    @MethodSource("tenantTables")
    void withoutAContextNoRowCanBeAdded(String table) {
        String insert = sampleOf(table).insertFor(TENANT_C);

        assertRefusedByRowSecurity(() -> withoutAContext(reused, insert));
        assertRefusedByRowSecurity(() -> withoutAContext(fresh, insert));
    }

    /** On a reused connection the setting is an empty string too, so only NULLIF in the policy keeps these rows out. */
    @ParameterizedTest
    @MethodSource("tenantTables")
    void aRowWithAnEmptyTenantIdIsOutOfReachWithoutAContext(String table) throws SQLException {
        addRowsWithAnEmptyTenantId();

        assertNothingInReach(reused, table);
        assertNothingInReach(fresh, table);
    }

    @ParameterizedTest
    @MethodSource("tenantTables")
    void aRowWithAnEmptyTenantIdCannotBeAddedWithoutAContext(String table) {
        String insert = sampleOf(table).insertFor(EMPTY_TENANT);

        assertRefusedByRowSecurity(() -> withoutAContext(reused, insert));
        assertRefusedByRowSecurity(() -> withoutAContext(fresh, insert));
    }
}
