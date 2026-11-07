package aie.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;

import aie.adapters.storepg.PgDatabase;
import aie.adapters.storepg.TestDatabase;
import aie.core.tenancy.api.IdempotencyKeys;
import aie.core.tenancy.api.IdempotencyKeys.Claim;
import aie.core.tenancy.api.IdempotencyKeys.Key;
import aie.core.tenancy.internal.DatabaseIdempotencyKeys;
import aie.ports.DatabaseException;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** The real scripts as engine_app, with a new connection per transaction, so requests at the same moment really race. */
@Testcontainers
class IdempotencyKeysTest {

    private static final String TENANT_A = "ten_a";
    private static final String TENANT_B = "ten_b";
    private static final Key KEY = new Key(TENANT_A, "app_a", "key_1");
    private static final String REQUEST = "POST /v1/sessions hash-1";
    private static final String OTHER_REQUEST = "POST /v1/sessions hash-2";
    private static final Claim ACQUIRED = new Claim.Acquired();
    private static final Claim IN_USE = new Claim.InUse();
    private static final Claim REUSED = new Claim.Reused();

    @Container
    private static final PostgreSQLContainer DATABASE = TestDatabase.container();

    private static IdempotencyKeys keys;

    @BeforeAll
    static void createRolesAndMigrate() throws IOException, InterruptedException {
        assertThat(TestDatabase.createRoles(
                        DATABASE, "owner_password=" + TestDatabase.OWNER_PASSWORD, "app_password=" + TestDatabase.APP_PASSWORD)
                .getExitCode()).isZero();
        assertThat(MigrateRole.run(
                        "--engine.database.url=" + DATABASE.getJdbcUrl(), "--engine.owner.password=" + TestDatabase.OWNER_PASSWORD))
                .isZero();
        keys = new DatabaseIdempotencyKeys(new PgDatabase(
                new DriverManagerDataSource(DATABASE.getJdbcUrl(), "engine_app", TestDatabase.APP_PASSWORD), Duration.ofSeconds(30)));
    }

    @BeforeEach
    void startWithTwoApplicationsOfOneTenantAndOneOfAnother() throws SQLException {
        TestDatabase.asAdmin(DATABASE, """
                TRUNCATE engine.tenancy_tenants CASCADE;
                INSERT INTO engine.tenancy_tenants (tenant_id, cell_id) VALUES ('ten_a', 'jp1'), ('ten_b', 'jp1');
                INSERT INTO engine.tenancy_applications (application_id, tenant_id, environment, api_version, default_data_class)
                VALUES ('app_a', 'ten_a', 'test', '2026-11-05', 'internal'),
                       ('app_a2', 'ten_a', 'test', '2026-11-05', 'internal'),
                       ('app_b', 'ten_b', 'test', '2026-11-05', 'internal');
                """);
    }

    private static void change(String assignment) throws SQLException {
        TestDatabase.asAdmin(DATABASE, "UPDATE engine.tenancy_idempotency_keys SET " + assignment);
    }

    private static String column(String name) throws SQLException {
        return TestDatabase.firstColumn(DATABASE, "SELECT " + name + " FROM engine.tenancy_idempotency_keys").get(0);
    }

    private static Instant timeIn(String column) throws SQLException {
        return Instant.ofEpochSecond(Long.parseLong(
                TestDatabase.firstColumn(DATABASE, "SELECT extract(epoch FROM " + column + ")::bigint FROM engine.tenancy_idempotency_keys").get(0)));
    }

    @Test
    void theFirstRequestGetsTheKey() {
        assertThat(keys.claim(KEY, REQUEST)).isEqualTo(ACQUIRED);
    }

    @Test
    void aRetryWhileTheFirstRequestRunsIsTurnedAway() {
        keys.claim(KEY, REQUEST);

        assertThat(keys.claim(KEY, REQUEST)).isEqualTo(IN_USE);
    }

    @Test
    void aRetryAfterTheFirstRequestGetsItsAnswer() {
        keys.claim(KEY, REQUEST);
        keys.finish(KEY, 201, "{\"id\":\"conv_1\"}");

        assertThat(keys.claim(KEY, REQUEST)).isEqualTo(new Claim.Finished(201, "{\"id\":\"conv_1\"}"));
    }

