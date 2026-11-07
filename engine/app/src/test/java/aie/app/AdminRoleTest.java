package aie.app;

import static org.assertj.core.api.Assertions.assertThat;

import aie.adapters.storepg.TestDatabase;
import aie.core.tenancy.api.ApiVersion;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Runs the role as an operator would, against the real scripts, and reads the result as the superuser. */
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class AdminRoleTest {

    private static final String AN_ID = "_[0-9A-HJKMNP-TV-Z]{26}";

    @Container
    private static final PostgreSQLContainer DATABASE = TestDatabase.container();

    @BeforeAll
    static void createRolesAndMigrate() throws IOException, InterruptedException {
        assertThat(TestDatabase.createRoles(
                        DATABASE, "owner_password=" + TestDatabase.OWNER_PASSWORD, "app_password=" + TestDatabase.APP_PASSWORD)
                .getExitCode()).isZero();
        assertThat(MigrateRole.run(
                        "--engine.database.url=" + DATABASE.getJdbcUrl(), "--engine.owner.password=" + TestDatabase.OWNER_PASSWORD))
                .isZero();
    }

    @BeforeEach
    void startEmpty() throws SQLException {
        TestDatabase.asAdmin(DATABASE, "TRUNCATE engine.tenancy_tenants CASCADE");
    }

    /** The settings a cell gives the role, then the command. A setting given in the command replaces the cell's. */
    private static String[] settings(String... more) {
        List<String> args = new ArrayList<>(List.of("--role=admin"));
        for (String setting : List.of(
                "--engine.database.url=" + DATABASE.getJdbcUrl(),
                "--engine.app.password=" + TestDatabase.APP_PASSWORD,
                "--engine.cell.code=jp1")) {
            String name = setting.substring(0, setting.indexOf('=') + 1);
            if (Arrays.stream(more).noneMatch(arg -> arg.startsWith(name))) {
                args.add(setting);
            }
        }
        args.addAll(Arrays.asList(more));
        return args.toArray(String[]::new);
    }

    private static int admin(String... args) {
        return AdminRole.run(settings(args));
    }

    /** Runs a command that must succeed and returns what it printed, which must be nothing but the new ID. */
    private static String created(CapturedOutput output, String... args) {
        int before = output.getOut().length();
        assertThat(admin(args)).isZero();
        return output.getOut().substring(before).strip();
    }

    private static List<String> rows(String sql) throws SQLException {
        return TestDatabase.firstColumn(DATABASE, sql);
    }

    @Test
    void createTenantPrintsOnlyTheNewId(CapturedOutput output) {
        assertThat(created(output, "create-tenant")).matches("ten" + AN_ID);
    }

    @Test
    void aNewTenantLivesInThisCellInThePoolWithNoParent(CapturedOutput output) throws SQLException {
        String tenant = created(output, "create-tenant");

        assertThat(rows("SELECT concat_ws(',', tenant_id, cell_id, isolation_tier, coalesce(parent_tenant_id, '-')) FROM engine.tenancy_tenants"))
                .containsExactly(tenant + ",jp1,pool,-");
    }

    @Test
    void aTenantCanHaveAParent(CapturedOutput output) throws SQLException {
        created(output, "create-tenant", "--parent=ten_elsewhere");

        assertThat(rows("SELECT parent_tenant_id FROM engine.tenancy_tenants")).containsExactly("ten_elsewhere");
    }

    @Test
    void anApplicationIsATestApplicationWithInternalDataByDefault(CapturedOutput output) throws SQLException {
        String tenant = created(output, "create-tenant");
        String application = created(output, "create-application", "--tenant=" + tenant);

        assertThat(application).matches("app" + AN_ID);
        assertThat(rows("SELECT concat_ws(',', application_id, tenant_id, environment, default_data_class, api_version) FROM engine.tenancy_applications"))
                .containsExactly(String.join(",", application, tenant, "test", "internal", ApiVersion.CURRENT));
    }

    @Test
    void anApplicationCanBeLiveWithAnotherDataClass(CapturedOutput output) throws SQLException {
        String tenant = created(output, "create-tenant");
        created(output, "create-application", "--tenant=" + tenant, "--environment=live", "--data-class=personal");

        assertThat(rows("SELECT environment || ',' || default_data_class FROM engine.tenancy_applications")).containsExactly("live,personal");
    }

    @Test
    void anApplicationNeedsAnExistingTenant(CapturedOutput output) throws SQLException {
        assertThat(admin("create-application", "--tenant=ten_unknown")).isEqualTo(EngineApplication.EXIT_FAILED);

        assertThat(output.getErr()).contains("Admin failed: Tenant ten_unknown does not exist");
        assertThat(rows("SELECT count(*) FROM engine.tenancy_applications")).containsExactly("0");
    }

    @ParameterizedTest
    @ValueSource(strings = {"--environment=staging", "--data-class=secret"})
    void theDatabaseRefusesAValueItDoesNotKnowAndSaysWhy(String option, CapturedOutput output) throws SQLException {
        String tenant = created(output, "create-tenant");

        assertThat(admin("create-application", "--tenant=" + tenant, option)).isEqualTo(EngineApplication.EXIT_FAILED);

        assertThat(output.getErr()).contains("Admin failed: ", "violates check constraint");
        assertThat(rows("SELECT count(*) FROM engine.tenancy_applications")).containsExactly("0");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "create-everything", "create-tenant create-application"})
    void aWrongCommandIsAUsageError(String words, CapturedOutput output) {
        String[] args = words.isEmpty() ? new String[0] : words.split(" ");

        assertThat(admin(args)).isEqualTo(EngineApplication.EXIT_USAGE);
        assertThat(output.getErr()).contains("Usage: --role=admin <command>");
    }

    @ParameterizedTest
    @ValueSource(strings = {"--tenant=ten_x", "--environmnet=live", "--parent="})
    void aWrongOptionIsAUsageError(String option, CapturedOutput output) throws SQLException {
        assertThat(admin("create-tenant", option)).isEqualTo(EngineApplication.EXIT_USAGE);

        assertThat(output.getErr()).contains("Usage: --role=admin <command>");
        assertThat(rows("SELECT count(*) FROM engine.tenancy_tenants")).containsExactly("0");
    }

    @Test
    void createApplicationNeedsATenant(CapturedOutput output) {
        assertThat(admin("create-application")).isEqualTo(EngineApplication.EXIT_USAGE);

        assertThat(output.getErr()).contains("create-application needs --tenant.");
    }

    @Test
    void createTenantNeedsTheCellCode(CapturedOutput output) {
        assertThat(admin("create-tenant", "--engine.cell.code=")).isEqualTo(EngineApplication.EXIT_FAILED);

        assertThat(output.getErr()).contains("ENGINE_CELL_CODE is required");
    }

    @ParameterizedTest
    @ValueSource(strings = {"JP1", "jp_1", "abcdefghi"})
    void aCellCodeThatWouldBreakKeysIsRefused(String cell, CapturedOutput output) throws SQLException {
        assertThat(admin("create-tenant", "--engine.cell.code=" + cell)).isEqualTo(EngineApplication.EXIT_FAILED);

        assertThat(output.getErr()).contains("ENGINE_CELL_CODE must be 1 to 8 lowercase letters and digits");
        assertThat(rows("SELECT count(*) FROM engine.tenancy_tenants")).containsExactly("0");
    }

    @Test
    void aRoleThatFailsTheSelfCheckChangesNothing(CapturedOutput output) throws SQLException {
        TestDatabase.asAdmin(DATABASE, "ALTER ROLE engine_app BYPASSRLS");
        try {
            assertThat(admin("create-tenant")).isEqualTo(EngineApplication.EXIT_FAILED);
        } finally {
            TestDatabase.asAdmin(DATABASE, "ALTER ROLE engine_app NOBYPASSRLS");
        }

        assertThat(output.getErr()).contains("engine_app may not serve traffic: it has BYPASSRLS");
        assertThat(rows("SELECT count(*) FROM engine.tenancy_tenants")).containsExactly("0");
    }

    @Test
    void aWrongPasswordIsAFailureThatDoesNotPrintIt(CapturedOutput output) {
        assertThat(admin("create-tenant", "--engine.app.password=wrong-secret-value")).isEqualTo(EngineApplication.EXIT_FAILED);

        assertThat(output.getErr()).contains("password authentication failed");
        assertThat(output.toString()).doesNotContain("wrong-secret-value");
    }

    @Test
    void missingTheAddressIsAFailureThatNamesIt(CapturedOutput output) {
        assertThat(admin("create-tenant", "--engine.database.url=")).isEqualTo(EngineApplication.EXIT_FAILED);

        assertThat(output.getErr()).contains("ENGINE_DATABASE_URL is required");
    }

    @Test
    void theLauncherRunsTheRole(CapturedOutput output) {
        int before = output.getOut().length();

        assertThat(EngineApplication.launch(settings("create-tenant"))).isZero();
        assertThat(output.getOut().substring(before).strip()).matches("ten" + AN_ID);
    }
}
