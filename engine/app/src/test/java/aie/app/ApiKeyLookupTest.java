package aie.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import aie.adapters.storepg.PgDatabase;
import aie.adapters.storepg.TestDatabase;
import aie.core.tenancy.api.ApiKeyLookup;
import aie.core.tenancy.api.ApiKeyLookup.Owner;
import aie.core.tenancy.api.ApiKeys;
import aie.core.tenancy.api.Provisioning;
import aie.core.tenancy.api.Provisioning.IssuedKey;
import aie.core.tenancy.internal.DatabaseApiKeyLookup;
import aie.core.tenancy.internal.DatabaseProvisioning;
import aie.ports.DatabasePort;
import aie.ports.Tx;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The system path of blueprint 7.6 on the real scripts, as engine_app: a key leads to its owner without any tenant,
 * and the function behind it opens nothing else.
 */
@Testcontainers
class ApiKeyLookupTest {

    private static final String KEY_TABLE_PRIVILEGES = """
            SELECT c.relname || '.' || a.attname || ' ' || p.privilege
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
            CROSS JOIN unnest(ARRAY['SELECT', 'INSERT', 'UPDATE', 'REFERENCES']) AS p (privilege)
            WHERE n.nspname = 'engine' AND c.relkind IN ('r', 'p')
                AND has_column_privilege('engine_system', c.oid, a.attnum, p.privilege)
            UNION ALL
            SELECT c.relname || ' ' || p.privilege
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            CROSS JOIN unnest(ARRAY['DELETE', 'TRUNCATE', 'TRIGGER']) AS p (privilege)
            WHERE n.nspname = 'engine' AND c.relkind IN ('r', 'p')
                AND has_table_privilege('engine_system', c.oid, p.privilege)
            ORDER BY 1
            """;

    @Container
    private static final PostgreSQLContainer DATABASE = TestDatabase.container();

    private static PgDatabase database;
    private static ApiKeyLookup lookup;
    private static Provisioning provisioning;

    private record Issued(String tenantId, String applicationId, IssuedKey key) {

        Owner owner() {
            return new Owner(key.keyId(), tenantId, applicationId);
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
        lookup = new DatabaseApiKeyLookup(database);
        provisioning = new DatabaseProvisioning(database);
    }

    @BeforeEach
    void emptyTheTables() throws SQLException {
        TestDatabase.asAdmin(DATABASE, "TRUNCATE engine.tenancy_tenants CASCADE");
    }