    @Test
    void aFailureIsAnAnswerToo() {
        keys.claim(KEY, REQUEST);
        keys.finish(KEY, 500, "{\"code\":\"internal_error\"}");

        assertThat(keys.claim(KEY, REQUEST)).isEqualTo(new Claim.Finished(500, "{\"code\":\"internal_error\"}"));
    }

    @Test
    void anAnswerWithNoBodyReplaysAsOne() {
        keys.claim(KEY, REQUEST);
        keys.finish(KEY, 204, null);

        assertThat(keys.claim(KEY, REQUEST)).isEqualTo(new Claim.Finished(204, null));
    }

    @Test
    void aDifferentRequestWhileTheFirstRunsIsRefused() {
        keys.claim(KEY, REQUEST);

        assertThat(keys.claim(KEY, OTHER_REQUEST)).isEqualTo(REUSED);
    }

    @Test
    void aDifferentRequestAfterTheFirstIsRefused() {
        keys.claim(KEY, REQUEST);
        keys.finish(KEY, 201, "{}");

        assertThat(keys.claim(KEY, OTHER_REQUEST)).isEqualTo(REUSED);
    }

    @Test
    void aKeyBelongsToOneApplication() {
        keys.claim(KEY, REQUEST);

        assertThat(keys.claim(new Key(TENANT_A, "app_a2", KEY.value()), OTHER_REQUEST)).isEqualTo(ACQUIRED);
    }

    @Test
    void aKeyBelongsToOneTenant() {
        keys.claim(KEY, REQUEST);

        assertThat(keys.claim(new Key(TENANT_B, "app_b", KEY.value()), OTHER_REQUEST)).isEqualTo(ACQUIRED);
    }

    @Test
    void anAnswerGoesOnlyToItsOwnKey() {
        Key other = new Key(TENANT_A, "app_a2", KEY.value());
        keys.claim(KEY, REQUEST);
        keys.claim(other, REQUEST);

        keys.finish(KEY, 201, "{}");

        assertThat(keys.claim(other, REQUEST)).isEqualTo(IN_USE);
    }

    @Test
    void aKeyLastsADayAndALockFiveMinutes() throws SQLException {
        keys.claim(KEY, REQUEST);

        assertThat(timeIn("expires_at")).isCloseTo(Instant.now().plus(24, ChronoUnit.HOURS), within(1, ChronoUnit.MINUTES));
        assertThat(timeIn("locked_until")).isCloseTo(Instant.now().plus(5, ChronoUnit.MINUTES), within(1, ChronoUnit.MINUTES));
    }

    @Test
    void anExpiredKeyStartsOver() throws SQLException {
        keys.claim(KEY, REQUEST);
        keys.finish(KEY, 201, "{\"id\":\"old\"}");
        change("expires_at = now() - interval '1 second'");

        assertThat(keys.claim(KEY, OTHER_REQUEST)).isEqualTo(ACQUIRED);

        assertThat(keys.claim(KEY, OTHER_REQUEST)).isEqualTo(IN_USE);
        assertThat(column("response_status")).isNull();
        assertThat(column("response_body")).isNull();
        assertThat(timeIn("expires_at")).isCloseTo(Instant.now().plus(24, ChronoUnit.HOURS), within(1, ChronoUnit.MINUTES));
    }

    @Test
    void aKeyWhoseRequestDiedCanBeTakenOverOnce() throws SQLException {
        keys.claim(KEY, REQUEST);
        change("locked_until = now() - interval '1 second'");

        assertThat(keys.claim(KEY, REQUEST)).isEqualTo(ACQUIRED);

        assertThat(keys.claim(KEY, REQUEST)).isEqualTo(IN_USE);
        assertThat(timeIn("locked_until")).isCloseTo(Instant.now().plus(5, ChronoUnit.MINUTES), within(1, ChronoUnit.MINUTES));
    }

    @Test
    void aKeyWhoseRequestDiedStillRefusesADifferentRequest() throws SQLException {
        keys.claim(KEY, REQUEST);
        change("locked_until = now() - interval '1 second'");

        assertThat(keys.claim(KEY, OTHER_REQUEST)).isEqualTo(REUSED);
    }

    @Test
    void aFinishedKeyIsNeverTakenOver() throws SQLException {
        keys.claim(KEY, REQUEST);
        keys.finish(KEY, 201, "{}");
        change("locked_until = now() - interval '1 second'");

        assertThat(keys.claim(KEY, REQUEST)).isEqualTo(new Claim.Finished(201, "{}"));
    }

