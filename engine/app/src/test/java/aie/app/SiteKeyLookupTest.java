package aie.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import aie.adapters.storepg.PgDatabase;
import aie.adapters.storepg.TestDatabase;
import aie.core.tenancy.api.ApiKeyLookup.Owner;
import aie.core.tenancy.api.ApiKeys;
import aie.core.tenancy.api.Provisioning;
import aie.core.tenancy.api.Provisioning.IssuedKey;
import aie.core.tenancy.api.SiteKeyLookup;
import aie.core.tenancy.api.SiteKeyLookup.Checked;
import aie.core.tenancy.api.SiteKeys;
import aie.core.tenancy.internal.DatabaseProvisioning;
import aie.core.tenancy.internal.DatabaseSiteKeyLookup;
import aie.ports.DatabasePort;
import aie.ports.Tx;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The site key lookup on the real scripts, as engine_app with no tenant, the way a browser's request reaches it. The
 * privileges engine_system holds are checked for every system function in ApiKeyLookupTest.
 */
@Testcontainers
class SiteKeyLookupTest {

    private static final String SHOP = "https://shop.example.com";
    private static final String LOCAL = "http://localhost:3000";

    @Container
    private static final PostgreSQLContainer DATABASE = TestDatabase.container();

    private static PgDatabase database;
    private static SiteKeyLookup lookup;
    private static Provisioning provisioning;

    private record Issued(String tenantId, String applicationId, IssuedKey key) {

        Checked allowed() {
            return new Checked.Allowed(new Owner(key.keyId(), tenantId, applicationId));
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
        database = new PgDatabase(
                new DriverManagerDataSource(DATABASE.getJdbcUrl(), "engine_app", TestDatabase.APP_PASSWORD), Duration.ofSeconds(30));
        lookup = new DatabaseSiteKeyLookup(database);
        provisioning = new DatabaseProvisioning(database);
    }

    @BeforeEach
    void emptyTheTables() throws SQLException {
        TestDatabase.asAdmin(DATABASE, "TRUNCATE engine.tenancy_tenants CASCADE");
    }

    private static Issued issue(String... origins) {
        String tenantId = provisioning.createTenant("jp1", null);
        String applicationId = provisioning.createApplication(tenantId, "test", "internal");
        return new Issued(tenantId, applicationId, provisioning.createSiteKey(tenantId, applicationId, Arrays.asList(origins)));
    }

