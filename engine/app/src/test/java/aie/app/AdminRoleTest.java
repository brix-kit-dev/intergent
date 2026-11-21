package aie.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import aie.adapters.storepg.PgRuntime;
import aie.adapters.storepg.TestDatabase;
import aie.core.tenancy.api.ApiKeys;
import aie.core.tenancy.api.ApiVersion;
import aie.core.tenancy.api.SiteKeys;
import aie.core.trust.api.Audit;
import aie.core.trust.api.DataClass;
import aie.core.trust.internal.DatabaseAudit;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the role as an operator would, against the real scripts, and reads the result as the superuser. The role
 * prints new keys, so {@link QuietOutput} keeps what it prints out of the CI log.
 */
@Testcontainers
@ExtendWith(QuietOutput.class)
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
        TestDatabase.asAdmin(DATABASE, "TRUNCATE engine.trust_audit_records");
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

    private static String[] issuedKey(CapturedOutput output, String environment) {
        String tenant = created(output, "create-tenant");
        String application = created(output, "create-application", "--tenant=" + tenant, "--environment=" + environment);
        return created(output, "create-key", "--tenant=" + tenant, "--application=" + application).split(" ");
    }

    @Test
    void createKeyPrintsTheKeyIdAndTheKeyAndKeepsOnlyItsHash(CapturedOutput output) throws SQLException {
        String tenant = created(output, "create-tenant");
        String application = created(output, "create-application", "--tenant=" + tenant);
        String[] printed = created(output, "create-key", "--tenant=" + tenant, "--application=" + application).split(" ");

        assertThat(printed).hasSize(2);
        assertThat(printed[0]).matches("key" + AN_ID);
        assertThat(printed[1]).matches("aie_test_jp1_[0-9A-Za-z]{40}");
        assertThat(rows("SELECT concat_ws(',', key_id, tenant_id, application_id, key_hash) FROM engine.tenancy_api_keys"))
                .containsExactly(String.join(",", printed[0], tenant, application, ApiKeys.hash(printed[1])));
    }

    @Test
    void aLiveApplicationGetsALiveKey(CapturedOutput output) {
        assertThat(issuedKey(output, "live")[1]).startsWith("aie_live_jp1_");
    }

    /** Every column of every table, as text: the secret part of the key appears in none of them. */
    @Test
    void theKeyItselfIsStoredNowhere(CapturedOutput output) throws SQLException {
        String secret = issuedKey(output, "test")[1].substring("aie_test_jp1_".length());

        for (String table : rows("SELECT relname FROM pg_class WHERE relnamespace = 'engine'::regnamespace AND relkind = 'r'")) {
            assertThat(rows("SELECT count(*) FROM engine." + table + " t WHERE t::text LIKE '%" + secret + "%'"))
                    .as(table).containsExactly("0");
        }
    }

    @Test
    void twoKeysOfOneApplicationDiffer(CapturedOutput output) throws SQLException {
        String tenant = created(output, "create-tenant");
        String application = created(output, "create-application", "--tenant=" + tenant);
        String first = created(output, "create-key", "--tenant=" + tenant, "--application=" + application);
        String second = created(output, "create-key", "--tenant=" + tenant, "--application=" + application);

        assertThat(first.split(" ")[1]).isNotEqualTo(second.split(" ")[1]);
        assertThat(rows("SELECT count(DISTINCT key_hash) FROM engine.tenancy_api_keys")).containsExactly("2");
    }

    @Test
    void aKeyNeedsAnApplicationOfThatTenant(CapturedOutput output) throws SQLException {
        String owner = created(output, "create-tenant");
        String application = created(output, "create-application", "--tenant=" + owner);
        String other = created(output, "create-tenant");

        assertThat(admin("create-key", "--tenant=" + other, "--application=" + application)).isEqualTo(EngineApplication.EXIT_FAILED);

        assertThat(output.getErr()).contains("Admin failed: Application " + application + " does not exist in tenant " + other);
        assertThat(rows("SELECT count(*) FROM engine.tenancy_api_keys")).containsExactly("0");
    }

    @Test
    void createKeyNeedsTheApplication(CapturedOutput output) {
        assertThat(admin("create-key", "--tenant=ten_x")).isEqualTo(EngineApplication.EXIT_USAGE);

        assertThat(output.getErr()).contains("create-key needs --application.");
    }

    /** The check behind "only a hash is kept": a key stored in the clear by mistake does not get in. */
    @Test
    void theDatabaseRefusesAKeyStoredInTheClear(CapturedOutput output) throws SQLException {
        String tenant = created(output, "create-tenant");
        String application = created(output, "create-application", "--tenant=" + tenant);

        assertThatThrownBy(() -> TestDatabase.asAdmin(DATABASE, """
                INSERT INTO engine.tenancy_api_keys (key_id, tenant_id, application_id, key_hash)
                VALUES ('key_x', '%s', '%s', '%s')""".formatted(tenant, application, ApiKeys.issue("test", "jp1"))))
                .hasMessageContaining("tenancy_api_keys_key_hash_check");
    }

    /** A request finds its key by the hash alone, so no two keys may share one, not even across tenants. */
    @Test
    void aHashBelongsToOneKeyOnly(CapturedOutput output) throws SQLException {
        String[] first = issuedKey(output, "test");
        String tenant = created(output, "create-tenant");
        String application = created(output, "create-application", "--tenant=" + tenant);

        assertThatThrownBy(() -> TestDatabase.asAdmin(DATABASE, """
                INSERT INTO engine.tenancy_api_keys (key_id, tenant_id, application_id, key_hash)
                VALUES ('key_x', '%s', '%s', '%s')""".formatted(tenant, application, ApiKeys.hash(first[1]))))
                .hasMessageContaining("tenancy_api_keys_key_hash_key");
    }

    /** Foreign keys ignore row-level security, so this only holds because the tenant is part of the key. */
    @Test
    void aKeyCannotPointAtAnotherTenantsApplication(CapturedOutput output) throws SQLException {
        String owner = created(output, "create-tenant");
        String application = created(output, "create-application", "--tenant=" + owner);
        String other = created(output, "create-tenant");

        assertThatThrownBy(() -> TestDatabase.asAdmin(DATABASE, """
                INSERT INTO engine.tenancy_api_keys (key_id, tenant_id, application_id, key_hash)
                VALUES ('key_x', '%s', '%s', '%s')""".formatted(other, application, ApiKeys.hash("x"))))
                .hasMessageContaining("violates foreign key constraint");
    }

    @Test
    void theLauncherRunsTheRole(CapturedOutput output) {
        int before = output.getOut().length();

        assertThat(EngineApplication.launch(settings("create-tenant"))).isZero();
        assertThat(output.getOut().substring(before).strip()).matches("ten" + AN_ID);
    }

    /** A tenant and an application of the environment, then a site key for these origins. Prints the ID and the key. */
    private static String[] issuedSiteKey(CapturedOutput output, String environment, String origins) {
        String tenant = created(output, "create-tenant");
        String application = created(output, "create-application", "--tenant=" + tenant, "--environment=" + environment);
        return created(output, "create-site-key", "--tenant=" + tenant, "--application=" + application, "--origins=" + origins)
                .split(" ");
    }

    /** The origins are kept as a browser writes them, in the order given, without repeats. */
    @Test
    void createSiteKeyPrintsTheIdAndTheKeyAndKeepsOnlyItsHash(CapturedOutput output) throws SQLException {
        String tenant = created(output, "create-tenant");
        String application = created(output, "create-application", "--tenant=" + tenant);
        String[] printed = created(output, "create-site-key", "--tenant=" + tenant, "--application=" + application,
                "--origins=https://Shop.Example.com/, http://localhost:3000,https://shop.example.com:443").split(" ");

        assertThat(printed).hasSize(2);
        assertThat(printed[0]).matches("site" + AN_ID);
        assertThat(printed[1]).matches("aie_site_test_jp1_[0-9A-Za-z]{40}");
        assertThat(rows("""
                SELECT concat_ws(',', site_key_id, tenant_id, application_id, key_hash, array_to_string(origins, ' '),
                       revoked_at IS NULL)
                FROM engine.tenancy_site_keys"""))
                .containsExactly(String.join(",", printed[0], tenant, application, SiteKeys.hash(printed[1]),
                        "https://shop.example.com http://localhost:3000", "t"));
    }

    @Test
    void aLiveApplicationGetsALiveSiteKey(CapturedOutput output) {
        assertThat(issuedSiteKey(output, "live", "https://shop.example.com")[1]).startsWith("aie_site_live_jp1_");
    }

    @Test
    void theSiteKeyItselfIsStoredNowhere(CapturedOutput output) throws SQLException {
        String secret = issuedSiteKey(output, "test", "https://shop.example.com")[1].substring("aie_site_test_jp1_".length());

        for (String table : rows("SELECT relname FROM pg_class WHERE relnamespace = 'engine'::regnamespace AND relkind = 'r'")) {
            assertThat(rows("SELECT count(*) FROM engine." + table + " t WHERE t::text LIKE '%" + secret + "%'"))
                    .as(table).containsExactly("0");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://shop.example.com", "https://shop.example.com/cart", "https://*.example.com",
        "shop.example.com", "https://shop.example.com,ftp://shop.example.com", "https://shop.example.com,"})
    void aSiteKeyListsOnlyExactOrigins(String origins, CapturedOutput output) throws SQLException {
        String tenant = created(output, "create-tenant");
        String application = created(output, "create-application", "--tenant=" + tenant);

        assertThat(admin("create-site-key", "--tenant=" + tenant, "--application=" + application, "--origins=" + origins))
                .isEqualTo(EngineApplication.EXIT_FAILED);

        assertThat(output.getErr()).contains("Admin failed: Not an origin a site key may list: ");
        assertThat(rows("SELECT count(*) FROM engine.tenancy_site_keys")).containsExactly("0");
    }

    @Test
    void aSiteKeyListsAtMostTenOrigins(CapturedOutput output) throws SQLException {
        String tenant = created(output, "create-tenant");
        String application = created(output, "create-application", "--tenant=" + tenant);
        String eleven = String.join(",", IntStream.rangeClosed(1, 11).mapToObj(i -> "https://shop" + i + ".example").toList());

        assertThat(admin("create-site-key", "--tenant=" + tenant, "--application=" + application, "--origins=" + eleven))
                .isEqualTo(EngineApplication.EXIT_FAILED);

        assertThat(output.getErr()).contains("Admin failed: A site key lists 1 to 10 origins, not 11");
        assertThat(rows("SELECT count(*) FROM engine.tenancy_site_keys")).containsExactly("0");
    }

    @Test
    void aSiteKeyNeedsAnApplicationOfThatTenant(CapturedOutput output) throws SQLException {
        String owner = created(output, "create-tenant");
        String application = created(output, "create-application", "--tenant=" + owner);
        String other = created(output, "create-tenant");

        assertThat(admin("create-site-key", "--tenant=" + other, "--application=" + application,
                "--origins=https://shop.example.com")).isEqualTo(EngineApplication.EXIT_FAILED);

        assertThat(output.getErr()).contains("Admin failed: Application " + application + " does not exist in tenant " + other);
        assertThat(rows("SELECT count(*) FROM engine.tenancy_site_keys")).containsExactly("0");
    }

    @Test
    void createSiteKeyNeedsOrigins(CapturedOutput output) {
        assertThat(admin("create-site-key", "--tenant=ten_x", "--application=app_x")).isEqualTo(EngineApplication.EXIT_USAGE);

        assertThat(output.getErr()).contains("create-site-key needs --origins.");
    }

    /** The row stays, with the moment it was revoked, and a key revoked once cannot be revoked again. */
    @Test
    void revokeSiteKeyPrintsTheIdAndKeepsTheRow(CapturedOutput output) throws SQLException {
        String tenant = created(output, "create-tenant");
        String application = created(output, "create-application", "--tenant=" + tenant);
        String siteKeyId = created(output, "create-site-key", "--tenant=" + tenant, "--application=" + application,
                "--origins=https://shop.example.com").split(" ")[0];

        assertThat(created(output, "revoke-site-key", "--tenant=" + tenant, "--site-key=" + siteKeyId)).isEqualTo(siteKeyId);
        assertThat(rows("SELECT count(*) FROM engine.tenancy_site_keys WHERE revoked_at IS NOT NULL")).containsExactly("1");

        assertThat(admin("revoke-site-key", "--tenant=" + tenant, "--site-key=" + siteKeyId)).isEqualTo(EngineApplication.EXIT_FAILED);
        assertThat(output.getErr()).contains("Admin failed: Site key " + siteKeyId + " is not an active key of tenant " + tenant);
    }

    /** Row-level security hides another tenant's site key, so it reads as missing and stays as it was. */
    @Test
    void aTenantCannotRevokeAnotherTenantsSiteKey(CapturedOutput output) throws SQLException {
        String siteKeyId = issuedSiteKey(output, "test", "https://shop.example.com")[0];
        String other = created(output, "create-tenant");

        assertThat(admin("revoke-site-key", "--tenant=" + other, "--site-key=" + siteKeyId)).isEqualTo(EngineApplication.EXIT_FAILED);

        assertThat(rows("SELECT count(*) FROM engine.tenancy_site_keys WHERE revoked_at IS NULL")).containsExactly("1");
    }

    /** The checks behind "only a hash" and "only origins": a value that slipped past the code does not get in. */
    @ParameterizedTest
    @ValueSource(strings = {
        "'%s', '{https://shop.example.com}'",
        "'0000000000000000000000000000000000000000000000000000000000000000', '{}'",
        "'0000000000000000000000000000000000000000000000000000000000000000', '{NULL}'",
        "'0000000000000000000000000000000000000000000000000000000000000000', '{https://shop.example.com,NULL}'",
        "'0000000000000000000000000000000000000000000000000000000000000000', '{https://shop.example.com/cart}'",
        "'0000000000000000000000000000000000000000000000000000000000000000', '{shop.example.com}'",
        "'0000000000000000000000000000000000000000000000000000000000000000', '{\"https://a.example b\"}'",
        "'0000000000000000000000000000000000000000000000000000000000000000', '{https://a.example,https://b.example,https://c.example,"
            + "https://d.example,https://e.example,https://f.example,https://g.example,https://h.example,https://i.example,"
            + "https://j.example,https://k.example}'"
    })
    void theDatabaseRefusesASiteKeyThatIsNotAHashOrOriginsThatAreNotOrigins(String values, CapturedOutput output) throws SQLException {
        String tenant = created(output, "create-tenant");
        String application = created(output, "create-application", "--tenant=" + tenant);

        assertThatThrownBy(() -> TestDatabase.asAdmin(DATABASE, """
                INSERT INTO engine.tenancy_site_keys (site_key_id, tenant_id, application_id, key_hash, origins)
                VALUES ('site_x', '%s', '%s', %s)""".formatted(tenant, application, values.formatted(SiteKeys.issue("test", "jp1")))))
                .hasMessageContaining("violates check constraint");
        assertThat(rows("SELECT count(*) FROM engine.tenancy_site_keys")).containsExactly("0");
    }

    @Test
    void theDatabaseTakesTenOriginsAsTheCodeWritesThem(CapturedOutput output) throws SQLException {
        String tenant = created(output, "create-tenant");
        String application = created(output, "create-application", "--tenant=" + tenant);
        String ten = String.join(",", IntStream.rangeClosed(1, 10).mapToObj(i -> "https://shop" + i + ".example:8443").toList());

        TestDatabase.asAdmin(DATABASE, """
                INSERT INTO engine.tenancy_site_keys (site_key_id, tenant_id, application_id, key_hash, origins)
                VALUES ('site_x', '%s', '%s', '%s', '{%s,http://localhost:3000}')""".formatted(
                tenant, application, "0".repeat(64), ten.substring(ten.indexOf(',') + 1)));

        assertThat(rows("SELECT cardinality(origins) FROM engine.tenancy_site_keys")).containsExactly("10");
    }

    /** What a command printed on standard output after this point, line by line. */
    private static List<String> printed(CapturedOutput output, int from) {
        return output.getOut().substring(from).lines().toList();
    }

    /** Adds an audit entry of a blocked input, with this ID and time, as the superuser, so its time is the test's. */
    private static void blockedAt(String tenant, String recordId, String time) throws SQLException {
        TestDatabase.asAdmin(DATABASE, """
                INSERT INTO engine.trust_audit_records (record_id, tenant_id, created_at, request_id, application_id, end_user_id,
                    conv_id, task, event, rule)
                VALUES ('%s', '%s', '%s', 'req_1', 'app_a', 'eu_a', 'conv_a', 'customer_chat', 'input_blocked', 'instruction_override')
                """.formatted(recordId, tenant, time));
    }

    /**
     * M3-5-2: audit prints the tenant's entries oldest first, one line of JSON each, its fields always in the same order,
     * with null for those it does not have, and nothing else. Another tenant's entries are not among them.
     */
    @Test
    void auditPrintsATenantsEntriesOldestFirstOneLineOfJsonEach(CapturedOutput output) throws SQLException {
        String tenant = created(output, "create-tenant");
        String other = created(output, "create-tenant");
        blockedAt(tenant, "aud_second", "2026-11-02T09:00:00+09:00");
        blockedAt(other, "aud_other", "2026-11-01T12:00:00Z");
        blockedAt(tenant, "aud_first", "2026-11-01T00:00:00.123456Z");
        new DatabaseAudit(PgRuntime.connect(DATABASE.getJdbcUrl(), TestDatabase.APP_PASSWORD), TestApi.AUDIT_MASTER_KEY).add(new Audit.Entry(null, tenant,
                "app_a", "eu_a", "conv_a", "msg_a", "customer_chat", Audit.Event.OUTPUT_BLOCKED, DataClass.SENSITIVE, "fake",
                "customer_chat/fake/v1", "prompt_leak"), new Audit.Content(null, "What went out before the stop"));
        int from = output.getOut().length();

        assertThat(admin("audit", "--tenant=" + tenant)).isZero();

        List<String> lines = printed(output, from);
        assertThat(lines).hasSize(3);
        assertThat(lines.subList(0, 2)).containsExactly(
                "{\"record_id\":\"aud_first\",\"created_at\":\"2026-11-01T00:00:00.123456Z\",\"request_id\":\"req_1\",\"tenant_id\":\""
                        + tenant + "\",\"application_id\":\"app_a\",\"end_user_id\":\"eu_a\",\"conversation_id\":\"conv_a\","
                        + "\"message_id\":null,\"task\":\"customer_chat\",\"event\":\"input_blocked\",\"data_class\":null,"
                        + "\"model\":null,\"prompt\":null,\"rule\":\"instruction_override\",\"question_hmac\":null,"
                        + "\"answer_hmac\":null,\"key_id\":null}",
                "{\"record_id\":\"aud_second\",\"created_at\":\"2026-11-02T00:00:00Z\",\"request_id\":\"req_1\",\"tenant_id\":\""
                        + tenant + "\",\"application_id\":\"app_a\",\"end_user_id\":\"eu_a\",\"conversation_id\":\"conv_a\","
                        + "\"message_id\":null,\"task\":\"customer_chat\",\"event\":\"input_blocked\",\"data_class\":null,"
                        + "\"model\":null,\"prompt\":null,\"rule\":\"instruction_override\",\"question_hmac\":null,"
                        + "\"answer_hmac\":null,\"key_id\":null}");
        Map<String, Object> last = JsonMapper.builder().build().readValue(lines.get(2), new TypeReference<LinkedHashMap<String, Object>>() {
        });
        assertThat(last.keySet()).containsExactly("record_id", "created_at", "request_id", "tenant_id", "application_id",
                "end_user_id", "conversation_id", "message_id", "task", "event", "data_class", "model", "prompt", "rule",
                "question_hmac", "answer_hmac", "key_id");
        assertThat(last.get("record_id").toString()).matches("aud" + AN_ID);
        assertThat(last).containsEntry("request_id", null).containsEntry("message_id", "msg_a")
                .containsEntry("event", "output_blocked").containsEntry("data_class", "sensitive").containsEntry("model", "fake")
                .containsEntry("prompt", "customer_chat/fake/v1").containsEntry("rule", "prompt_leak").containsEntry("question_hmac", null);
        assertThat(last.get("answer_hmac").toString()).matches("[0-9a-f]{64}");
        assertThat(last.get("key_id").toString()).matches("tk" + AN_ID);
        assertThat(lines.get(2)).doesNotContain("went out");
        assertThat(output.getOut().substring(from)).doesNotContain("aud_other");
    }

    /**
     * M3-5-2: --from and --to take RFC 3339 times with any offset, and the window holds the entries from the first on and
     * before the second; either may be left out. A tenant without entries in it prints nothing.
     */
    @Test
    void auditTakesAWindowOfRfc3339Times(CapturedOutput output) throws SQLException {
        String tenant = created(output, "create-tenant");
        blockedAt(tenant, "aud_1", "2026-11-01T00:00:00Z");
        blockedAt(tenant, "aud_2", "2026-11-02T00:00:00Z");
        blockedAt(tenant, "aud_3", "2026-11-03T00:00:00Z");

        assertThat(auditIds(output, "--tenant=" + tenant, "--from=2026-11-02T09:00:00+09:00", "--to=2026-11-03T00:00:00Z"))
                .containsExactly("aud_2");
        assertThat(auditIds(output, "--tenant=" + tenant, "--from=2026-11-02t00:00:00.000001z")).containsExactly("aud_3");
        assertThat(auditIds(output, "--tenant=" + tenant, "--to=2026-11-02T00:00:00.000001Z")).containsExactly("aud_1", "aud_2");
        assertThat(auditIds(output, "--tenant=" + tenant, "--from=2026-11-04T00:00:00Z")).isEmpty();
        assertThat(auditIds(output, "--tenant=ten_without_entries")).isEmpty();
    }

    private static List<String> auditIds(CapturedOutput output, String... options) {
        int from = output.getOut().length();
        String[] args = new String[options.length + 1];
        args[0] = "audit";
        System.arraycopy(options, 0, args, 1, options.length);
        assertThat(admin(args)).isZero();
        JsonMapper json = JsonMapper.builder().build();
        return printed(output, from).stream().map(line -> json.readValue(line, LinkedHashMap.class).get("record_id").toString())
                .toList();
    }

    /**
     * M3-5-2: a time that is not RFC 3339, or a window that does not end after it starts, is a usage error, before the
     * role connects anywhere: here the address names no database at all.
     */
    @ParameterizedTest
    @ValueSource(strings = {"--from=2026-11-21", "--from=2026-11-21T00:00:00", "--from=2026-11-21 00:00:00Z", "--to=2026-11-21T00:00Z",
        "--to=2026-02-30T00:00:00Z", "--to=1763683200", "--from=2026-11-21T00:00:00+0900", "--from=2026-11-21T00:00:00Z --to=2026-11-21T00:00:00Z",
        "--from=2026-11-21T09:00:00+09:00 --to=2026-11-21T00:00:00Z", "--from=2026-11-22T00:00:00Z --to=2026-11-21T00:00:00Z"})
    void aTimeThatIsNotRfc3339OrAWindowThatDoesNotEndAfterItStartsIsAUsageError(String options, CapturedOutput output) {
        int from = output.getOut().length();
        List<String> args = new ArrayList<>(List.of("audit", "--tenant=ten_x", "--engine.database.url=jdbc:postgresql://127.0.0.1:1/none"));
        args.addAll(Arrays.asList(options.split(" (?=--)")));

        assertThat(admin(args.toArray(String[]::new))).isEqualTo(EngineApplication.EXIT_USAGE);

        assertThat(output.getErr()).containsAnyOf("must be an RFC 3339 time, such as 2026-11-21T09:00:00+09:00, not 20",
                "must be an RFC 3339 time, such as 2026-11-21T09:00:00+09:00, not 17", "--from must come before --to.");
        assertThat(output.getErr()).contains("Usage: --role=admin <command>", "audit --tenant=<tenant id> [--from=<RFC 3339 time>]");
        assertThat(output.getOut().substring(from)).isEmpty();
    }

    /** M3-5-2: audit needs the tenant, and takes no other option. */
    @ParameterizedTest
    @ValueSource(strings = {"", "--application=app_a", "--tenant=ten_x --since=2026-11-21T00:00:00Z"})
    void auditNeedsItsTenantAndTakesOnlyItsOptions(String options, CapturedOutput output) {
        List<String> args = new ArrayList<>(List.of("audit"));
        if (!options.isEmpty()) {
            args.addAll(Arrays.asList(options.split(" ")));
        }

        assertThat(admin(args.toArray(String[]::new))).isEqualTo(EngineApplication.EXIT_USAGE);

        assertThat(output.getErr()).containsAnyOf("audit needs --tenant.", "audit does not take --application.",
                "audit does not take --since.");
    }
}
