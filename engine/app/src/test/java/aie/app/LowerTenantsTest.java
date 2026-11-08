package aie.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import aie.adapters.storepg.PgDatabase;
import aie.adapters.storepg.TestDatabase;
import aie.core.tenancy.api.ApiKeyLookup.Owner;
import aie.core.tenancy.api.LowerTenants;
import aie.core.tenancy.api.LowerTenants.Acting;
import aie.core.tenancy.api.Provisioning;
import aie.core.tenancy.internal.DatabaseLowerTenants;
import aie.core.tenancy.internal.DatabaseProvisioning;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** On-Behalf-Of's system function on the real scripts, as engine_app (blueprint 6.5 and 7.6). */
@Testcontainers
class LowerTenantsTest {

    @Container
    private static final PostgreSQLContainer DATABASE = TestDatabase.container();

    private static PgDatabase database;
    private static LowerTenants lowerTenants;
    private static Provisioning provisioning;

    @BeforeAll
    static void createRolesAndMigrate() throws IOException, InterruptedException {
        assertThat(TestDatabase.createRoles(
                        DATABASE, "owner_password=" + TestDatabase.OWNER_PASSWORD, "app_password=" + TestDatabase.APP_PASSWORD)
                .getExitCode()).isZero();
        assertThat(MigrateRole.run(
                        "--engine.database.url=" + DATABASE.getJdbcUrl(), "--engine.owner.password=" + TestDatabase.OWNER_PASSWORD))
                .isZero();
        database = new PgDatabase(
                new DriverManagerDataSource(DATABASE.getJdbcUrl(), "engine_app", TestDatabase.APP_PASSWORD), Duration.ofSeconds(30));
        lowerTenants = new DatabaseLowerTenants(database);
        provisioning = new DatabaseProvisioning(database);
    }

    @BeforeEach
    void emptyTheTables() throws SQLException {
        TestDatabase.asAdmin(DATABASE, "TRUNCATE engine.tenancy_tenants CASCADE");
    }

    /** A platform host with a test application and one key, as the caller of every test. */
    private static Owner platform() {
        String tenant = provisioning.createTenant("jp1", null);
        String application = provisioning.createApplication(tenant, "test", "internal");
        return new Owner(provisioning.createKey(tenant, application).keyId(), tenant, application);
    }

    private static String lowerTenantOf(String parent) {
        return provisioning.createTenant("jp1", parent);
    }

    private static List<String> seenWithoutATenant(String table) {
        return database.inSystemTx(tx -> tx.query("SELECT tenant_id FROM " + table, row -> row.string("tenant_id")));
    }

    @Test
    void aKeyActsAsTheLowerTenantsApplicationOfItsEnvironment() {
        Owner platform = platform();
        String lower = lowerTenantOf(platform.tenantId());
        String test = provisioning.createApplication(lower, "test", "internal");
        String live = provisioning.createApplication(lower, "live", "internal");

        assertThat(lowerTenants.actFor(platform, "test", lower)).isEqualTo(new Acting.As(new Owner(platform.keyId(), lower, test)));
        assertThat(lowerTenants.actFor(platform, "live", lower)).isEqualTo(new Acting.As(new Owner(platform.keyId(), lower, live)));
    }

    @Test
    void anotherTenantIsNotALowerTenant() {
        Owner platform = platform();
        String other = provisioning.createTenant("jp1", null);
        provisioning.createApplication(other, "test", "internal");

        assertThat(lowerTenants.actFor(platform, "test", other)).isEqualTo(new Acting.NotALowerTenant());
    }

    @Test
    void anotherPlatformsLowerTenantIsNotOne() {
        Owner platform = platform();
        String lower = lowerTenantOf(platform().tenantId());
        provisioning.createApplication(lower, "test", "internal");

        assertThat(lowerTenants.actFor(platform, "test", lower)).isEqualTo(new Acting.NotALowerTenant());
    }

    @Test
    void anUnknownTenantIsNotALowerTenant() {
        assertThat(lowerTenants.actFor(platform(), "test", "ten_unknown")).isEqualTo(new Acting.NotALowerTenant());
    }

    @Test
    void theCallersOwnTenantIsNotALowerTenant() {
        Owner platform = platform();

        assertThat(lowerTenants.actFor(platform, "test", platform.tenantId())).isEqualTo(new Acting.NotALowerTenant());
    }

    /** Only the platform's own lower tenants count, not theirs in turn. */
    @Test
    void aLowerTenantOfALowerTenantIsNotOne() {
        Owner platform = platform();
        String further = lowerTenantOf(lowerTenantOf(platform.tenantId()));
        provisioning.createApplication(further, "test", "internal");

        assertThat(lowerTenants.actFor(platform, "test", further)).isEqualTo(new Acting.NotALowerTenant());
    }

    @Test
    void aLowerTenantWithoutAnApplicationOfTheEnvironmentHasNoneToActAs() {
        Owner platform = platform();
        String none = lowerTenantOf(platform.tenantId());
        String testOnly = lowerTenantOf(platform.tenantId());
        provisioning.createApplication(testOnly, "test", "internal");

        assertThat(lowerTenants.actFor(platform, "test", none)).isEqualTo(new Acting.NoSingleApplication());
        assertThat(lowerTenants.actFor(platform, "live", testOnly)).isEqualTo(new Acting.NoSingleApplication());
    }

    /** Two candidates would be a guess, so neither is taken. */
    @Test
    void aLowerTenantWithTwoApplicationsOfTheEnvironmentHasNoneToActAs() {
        Owner platform = platform();
        String lower = lowerTenantOf(platform.tenantId());
        provisioning.createApplication(lower, "test", "internal");
        provisioning.createApplication(lower, "test", "internal");

        assertThat(lowerTenants.actFor(platform, "test", lower)).isEqualTo(new Acting.NoSingleApplication());
    }

    /** The new read policies are engine_system's alone, so engine_app without a tenant still reads empty tables. */
    @Test
    void engineAppStillSeesNoTenantWithoutOne() {
        Owner platform = platform();
        provisioning.createApplication(lowerTenantOf(platform.tenantId()), "test", "internal");

        assertThat(seenWithoutATenant("tenancy_tenants")).isEmpty();
        assertThat(seenWithoutATenant("tenancy_applications")).isEmpty();
    }

    /** The connection opens outside the assertion, so a role that cannot log in does not pass for the wrong reason. */
    @Test
    void noOtherRoleMayCallTheFunction() throws SQLException {
        TestDatabase.asAdmin(DATABASE, """
                CREATE ROLE outsider LOGIN PASSWORD 'outsider-password';
                GRANT USAGE ON SCHEMA engine TO outsider;
                DO $$ BEGIN EXECUTE format('GRANT CONNECT ON DATABASE %I TO outsider', current_database()); END $$;
                """);
        try (Connection outsider = TestDatabase.connect(DATABASE, "outsider", "outsider-password");
                Statement statement = outsider.createStatement()) {
            assertThatThrownBy(() -> statement.executeQuery("SELECT * FROM engine.tenancy_find_lower_applications('a', 'b', 'test')"))
                    .hasMessageContaining("permission denied for function tenancy_find_lower_applications");
        }
    }
}
