package aie.app;

import static org.assertj.core.api.Assertions.assertThat;

import aie.adapters.storepg.PgDatabase;
import aie.adapters.storepg.TestDatabase;
import aie.core.tenancy.api.ApiKeyLookup;
import aie.core.tenancy.api.ApiKeyLookup.Owner;
import aie.core.tenancy.api.ApiKeyManagement;
import aie.core.tenancy.api.Provisioning;
import aie.core.tenancy.api.Provisioning.IssuedKey;
import aie.core.tenancy.internal.DatabaseApiKeyLookup;
import aie.core.tenancy.internal.DatabaseApiKeyManagement;
import aie.core.tenancy.internal.DatabaseProvisioning;
import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Revoking keys on the real scripts, as engine_app, the way DELETE /v1/api-keys/{key_id} does it. */
@Testcontainers
class ApiKeyManagementTest {

    @Container
    private static final PostgreSQLContainer DATABASE = TestDatabase.container();

    private static ApiKeyLookup lookup;
    private static ApiKeyManagement management;
    private static Provisioning provisioning;

    /** A key as its caller sees it: the key itself and whose it is. */
    private record Held(String key, Owner owner) {
    }

    @BeforeAll
    static void createRolesAndMigrate() throws IOException, InterruptedException {
        assertThat(TestDatabase.createRoles(
                        DATABASE, "owner_password=" + TestDatabase.OWNER_PASSWORD, "app_password=" + TestDatabase.APP_PASSWORD)
                .getExitCode()).isZero();
        assertThat(MigrateRole.run(
                        "--engine.database.url=" + DATABASE.getJdbcUrl(), "--engine.owner.password=" + TestDatabase.OWNER_PASSWORD))
                .isZero();
        PgDatabase database = new PgDatabase(
                new DriverManagerDataSource(DATABASE.getJdbcUrl(), "engine_app", TestDatabase.APP_PASSWORD), Duration.ofSeconds(30));
        lookup = new DatabaseApiKeyLookup(database);
        management = new DatabaseApiKeyManagement(database);
        provisioning = new DatabaseProvisioning(database);
    }

    @BeforeEach
    void emptyTheTables() throws SQLException {
        TestDatabase.asAdmin(DATABASE, "TRUNCATE engine.tenancy_tenants CASCADE");
    }

    private static String newApplication(String tenantId) {
        return provisioning.createApplication(tenantId, "test", "internal");
    }

    private static Held newKey(String tenantId, String applicationId) {
        IssuedKey issued = provisioning.createKey(tenantId, applicationId);
        return new Held(issued.key(), new Owner(issued.keyId(), tenantId, applicationId));
    }

    private static Held newKeyOfANewTenant() {
        String tenantId = provisioning.createTenant("jp1", null);
        return newKey(tenantId, newApplication(tenantId));
    }

    private static boolean works(Held held) {
        return lookup.find(held.key()).isPresent();
    }

    private static String revokedAt(Held held) throws SQLException {
        return TestDatabase.firstColumn(DATABASE,
                "SELECT revoked_at::text FROM engine.tenancy_api_keys WHERE key_id = '" + held.owner().keyId() + "'").get(0);
    }

    @Test
    void aRevokedKeyStopsWorkingAtOnce() {
        Held held = newKeyOfANewTenant();
        assertThat(works(held)).isTrue();

        assertThat(management.revoke(held.owner(), held.owner().keyId())).isTrue();

        assertThat(works(held)).isFalse();
    }

    @Test
    void aKeyRevokesAnotherKeyOfItsApplicationAndThatOneAlone() {
        Held first = newKeyOfANewTenant();
        Held second = newKey(first.owner().tenantId(), first.owner().applicationId());

        assertThat(management.revoke(first.owner(), second.owner().keyId())).isTrue();

        assertThat(works(second)).isFalse();
        assertThat(works(first)).isTrue();
    }

    /** A leaked key must always be stoppable, so the last one goes too, and admin issues the next. */
    @Test
    void theLastKeyOfAnApplicationCanBeRevoked() {
        Held only = newKeyOfANewTenant();

        assertThat(management.revoke(only.owner(), only.owner().keyId())).isTrue();

        assertThat(works(only)).isFalse();
    }

    @Test
    void aKeyIsRevokedOnce() {
        Held held = newKeyOfANewTenant();
        Held other = newKey(held.owner().tenantId(), held.owner().applicationId());
        management.revoke(other.owner(), held.owner().keyId());

        assertThat(management.revoke(other.owner(), held.owner().keyId())).isFalse();
    }

    @Test
    void aKeyOfAnotherApplicationOfTheTenantReadsAsMissing() {
        Held mine = newKeyOfANewTenant();
        Held theirs = newKey(mine.owner().tenantId(), newApplication(mine.owner().tenantId()));

        assertThat(management.revoke(mine.owner(), theirs.owner().keyId())).isFalse();

        assertThat(works(theirs)).isTrue();
    }

    @Test
    void aKeyOfAnotherTenantReadsAsMissing() {
        Held mine = newKeyOfANewTenant();
        Held theirs = newKeyOfANewTenant();

        assertThat(management.revoke(mine.owner(), theirs.owner().keyId())).isFalse();

        assertThat(works(theirs)).isTrue();
    }

    /** The caller names its own tenant and application, so a forged pair cannot reach across tenants either. */
    @Test
    void aCallerNamingAnotherTenantsApplicationReachesNothing() {
        Held mine = newKeyOfANewTenant();
        Held theirs = newKeyOfANewTenant();
        Owner forged = new Owner(mine.owner().keyId(), mine.owner().tenantId(), theirs.owner().applicationId());

        assertThat(management.revoke(forged, theirs.owner().keyId())).isFalse();

        assertThat(works(theirs)).isTrue();
    }

    @Test
    void anUnknownKeyReadsAsMissing() {
        Held mine = newKeyOfANewTenant();

        assertThat(management.revoke(mine.owner(), "key_unknown")).isFalse();
    }

    /** The row stays, so the key can still be traced, with the moment it stopped working. */
    @Test
    void aRevokedKeyKeepsItsRowWithTheTimeOfRevocation() throws SQLException {
        Held held = newKeyOfANewTenant();
        assertThat(revokedAt(held)).isNull();
        Instant before = Instant.now();

        management.revoke(held.owner(), held.owner().keyId());

        assertThat(TestDatabase.firstColumn(DATABASE, "SELECT count(*) FROM engine.tenancy_api_keys")).containsExactly("1");
        assertThat(Instant.parse(TestDatabase.firstColumn(DATABASE,
                        "SELECT to_char(revoked_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"') FROM engine.tenancy_api_keys")
                .get(0))).isBetween(before.minusSeconds(5), Instant.now().plusSeconds(5));
    }
}
