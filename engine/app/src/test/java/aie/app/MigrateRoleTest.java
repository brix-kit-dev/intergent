package aie.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import aie.adapters.storepg.PgDatabase;
import aie.adapters.storepg.TestDatabase;
import aie.ports.DatabaseException;
import aie.ports.Tx;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Runs the role and the real scripts, then uses the tables the way the engine will: as engine_app, on one connection
 * that is reused like a pooled one. After a tenant transaction it holds an empty setting, which a fresh one does not.
 */
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class MigrateRoleTest {

    private static final String TENANT_A = "ten_a";
    private static final String TENANT_B = "ten_b";
    private static final String ADD_TENANT = "INSERT INTO tenancy_tenants (tenant_id, cell_id) VALUES (?, 'jp1')";
    private static final String ADD_APPLICATION = """
            INSERT INTO tenancy_applications (application_id, tenant_id, environment, api_version, default_data_class)
            VALUES (?, ?, 'test', '2026-11-05', 'internal')
            """;

    @Container
    private static final PostgreSQLContainer DATABASE = TestDatabase.container();

    private static PgDatabase app;

    @BeforeAll
    static void createRolesAndMigrate() throws IOException, InterruptedException {
        assertThat(TestDatabase.createRoles(
                        DATABASE, "owner_password=" + TestDatabase.OWNER_PASSWORD, "app_password=" + TestDatabase.APP_PASSWORD)
                .getExitCode()).isZero();
        assertThat(migrate()).isZero();
        app = new PgDatabase(
                new SingleConnectionDataSource(DATABASE.getJdbcUrl(), "engine_app", TestDatabase.APP_PASSWORD, true),
                Duration.ofSeconds(30));
    }

    @BeforeEach
    void emptyTheTables() throws SQLException {
        asAdmin("TRUNCATE engine.tenancy_applications, engine.tenancy_tenants");
    }

    private static void asAdmin(String sql) throws SQLException {
        try (Connection admin = TestDatabase.admin(DATABASE); Statement statement = admin.createStatement()) {
            statement.execute(sql);
        }
    }

    private static int migrate() {
        return MigrateRole.run(
                "--engine.database.url=" + DATABASE.getJdbcUrl(), "--engine.owner.password=" + TestDatabase.OWNER_PASSWORD);
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

    private static void addTenant(String tenant) {
        app.inTenantTx(tenant, tx -> tx.update(ADD_TENANT, tenant));
    }

    private static void addApplication(String application, String tenant) {
        app.inTenantTx(tenant, tx -> tx.update(ADD_APPLICATION, application, tenant));
    }

    private static List<String> tenantIds(Tx tx) {
        return tx.query("SELECT tenant_id FROM tenancy_tenants ORDER BY tenant_id", row -> row.string("tenant_id"));
    }

    private static List<String> applicationIds(Tx tx) {
        return tx.query("SELECT application_id FROM tenancy_applications ORDER BY application_id", row -> row.string("application_id"));
    }

    private static List<String> tenantsSeenBy(String tenant) {
        return app.inTenantTx(tenant, MigrateRoleTest::tenantIds);
    }

    private static List<String> applicationsSeenBy(String tenant) {
        return app.inTenantTx(tenant, MigrateRoleTest::applicationIds);
    }

    private static List<String> tenantsSeenWithoutAContext() {
        return app.inSystemTx(MigrateRoleTest::tenantIds);
    }

    private static List<String> applicationsSeenWithoutAContext() {
        return app.inSystemTx(MigrateRoleTest::applicationIds);
    }

    private static int updateAs(String tenant, String sql) {
        return app.inTenantTx(tenant, tx -> tx.update(sql));
    }

    private static List<String> tenantColumn(String tenant, String column) {
        return app.inTenantTx(tenant, tx -> tx.query("SELECT " + column + " FROM tenancy_tenants", row -> row.string(column)));
    }

    @Test
    void theScriptsWereAppliedInOrder() throws SQLException {
        assertThat(firstColumn("SELECT version FROM engine.flyway_history_tenancy WHERE type = 'SQL' ORDER BY installed_rank"))
                .containsExactly("1", "2");
    }

    @Test
    void aSecondRunSaysNothingWasApplied(CapturedOutput output) {
        assertThat(migrate()).isZero();

        assertThat(output.getOut()).contains("tenancy: 0 applied");
    }

    @Test
    void missingTheAddressIsAFailureThatNamesIt(CapturedOutput output) {
        assertThat(MigrateRole.run("--engine.database.url=", "--engine.owner.password=x")).isEqualTo(EngineApplication.EXIT_FAILED);

        assertThat(output.getErr()).contains("ENGINE_DATABASE_URL is required");
    }

    @Test
    void missingThePasswordIsAFailureThatNamesIt(CapturedOutput output) {
        assertThat(MigrateRole.run("--engine.database.url=" + DATABASE.getJdbcUrl(), "--engine.owner.password="))
                .isEqualTo(EngineApplication.EXIT_FAILED);

        assertThat(output.getErr()).contains("ENGINE_OWNER_PASSWORD is required");
    }

    @Test
    void aWrongPasswordIsAFailureThatDoesNotPrintIt(CapturedOutput output) {
        assertThat(MigrateRole.run("--engine.database.url=" + DATABASE.getJdbcUrl(), "--engine.owner.password=wrong-secret-value"))
                .isEqualTo(EngineApplication.EXIT_FAILED);

        assertThat(output.getErr()).contains("password authentication failed");
        assertThat(output.toString()).doesNotContain("wrong-secret-value");
    }

    @Test
    void theLauncherReturnsTheRolesExitCode() {
        assertThat(EngineApplication.launch(new String[] {"--role=migrate", "--engine.database.url=", "--engine.owner.password="}))
                .isEqualTo(EngineApplication.EXIT_FAILED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"tenancy_tenants", "tenancy_applications"})
    void everyTableFollowsTheTemplate(String table) throws SQLException {
        String name = "'engine." + table + "'::regclass";

        assertThat(firstColumn("SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid = " + name))
                .containsExactly("t");
        assertThat(firstColumn("SELECT pg_get_userbyid(relowner) FROM pg_class WHERE oid = " + name))
                .containsExactly("engine_owner");
        assertThat(firstColumn("SELECT polname FROM pg_policy WHERE polrelid = " + name))
                .containsExactly("tenant_isolation");
        assertThat(firstColumn("SELECT polcmd FROM pg_policy WHERE polrelid = " + name)).containsExactly("*");
        assertThat(firstColumn("SELECT polroles = '{0}' FROM pg_policy WHERE polrelid = " + name)).containsExactly("t");
        assertThat(firstColumn("""
                SELECT privilege_type FROM information_schema.role_table_grants
                WHERE grantee = 'engine_app' AND table_name = '%s' ORDER BY privilege_type""".formatted(table)))
                .containsExactly("DELETE", "INSERT", "SELECT", "UPDATE");
    }

    @Test
    void aTenantReadsOnlyItsOwnRows() {
        addTenant(TENANT_A);
        addTenant(TENANT_B);
        addApplication("app_a", TENANT_A);
        addApplication("app_b", TENANT_B);

        assertThat(tenantsSeenBy(TENANT_A)).containsExactly(TENANT_A);
        assertThat(applicationsSeenBy(TENANT_A)).containsExactly("app_a");
    }

    @Test
    void withoutATenantNothingCanBeRead() {
        addTenant(TENANT_A);
        addApplication("app_a", TENANT_A);

        assertThat(tenantsSeenWithoutAContext()).isEmpty();
        assertThat(applicationsSeenWithoutAContext()).isEmpty();
    }

    @Test
    void aTenantCannotWriteRowsOfAnother() {
        addTenant(TENANT_A);
        addTenant(TENANT_B);

        assertThatThrownBy(() -> app.inTenantTx(TENANT_A, tx -> tx.update(ADD_TENANT, "ten_c")))
                .isInstanceOf(DatabaseException.class);
        assertThatThrownBy(() -> app.inTenantTx(TENANT_A, tx -> tx.update(ADD_APPLICATION, "app_x", TENANT_B)))
                .isInstanceOf(DatabaseException.class);
    }

    @Test
    void aTenantCannotChangeRowsOfAnother() {
        addTenant(TENANT_A);
        addTenant(TENANT_B);
        addApplication("app_b", TENANT_B);

        assertThat(updateAs(TENANT_A, "UPDATE tenancy_applications SET api_version = 'x'")).isZero();
        assertThat(updateAs(TENANT_A, "DELETE FROM tenancy_applications")).isZero();
        assertThat(applicationsSeenBy(TENANT_B)).containsExactly("app_b");
    }

    @Test
    void aTenantCannotMoveItsRowToAnother() {
        addTenant(TENANT_A);
        addTenant(TENANT_B);
        addApplication("app_a", TENANT_A);

        assertThatThrownBy(() -> app.inTenantTx(TENANT_A, tx -> tx.update("UPDATE tenancy_applications SET tenant_id = ?", TENANT_B)))
                .isInstanceOf(DatabaseException.class);
    }

    @Test
    void anEmptyTenantIdCannotBeWrittenWithoutAContext() {
        addTenant(TENANT_A);

        assertThatThrownBy(() -> app.inSystemTx(tx -> tx.update(ADD_TENANT, "")))
                .isInstanceOf(DatabaseException.class)
                .hasStackTraceContaining("row-level security");
    }

    /** The tenant row is there so that row-level security refuses this, not the foreign key. */
    @Test
    void anApplicationWithAnEmptyTenantIdCannotBeWrittenWithoutAContext() throws SQLException {
        addTenant(TENANT_A);
        asAdmin("INSERT INTO engine.tenancy_tenants (tenant_id, cell_id) VALUES ('', 'jp1')");

        assertThatThrownBy(() -> app.inSystemTx(tx -> tx.update(ADD_APPLICATION, "app_x", "")))
                .isInstanceOf(DatabaseException.class)
                .hasStackTraceContaining("row-level security");
    }

    @Test
    void aRowWithAnEmptyTenantIdStaysHiddenWithoutAContext() throws SQLException {
        addTenant(TENANT_A);
        asAdmin("""
                INSERT INTO engine.tenancy_tenants (tenant_id, cell_id) VALUES ('', 'jp1');
                INSERT INTO engine.tenancy_applications (application_id, tenant_id, environment, api_version, default_data_class)
                VALUES ('app_x', '', 'test', '2026-11-05', 'internal');
                """);

        assertThat(tenantsSeenWithoutAContext()).isEmpty();
        assertThat(applicationsSeenWithoutAContext()).isEmpty();
    }

    @Test
    void anApplicationNeedsAnExistingTenant() {
        assertThatThrownBy(() -> addApplication("app_x", "ten_unknown"))
                .isInstanceOf(DatabaseException.class);
    }

    @Test
    void aParentTenantMayLiveInAnotherDatabase() {
        app.inTenantTx(TENANT_B, tx -> tx.update(
                "INSERT INTO tenancy_tenants (tenant_id, parent_tenant_id, cell_id) VALUES (?, 'ten_elsewhere', 'jp1')", TENANT_B));

        assertThat(tenantColumn(TENANT_B, "parent_tenant_id")).containsExactly("ten_elsewhere");
    }

    @Test
    void aNewTenantStartsInThePool() {
        addTenant(TENANT_A);

        assertThat(tenantColumn(TENANT_A, "isolation_tier")).containsExactly("pool");
    }

    @ParameterizedTest
    @ValueSource(strings = {"jp1", "a", "abcdefg8"})
    void goodCellCodesAreAccepted(String cell) {
        app.inTenantTx(TENANT_A, tx -> tx.update("INSERT INTO tenancy_tenants (tenant_id, cell_id) VALUES (?, ?)", TENANT_A, cell));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "JP1", "jp_1", "jp-1", "abcdefghi", "jp1\n"})
    void badCellCodesAreRefused(String cell) {
        assertThatThrownBy(() -> app.inTenantTx(
                        TENANT_A, tx -> tx.update("INSERT INTO tenancy_tenants (tenant_id, cell_id) VALUES (?, ?)", TENANT_A, cell)))
                .isInstanceOf(DatabaseException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pool", "silo", "cell"})
    void everyIsolationTierIsAccepted(String tier) {
        app.inTenantTx(TENANT_A, tx -> tx.update(
                "INSERT INTO tenancy_tenants (tenant_id, cell_id, isolation_tier) VALUES (?, 'jp1', ?)", TENANT_A, tier));
    }

    @Test
    void anUnknownIsolationTierIsRefused() {
        assertThatThrownBy(() -> app.inTenantTx(TENANT_A, tx -> tx.update(
                        "INSERT INTO tenancy_tenants (tenant_id, cell_id, isolation_tier) VALUES (?, 'jp1', 'shared')", TENANT_A)))
                .isInstanceOf(DatabaseException.class);
    }

    private static int addApplicationWith(String environment, String dataClass) {
        return app.inTenantTx(TENANT_A, tx -> tx.update("""
                INSERT INTO tenancy_applications (application_id, tenant_id, environment, api_version, default_data_class)
                VALUES ('app_a', ?, ?, '2026-11-05', ?)
                """, TENANT_A, environment, dataClass));
    }

    @ParameterizedTest
    @ValueSource(strings = {"live", "test"})
    void bothEnvironmentsAreAccepted(String environment) {
        addTenant(TENANT_A);

        assertThat(addApplicationWith(environment, "public")).isOne();
    }

    @Test
    void anUnknownEnvironmentIsRefused() {
        addTenant(TENANT_A);

        assertThatThrownBy(() -> addApplicationWith("staging", "public")).isInstanceOf(DatabaseException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"public", "internal", "personal", "sensitive"})
    void everyDataClassIsAccepted(String dataClass) {
        addTenant(TENANT_A);

        assertThat(addApplicationWith("test", dataClass)).isOne();
    }

    @Test
    void anUnknownDataClassIsRefused() {
        addTenant(TENANT_A);

        assertThatThrownBy(() -> addApplicationWith("test", "secret")).isInstanceOf(DatabaseException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "ALTER TABLE tenancy_tenants DISABLE ROW LEVEL SECURITY",
        "ALTER TABLE tenancy_tenants NO FORCE ROW LEVEL SECURITY",
        "DROP POLICY tenant_isolation ON tenancy_tenants",
        "DROP TABLE tenancy_applications",
        "TRUNCATE tenancy_tenants",
        "CREATE TABLE tenancy_extra (id text)"
    })
    void theAppCannotChangeTheStructure(String sql) throws SQLException {
        try (Connection connection = TestDatabase.connect(DATABASE, "engine_app", TestDatabase.APP_PASSWORD);
                Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.execute(sql))
                    .hasMessageMatching("(?s).*(must be owner|permission denied).*");
        }
    }
}