    private static List<String> rows(Statement statement, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                values.add(result.getString(1));
            }
        }
        return values;
    }

    @Test
    void aSiteKeyFromAListedOriginLeadsToItsApplicationWithoutATenant() {
        Issued issued = issue(SHOP, LOCAL);

        assertThat(lookup.check(issued.key().key(), SHOP)).isEqualTo(issued.allowed());
        assertThat(lookup.check(issued.key().key(), LOCAL)).isEqualTo(issued.allowed());
    }

    /** A browser writes the origin this way already; anything else is read the way SiteKeys.origin reads it. */
    @Test
    void theOriginIsReadAsABrowserWritesIt() {
        Issued issued = issue(SHOP);

        assertThat(lookup.check(issued.key().key(), "HTTPS://Shop.Example.com:443/")).isEqualTo(issued.allowed());
    }

    /** The key is known, so the answer is not Unknown: a page on another site embedded it. {@code null} is a sandboxed frame. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"https://evil.example", "http://shop.example.com", "https://shop.example.com:8443",
        "https://sub.shop.example.com", "https://example.com", "null", "https://shop.example.com.evil.example"})
    void anotherOriginIsRefusedForAKnownKey(String origin) {
        Issued issued = issue(SHOP, LOCAL);

        assertThat(lookup.check(issued.key().key(), origin)).isEqualTo(new Checked.OtherOrigin());
    }

    @Test
    void everySiteKeyLeadsToItsOwnApplication() {
        Issued first = issue(SHOP);
        Issued second = issue(SHOP);
        IssuedKey third = provisioning.createSiteKey(second.tenantId(), second.applicationId(), List.of(LOCAL));

        assertThat(lookup.check(first.key().key(), SHOP)).isEqualTo(first.allowed());
        assertThat(lookup.check(second.key().key(), SHOP)).isEqualTo(second.allowed());
        assertThat(lookup.check(third.key(), LOCAL))
                .isEqualTo(new Checked.Allowed(new Owner(third.keyId(), second.tenantId(), second.applicationId())));
        assertThat(lookup.check(third.key(), SHOP)).isEqualTo(new Checked.OtherOrigin());
    }

    @Test
    void aSiteKeyThatWasNeverIssuedIsUnknown() {
        issue(SHOP);

        assertThat(lookup.check(SiteKeys.issue("test", "jp1"), SHOP)).isEqualTo(new Checked.Unknown());
    }

    /** The hash covers the whole key, so a key one character away from a real one is a different key. */
    @Test
    void aSiteKeyOneCharacterAwayIsUnknown() {
        String key = issue(SHOP).key().key();
        String changed = key.substring(0, key.length() - 1) + (key.endsWith("A") ? "B" : "A");

        assertThat(SiteKeys.parse(changed)).isPresent();
        assertThat(lookup.check(changed, SHOP)).isEqualTo(new Checked.Unknown());
    }

    /** A revoked key gives nothing away, not even that it once existed, so it reads as unknown from any origin. */
    @Test
    void aRevokedSiteKeyIsUnknown() {
        Issued issued = issue(SHOP);
        assertThat(provisioning.revokeSiteKey(issued.tenantId(), issued.key().keyId())).isTrue();

        assertThat(lookup.check(issued.key().key(), SHOP)).isEqualTo(new Checked.Unknown());
        assertThat(lookup.check(issued.key().key(), "https://evil.example")).isEqualTo(new Checked.Unknown());
    }

    static List<String> malformedKeys() {
        String key = SiteKeys.issue("test", "jp1");
        return List.of(" ", "aie_site_test_jp1_short", key + "\n", " " + key, key.toUpperCase(), ApiKeys.issue("test", "jp1"),
                "aie_" + key.substring("aie_site_".length()));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @MethodSource("malformedKeys")
    void aMalformedSiteKeyIsUnknownWithoutAskingTheDatabase(String key) {
        DatabasePort refusing = new DatabasePort() {

            @Override
            public <T> T inTenantTx(String tenantId, Function<Tx, T> work) {
                throw new AssertionError("A malformed site key reached the database");
            }

            @Override
            public <T> T inSystemTx(Function<Tx, T> work) {
                throw new AssertionError("A malformed site key reached the database");
            }
        };

        assertThat(new DatabaseSiteKeyLookup(refusing).check(key, SHOP)).isEqualTo(new Checked.Unknown());
    }

    /** The new policy is for engine_system alone, so engine_app without a tenant still reads an empty table. */
    @Test
    void engineAppStillSeesNoSiteKeyWithoutATenant() {
        issue(SHOP);

        List<String> seen = database.inSystemTx(tx -> tx.query("SELECT site_key_id FROM tenancy_site_keys", row -> row.string("site_key_id")));

        assertThat(seen).isEmpty();
    }

    private static void onThisDatabase(String grant) throws SQLException {
        TestDatabase.asAdmin(DATABASE, "DO $$ BEGIN EXECUTE format('" + grant + "', current_database()); END $$");
    }

    /** Without its fixed search_path the function would read a table the caller made up (blueprint 7.6). */
    @Test
    void aTemporaryTableCannotStandInForTheSiteKeyTable() throws SQLException {
        String fake = SiteKeys.issue("test", "jp1");
        onThisDatabase("GRANT TEMPORARY ON DATABASE %I TO engine_app");
        try (Connection app = TestDatabase.connect(DATABASE, "engine_app", TestDatabase.APP_PASSWORD);
                Statement statement = app.createStatement()) {
            statement.execute("CREATE TEMP TABLE tenancy_site_keys (site_key_id text, tenant_id text, application_id text, "
                    + "key_hash text, origins text[], revoked_at timestamptz)");
            statement.execute("INSERT INTO tenancy_site_keys VALUES ('site_fake', 'ten_fake', 'app_fake', '"
                    + SiteKeys.hash(fake) + "', '{" + SHOP + "}', NULL)");

            assertThat(rows(statement, "SELECT tenant_id FROM tenancy_site_keys")).as("the trap works on a plain name")
                    .containsExactly("ten_fake");
            assertThat(rows(statement, "SELECT tenant_id FROM tenancy_find_site_key('" + SiteKeys.hash(fake) + "', '" + SHOP + "')"))
                    .isEmpty();
        } finally {
            onThisDatabase("REVOKE TEMPORARY ON DATABASE %I FROM engine_app");
        }
    }

    /** The connection opens outside the assertion, so a role that cannot log in does not pass for the wrong reason. */
    @Test
    void noOtherRoleMayCallTheFunction() throws SQLException {
        TestDatabase.asAdmin(DATABASE, """
                CREATE ROLE outsider LOGIN PASSWORD 'outsider-password';
                GRANT USAGE ON SCHEMA engine TO outsider;
                """);
        onThisDatabase("GRANT CONNECT ON DATABASE %I TO outsider");
        try (Connection outsider = TestDatabase.connect(DATABASE, "outsider", "outsider-password");
                Statement statement = outsider.createStatement()) {
            assertThatThrownBy(() -> statement.executeQuery("SELECT * FROM engine.tenancy_find_site_key('x', 'y')"))
                    .hasMessageContaining("permission denied for function tenancy_find_site_key");
        }
    }
}
