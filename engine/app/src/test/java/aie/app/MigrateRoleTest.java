package aie.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import aie.adapters.storepg.CatalogCheck;
import aie.adapters.storepg.PgDatabase;
import aie.adapters.storepg.PgMigrations;
import aie.adapters.storepg.TestDatabase;
import aie.ports.DatabaseException;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

/** Runs the role and the real scripts, then uses the tables as engine_app. {@link TenantIsolationTest} covers isolation. */
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class MigrateRoleTest {

    private static final String TENANT_A = "ten_a";
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
        TestDatabase.asAdmin(DATABASE, "TRUNCATE engine.tenancy_tenants CASCADE");
    }

    private static int migrate() {
        return MigrateRole.run(
                "--engine.database.url=" + DATABASE.getJdbcUrl(), "--engine.owner.password=" + TestDatabase.OWNER_PASSWORD);
    }

    private static void addTenant(String tenant) {
        app.inTenantTx(tenant, tx -> tx.update(ADD_TENANT, tenant));
    }

    private static void addApplication(String application, String tenant) {
        app.inTenantTx(tenant, tx -> tx.update(ADD_APPLICATION, application, tenant));
    }

    private static List<String> tenantColumn(String tenant, String column) {
        return app.inTenantTx(tenant, tx -> tx.query("SELECT " + column + " FROM tenancy_tenants", row -> row.string(column)));
    }

    @Test
    void theScriptsWereAppliedInOrder() throws SQLException {
        assertThat(TestDatabase.firstColumn(
                        DATABASE, "SELECT version FROM engine.flyway_history_tenancy WHERE type = 'SQL' ORDER BY installed_rank"))
                .startsWith("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14")
                .isSortedAccordingTo(Comparator.comparingInt(Integer::parseInt));
    }

    /** The second module numbers its scripts from 1 again, in a history of its own (blueprint 5.6). */
    @Test
    void theConversationScriptsHaveAHistoryOfTheirOwn() throws SQLException {
        assertThat(TestDatabase.firstColumn(
                        DATABASE, "SELECT version FROM engine.flyway_history_conversation WHERE type = 'SQL' ORDER BY installed_rank"))
                .startsWith("1", "2")
                .isSortedAccordingTo(Comparator.comparingInt(Integer::parseInt));
    }

    @Test
    void aSecondRunSaysNothingWasApplied(CapturedOutput output) {
        assertThat(migrate()).isZero();

        assertThat(output.getOut()).contains("conversation: 0 applied", "tenancy: 0 applied");
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

    @Test
    void theRealScriptsPassTheCatalogCheck() throws SQLException {
        Set<String> systemTables = new HashSet<>(SystemObjects.TABLES);
        new PgMigrations(DATABASE.getJdbcUrl(), TestDatabase.OWNER_PASSWORD).run().keySet()
                .forEach(module -> systemTables.add("flyway_history_" + module));

        assertThat(CatalogCheck.violations(DATABASE, systemTables, SystemObjects.FUNCTIONS)).isEmpty();
    }

    @Test
    void anApplicationNeedsAnExistingTenant() {
        assertThatThrownBy(() -> addApplication("app_x", "ten_unknown"))
                .isInstanceOf(DatabaseException.class);
    }

    @Test
    void aParentTenantMayLiveInAnotherDatabase() {
        app.inTenantTx(TENANT_A, tx -> tx.update(
                "INSERT INTO tenancy_tenants (tenant_id, parent_tenant_id, cell_id) VALUES (?, 'ten_elsewhere', 'jp1')", TENANT_A));

        assertThat(tenantColumn(TENANT_A, "parent_tenant_id")).containsExactly("ten_elsewhere");
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