    @Test
    void theFirstAnswerStands() {
        keys.claim(KEY, REQUEST);
        keys.finish(KEY, 201, "{\"id\":\"first\"}");
        keys.finish(KEY, 500, "{\"id\":\"late\"}");

        assertThat(keys.claim(KEY, REQUEST)).isEqualTo(new Claim.Finished(201, "{\"id\":\"first\"}"));
    }

    @Test
    void anAnswerDoesNotMoveTheExpiry() throws SQLException {
        keys.claim(KEY, REQUEST);
        change("expires_at = now() + interval '1 hour'");
        Instant before = timeIn("expires_at");

        keys.finish(KEY, 201, "{}");

        assertThat(timeIn("expires_at")).isEqualTo(before);
    }

    @Test
    void requestsAtTheSameMomentGetTheKeyOnce() throws Exception {
        int requests = 8;
        CyclicBarrier start = new CyclicBarrier(requests);
        List<Future<Claim>> claims = new ArrayList<>();
        try (ExecutorService threads = Executors.newFixedThreadPool(requests)) {
            for (int i = 0; i < requests; i++) {
                claims.add(threads.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return keys.claim(KEY, REQUEST);
                }));
            }
            List<Claim> results = new ArrayList<>();
            for (Future<Claim> claim : claims) {
                results.add(claim.get());
            }
            assertThat(results).containsOnlyOnce(ACQUIRED).filteredOn(IN_USE::equals).hasSize(requests - 1);
        }
    }

    @Test
    void retriesAtTheSameMomentTakeOverADeadRequestOnce() throws Exception {
        keys.claim(KEY, REQUEST);
        change("locked_until = now() - interval '1 second'");
        int requests = 8;
        CyclicBarrier start = new CyclicBarrier(requests);
        List<Future<Claim>> claims = new ArrayList<>();
        try (ExecutorService threads = Executors.newFixedThreadPool(requests)) {
            for (int i = 0; i < requests; i++) {
                claims.add(threads.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return keys.claim(KEY, REQUEST);
                }));
            }
            List<Claim> results = new ArrayList<>();
            for (Future<Claim> claim : claims) {
                results.add(claim.get());
            }
            assertThat(results).containsOnlyOnce(ACQUIRED);
        }
    }

    /**
     * The race of the test above, made certain: another transaction takes the key over while this claim is deciding.
     * Without the row lock this claim would read the old state and take the key over a second time.
     */
    @Test
    void aClaimDecidesOnlyAfterAnotherTakeOverHasEnded() throws Exception {
        keys.claim(KEY, REQUEST);
        change("locked_until = now() - interval '1 second'");
        try (Connection other = TestDatabase.admin(DATABASE); Statement statement = other.createStatement();
                ExecutorService thread = Executors.newSingleThreadExecutor()) {
            other.setAutoCommit(false);
            statement.execute("SELECT 1 FROM engine.tenancy_idempotency_keys FOR UPDATE");
            Future<Claim> claim = thread.submit(() -> keys.claim(KEY, REQUEST));
            await().atMost(Duration.ofSeconds(10)).until(() -> TestDatabase.firstColumn(DATABASE,
                    "SELECT count(*) FROM pg_stat_activity WHERE usename = 'engine_app' AND wait_event_type = 'Lock'")
                    .equals(List.of("1")));
            statement.execute("UPDATE engine.tenancy_idempotency_keys SET locked_until = now() + interval '5 minutes'");
            other.commit();

            assertThat(claim.get(10, TimeUnit.SECONDS)).isEqualTo(IN_USE);
        }
    }

    @Test
    void aKeyNeedsAnExistingApplication() {
        assertThatThrownBy(() -> keys.claim(new Key(TENANT_A, "app_unknown", "key_1"), REQUEST))
                .isInstanceOf(DatabaseException.class)
                .hasStackTraceContaining("foreign key");
    }

    /** Foreign keys ignore row-level security, so this only holds because the tenant is part of the key. */
    @Test
    void aKeyCannotPointAtAnotherTenantsApplication() {
        assertThatThrownBy(() -> keys.claim(new Key(TENANT_A, "app_b", "key_1"), REQUEST))
                .isInstanceOf(DatabaseException.class)
                .hasStackTraceContaining("foreign key");
    }
}
