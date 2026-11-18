package aie.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import aie.adapters.storepg.PgDatabase;
import aie.adapters.storepg.TestDatabase;
import aie.core.tenancy.api.ApiKeyLookup.Owner;
import aie.core.tenancy.api.EndUsers;
import aie.core.tenancy.api.Provisioning;
import aie.core.tenancy.internal.DatabaseEndUsers;
import aie.core.tenancy.internal.DatabaseProvisioning;
import aie.ports.DatabaseException;
import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** End users on the real scripts, as engine_app, with a new connection per transaction so that races are real. */
@Testcontainers
class EndUsersTest {

    @Container
    private static final PostgreSQLContainer DATABASE = TestDatabase.container();

    private static EndUsers endUsers;
    private static Provisioning provisioning;

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
        endUsers = new DatabaseEndUsers(database);
        provisioning = new DatabaseProvisioning(database);
    }

    @BeforeEach
    void emptyTheTables() throws SQLException {
        TestDatabase.asAdmin(DATABASE, "TRUNCATE engine.tenancy_tenants CASCADE");
    }

    /** A caller of a new tenant's application; the key ID plays no part here. */
    private static Owner newApplication() {
        String tenant = provisioning.createTenant("jp1", null);
        return new Owner("key_unused", tenant, provisioning.createApplication(tenant, "test", "internal"));
    }

    private static Owner anotherApplicationOf(Owner caller) {
        return new Owner("key_unused", caller.tenantId(), provisioning.createApplication(caller.tenantId(), "test", "internal"));
    }

    private static List<String> rows() throws SQLException {
        return TestDatabase.firstColumn(DATABASE, "SELECT end_user_id || ' ' || external_id FROM engine.tenancy_end_users ORDER BY 1");
    }

    @Test
    void theSameExternalIdIsAlwaysTheSameEndUser() throws SQLException {
        Owner caller = newApplication();

        String first = endUsers.resolve(caller, "user-42");
        String again = endUsers.resolve(caller, "user-42");

        assertThat(again).isEqualTo(first);
        assertThat(rows()).containsExactly(first + " user-42");
    }

    @Test
    void differentExternalIdsAreDifferentEndUsers() {
        Owner caller = newApplication();

        assertThat(endUsers.resolve(caller, "user-1")).isNotEqualTo(endUsers.resolve(caller, "user-2"));
    }

    /** Blueprint 7.1: an end user belongs to one application, so the same host ID in another one is someone else. */
    @Test
    void eachApplicationHasItsOwnEndUsers() {
        Owner caller = newApplication();

        assertThat(endUsers.resolve(caller, "user-42")).isNotEqualTo(endUsers.resolve(anotherApplicationOf(caller), "user-42"));
    }

    @Test
    void eachTenantHasItsOwnEndUsers() {
        assertThat(endUsers.resolve(newApplication(), "user-42")).isNotEqualTo(endUsers.resolve(newApplication(), "user-42"));
    }

    /** Random and typed, so it says nothing about the person behind it. */
    @Test
    void anEndUserIdIsRandomAndTyped() {
        String endUser = endUsers.resolve(newApplication(), "+81-90-1234-5678");

        assertThat(endUser).matches("eu_[0-9A-HJKMNP-TV-Z]{26}").doesNotContain("1234");
    }

    /** Eight requests bring a new ID at the same moment; the unique key leaves one end user for all of them. */
    @Test
    void requestsAtTheSameMomentGetOneEndUser() throws Exception {
        Owner caller = newApplication();
        CyclicBarrier together = new CyclicBarrier(8);
        List<String> seen = new ArrayList<>();
        try (ExecutorService requests = Executors.newFixedThreadPool(8)) {
            List<Future<String>> running = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                running.add(requests.submit(() -> {
                    together.await();
                    return endUsers.resolve(caller, "user-at-once");
                }));
            }
            for (Future<String> request : running) {
                seen.add(request.get());
            }
        }

        assertThat(new HashSet<>(seen)).hasSize(1);
        assertThat(rows()).hasSize(1);
    }

    /** The contract allows 1 to 255 characters; the database keeps that limit even if a caller does not. */
    @Test
    void theDatabaseRefusesAnEmptyOrAnOverlongExternalId() {
        Owner caller = newApplication();

        assertThat(endUsers.resolve(caller, "u".repeat(255))).startsWith("eu_");
        assertThatThrownBy(() -> endUsers.resolve(caller, "")).isInstanceOf(DatabaseException.class);
        assertThatThrownBy(() -> endUsers.resolve(caller, "u".repeat(256))).isInstanceOf(DatabaseException.class);
    }

    /** The foreign key carries the tenant, so a caller naming another tenant's application gets nothing there. */
    @Test
    void anApplicationOfAnotherTenantGetsNoEndUser() throws SQLException {
        Owner mine = newApplication();
        Owner theirs = newApplication();

        assertThatThrownBy(() -> endUsers.resolve(new Owner("key_unused", mine.tenantId(), theirs.applicationId()), "user-42"))
                .isInstanceOf(DatabaseException.class);
        assertThat(rows()).isEmpty();
    }
    /** Every end user as its ID, tenant, application and whether it lacks a host ID, oldest first. */
    private static List<String> allRows() throws SQLException {
        return TestDatabase.firstColumn(DATABASE, """
                SELECT end_user_id || ' ' || tenant_id || ' ' || application_id || ' ' || (external_id IS NULL)::text
                FROM engine.tenancy_end_users ORDER BY created_at, end_user_id""");
    }

    /** One visitor's sessions cannot be linked, because each anonymous session is a new end user without a host ID. */
    @Test
    void eachAnonymousSessionIsANewEndUserWithoutAHostId() throws SQLException {
        Owner caller = newApplication();

        String first = endUsers.anonymous(caller);
        String second = endUsers.anonymous(caller);

        assertThat(first).matches("eu_[0-9A-HJKMNP-TV-Z]{26}");
        assertThat(second).isNotEqualTo(first);
        assertThat(allRows()).containsExactlyInAnyOrder(
                first + " " + caller.tenantId() + " " + caller.applicationId() + " true",
                second + " " + caller.tenantId() + " " + caller.applicationId() + " true");
    }

    /** The lookup by the host's ID compares it with the column, so it never lands on an anonymous end user. */
    @Test
    void theHostsIdNeverFindsAnAnonymousEndUser() throws SQLException {
        Owner caller = newApplication();
        String anonymous = endUsers.anonymous(caller);

        String known = endUsers.resolve(caller, "user-42");

        assertThat(known).isNotEqualTo(anonymous);
        assertThat(endUsers.resolve(caller, "user-42")).isEqualTo(known);
        assertThat(allRows()).hasSize(2);
    }

    /** Without an ID, resolve would make an anonymous end user and then fail to find it, so it refuses first. */
    @Test
    void resolvingNeedsTheHostsId() throws SQLException {
        Owner caller = newApplication();

        assertThatThrownBy(() -> endUsers.resolve(caller, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("An end user found by the host's ID needs the ID");
        assertThat(allRows()).isEmpty();
    }

    /** Point 37: the database cannot store a NUL, so resolve refuses an ID with one itself, with the reason. */
    @Test
    void aHostsIdWithANulIsRefusedBeforeTheDatabase() throws SQLException {
        Owner caller = newApplication();

        assertThatThrownBy(() -> endUsers.resolve(caller, "host\0user"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A host's ID cannot hold a NUL character");
        assertThat(allRows()).isEmpty();
    }

    @Test
    void anAnonymousEndUserBelongsToTheCallersTenantOnly() throws SQLException {
        Owner mine = newApplication();
        Owner theirs = newApplication();

        assertThatThrownBy(() -> endUsers.anonymous(new Owner("key_unused", mine.tenantId(), theirs.applicationId())))
                .isInstanceOf(DatabaseException.class);
        assertThat(allRows()).isEmpty();
    }
}