    private static Issued issue() {
        String tenantId = provisioning.createTenant("jp1", null);
        String applicationId = provisioning.createApplication(tenantId, "test", "internal");
        return new Issued(tenantId, applicationId, provisioning.createKey(tenantId, applicationId));
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

    static List<String> malformedKeys() {
        String key = ApiKeys.issue("test", "jp1");
        return List.of(" ", "aie_test_jp1_short", key + "\n", " " + key, key.toUpperCase(), "Bearer " + key, "sk_live_" + key.substring(13));
    }

    @Test
    void aKeyLeadsToItsOwnerWithoutATenant() {
        Issued issued = issue();

        assertThat(lookup.find(issued.key().key())).contains(issued.owner());
    }

    @Test
    void everyKeyLeadsToItsOwnOwner() {
        Issued first = issue();
        Issued second = issue();
        IssuedKey third = provisioning.createKey(second.tenantId(), second.applicationId());

        assertThat(lookup.find(first.key().key())).contains(first.owner());
        assertThat(lookup.find(second.key().key())).contains(second.owner());
        assertThat(lookup.find(third.key())).contains(new Owner(third.keyId(), second.tenantId(), second.applicationId()));
    }

    @Test
    void aKeyThatWasNeverIssuedFindsNothing() {
        issue();

        assertThat(lookup.find(ApiKeys.issue("test", "jp1"))).isEmpty();
    }

    /** The hash covers the whole key, so a key one character away from a real one is a different key. */
    @Test
    void aKeyOneCharacterAwayFindsNothing() {
        String key = issue().key().key();
        String changed = key.substring(0, key.length() - 1) + (key.endsWith("A") ? "B" : "A");

        assertThat(ApiKeys.parse(changed)).isPresent();
        assertThat(lookup.find(changed)).isEmpty();
    }

    private static void setExpiry(Issued issued, String expiry) throws SQLException {
        TestDatabase.asAdmin(DATABASE, "UPDATE engine.tenancy_api_keys SET expires_at = " + expiry
                + " WHERE key_id = '" + issued.key().keyId() + "'");
    }

    @Test
    void anExpiredKeyFindsNothing() throws SQLException {
        Issued issued = issue();
        setExpiry(issued, "now() - interval '1 second'");

        assertThat(lookup.find(issued.key().key())).isEmpty();
    }

    @Test
    void aKeyThatExpiresLaterStillWorks() throws SQLException {
        Issued issued = issue();
        setExpiry(issued, "now() + interval '1 hour'");

        assertThat(lookup.find(issued.key().key())).contains(issued.owner());
    }

    /** The function asks for keys in use only, so a revoked key leads nowhere, whoever revoked it. */
    @Test
    void aRevokedKeyFindsNothing() throws SQLException {
        Issued issued = issue();
        TestDatabase.asAdmin(DATABASE,
                "UPDATE engine.tenancy_api_keys SET revoked_at = now() WHERE key_id = '" + issued.key().keyId() + "'");

        assertThat(lookup.find(issued.key().key())).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @MethodSource("malformedKeys")
    void aMalformedKeyFindsNothingWithoutAskingTheDatabase(String key) {
        DatabasePort refusing = new DatabasePort() {

            @Override
            public <T> T inTenantTx(String tenantId, Function<Tx, T> work) {
                throw new AssertionError("A malformed key reached the database");
            }

            @Override
            public <T> T inSystemTx(Function<Tx, T> work) {
                throw new AssertionError("A malformed key reached the database");
            }
        };

        assertThat(new DatabaseApiKeyLookup(refusing).find(key)).isEmpty();
    }

    private static List<String> keysSeenWithoutATenant() {
        return database.inSystemTx(tx -> tx.query("SELECT key_id FROM tenancy_api_keys", row -> row.string("key_id")));
    }

    /** The new policy is for engine_system alone, so engine_app without a tenant still reads an empty table. */
    @Test
    void engineAppStillSeesNoKeyWithoutATenant() {
        issue();

        assertThat(keysSeenWithoutATenant()).isEmpty();
    }

    /** Blueprint 7.6: engine_system reads only the columns its functions need, and writes nothing, on every table. */
    @Test
    void engineSystemMayOnlyReadWhatTheFunctionNeeds() throws SQLException {
        assertThat(TestDatabase.firstColumn(DATABASE, KEY_TABLE_PRIVILEGES)).containsExactly(
                "tenancy_api_keys.application_id SELECT",
                "tenancy_api_keys.expires_at SELECT",
                "tenancy_api_keys.key_hash SELECT",
                "tenancy_api_keys.key_id SELECT",
                "tenancy_api_keys.revoked_at SELECT",
                "tenancy_api_keys.tenant_id SELECT",
                "tenancy_applications.application_id SELECT",
                "tenancy_applications.environment SELECT",
                "tenancy_applications.tenant_id SELECT",
                "tenancy_site_keys.application_id SELECT",
                "tenancy_site_keys.key_hash SELECT",
                "tenancy_site_keys.origins SELECT",
                "tenancy_site_keys.revoked_at SELECT",
                "tenancy_site_keys.site_key_id SELECT",
                "tenancy_site_keys.tenant_id SELECT",
                "tenancy_tenants.parent_tenant_id SELECT",
                "tenancy_tenants.tenant_id SELECT");
    }

    private static void onThisDatabase(String grant) throws SQLException {
        TestDatabase.asAdmin(DATABASE, "DO $$ BEGIN EXECUTE format('" + grant + "', current_database()); END $$");
    }

    /**
     * PostgreSQL looks in the session's temporary tables first, so without its fixed search_path the function would
     * read a table the caller made up (blueprint 7.6). The database already refuses temporary tables to engine_app
     * ({@code DatabaseRolesTest}). This test lifts that first layer, so it checks the function's own defence.
     */
    @Test
    void aTemporaryTableCannotStandInForTheKeyTable() throws SQLException {
        String fake = ApiKeys.issue("test", "jp1");
        onThisDatabase("GRANT TEMPORARY ON DATABASE %I TO engine_app");
        try (Connection app = TestDatabase.connect(DATABASE, "engine_app", TestDatabase.APP_PASSWORD);
                Statement statement = app.createStatement()) {
            statement.execute("CREATE TEMP TABLE tenancy_api_keys (key_id text, tenant_id text, application_id text, key_hash text)");
            statement.execute("INSERT INTO tenancy_api_keys VALUES ('key_fake', 'ten_fake', 'app_fake', '" + ApiKeys.hash(fake) + "')");

            assertThat(rows(statement, "SELECT tenant_id FROM tenancy_api_keys")).as("the trap works on a plain name")
                    .containsExactly("ten_fake");
            assertThat(rows(statement, "SELECT tenant_id FROM tenancy_find_api_key('" + ApiKeys.hash(fake) + "')")).isEmpty();
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
            assertThatThrownBy(() -> statement.executeQuery("SELECT * FROM engine.tenancy_find_api_key('x')"))
                    .hasMessageContaining("permission denied for function tenancy_find_api_key");
        }
    }
}
