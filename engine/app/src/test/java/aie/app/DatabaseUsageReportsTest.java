package aie.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import aie.adapters.storepg.PgPool;
import aie.adapters.storepg.PgRuntime;
import aie.adapters.storepg.TestDatabase;
import aie.core.metering.api.UsageReports;
import aie.core.metering.internal.DatabaseUsageReports;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * M3-6-4: usage reports on the real scripts, as engine_app through a pool. A report is the exact sum of one application's
 * uses in its window, by model and in all, and a window left open is this month of the time zone the reports were given.
 * That a tenant reads only its own uses, TenantIsolationTest shows for every tenant table.
 */
@Testcontainers
class DatabaseUsageReportsTest {

    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");

    @Container
    private static final PostgreSQLContainer DATABASE = TestDatabase.container();

    private static PgPool pool;

    @BeforeAll
    static void createRolesMigrateAndOpenAPool() throws IOException, InterruptedException {
        assertThat(TestDatabase.createRoles(
                        DATABASE, "owner_password=" + TestDatabase.OWNER_PASSWORD, "app_password=" + TestDatabase.APP_PASSWORD)
                .getExitCode()).isZero();
        assertThat(MigrateRole.run(
                        "--engine.database.url=" + DATABASE.getJdbcUrl(), "--engine.owner.password=" + TestDatabase.OWNER_PASSWORD))
                .isZero();
        pool = PgRuntime.pool(DATABASE.getJdbcUrl(), TestDatabase.APP_PASSWORD, 2);
    }

    @AfterAll
    static void closeThePool() {
        pool.close();
    }

    @BeforeEach
    void startEmpty() throws SQLException {
        TestDatabase.asAdmin(DATABASE, "TRUNCATE engine.metering_usage");
    }

    /** Reports of this time zone, at this moment. */
    private static UsageReports reports(ZoneId zone, String now) {
        return new DatabaseUsageReports(pool, zone, Clock.fixed(Instant.parse(now), ZoneOffset.UTC));
    }

    private static int uses;

    /** Adds a use of the tenant's application at this time, as the superuser, so its time is the test's. */
    private static void used(String tenant, String application, String model, String time, long in, long out, String cost)
            throws SQLException {
        TestDatabase.asAdmin(DATABASE, """
                INSERT INTO engine.metering_usage (use_id, tenant_id, application_id, message_id, task, model, input_tokens,
                    output_tokens, cost_usd, created_at)
                VALUES ('use_%d', '%s', '%s', 'msg_a', 'customer_chat', '%s', %d, %d, %s, '%s')
                """.formatted(++uses, tenant, application, model, in, out, cost, time));
    }

    private static String line(UsageReports.Report report) {
        StringBuilder line = new StringBuilder(report.from() + " " + report.to() + " " + report.inputTokens() + " "
                + report.outputTokens() + " " + report.cost().toPlainString());
        report.byModel().forEach(use -> line.append(" | ").append(use.model()).append(' ').append(use.inputTokens()).append(' ')
                .append(use.outputTokens()).append(' ').append(use.cost().toPlainString()));
        return line.toString();
    }

    /**
     * A report sums the application's uses of its window by model, in the order of the models' IDs, and in all, every
     * token and every place of every cost. The window holds its first moment and leaves out its last. Another application
     * of the same tenant, and another tenant, are not in it.
     */
    @Test
    void aReportIsTheExactSumOfTheApplicationsUsesInItsWindow() throws SQLException {
        used("ten_a", "app_a", "dear", "2026-11-10T00:00:00Z", 1234, 567, "0.012207");
        used("ten_a", "app_a", "cheap", "2026-11-10T00:00:00Z", 2, 5, "0.000000375");
        used("ten_a", "app_a", "dear", "2026-11-11T00:00:00Z", 1, 1, "0.000018");
        used("ten_a", "app_a", "cheap", "2026-11-12T00:00:00Z", 3, 7, "0.000000001");
        used("ten_a", "app_a", "dear", "2026-11-09T23:59:59.999999Z", 100, 100, "1");
        used("ten_a", "app_a", "dear", "2026-11-13T00:00:00Z", 100, 100, "1");
        used("ten_a", "app_b", "dear", "2026-11-10T00:00:00Z", 100, 100, "1");
        used("ten_b", "app_a", "dear", "2026-11-10T00:00:00Z", 100, 100, "1");

        UsageReports.Report report = reports(TOKYO, "2026-11-20T00:00:00Z").report("ten_a", "app_a",
                Instant.parse("2026-11-10T00:00:00Z"), Instant.parse("2026-11-13T00:00:00Z"));

        assertThat(line(report)).isEqualTo("2026-11-10T00:00:00Z 2026-11-13T00:00:00Z 1240 580 0.012225376"
                + " | cheap 5 12 0.000000376 | dear 1235 568 0.012225");
    }

    /**
     * M3-6 decision 6 and decision 2: a window left open is this month in the time zone: in Tokyo from 2026-10-31T15:00Z
     * on and before 2026-11-30T15:00Z, which UTC would end and start 9 hours later. Either end may be given alone.
     */
    @Test
    void aWindowLeftOpenIsThisMonthInTheTimeZone() throws SQLException {
        used("ten_a", "app_a", "m", "2026-10-31T14:59:59.999999Z", 1, 0, "1");
        used("ten_a", "app_a", "m", "2026-10-31T15:00:00Z", 2, 0, "2");
        used("ten_a", "app_a", "m", "2026-11-30T14:59:59.999999Z", 4, 0, "4");
        used("ten_a", "app_a", "m", "2026-11-30T15:00:00Z", 8, 0, "8");
        UsageReports tokyo = reports(TOKYO, "2026-11-30T14:59:59Z");

        assertThat(line(tokyo.report("ten_a", "app_a", null, null)))
                .isEqualTo("2026-10-31T15:00:00Z 2026-11-30T15:00:00Z 6 0 6 | m 6 0 6");
        assertThat(line(reports(ZoneOffset.UTC, "2026-11-30T14:59:59Z").report("ten_a", "app_a", null, null)))
                .isEqualTo("2026-11-01T00:00:00Z 2026-12-01T00:00:00Z 12 0 12 | m 12 0 12");
        assertThat(line(reports(TOKYO, "2026-11-30T15:00:00Z").report("ten_a", "app_a", null, null)))
                .isEqualTo("2026-11-30T15:00:00Z 2026-12-31T15:00:00Z 8 0 8 | m 8 0 8");
        assertThat(line(tokyo.report("ten_a", "app_a", Instant.parse("2026-10-01T00:00:00Z"), null)))
                .isEqualTo("2026-10-01T00:00:00Z 2026-11-30T15:00:00Z 7 0 7 | m 7 0 7");
        assertThat(line(tokyo.report("ten_a", "app_a", null, Instant.parse("2026-11-30T14:59:59.999999Z"))))
                .isEqualTo("2026-10-31T15:00:00Z 2026-11-30T14:59:59.999999Z 2 0 2 | m 2 0 2");
    }

    /** A window without uses reports nothing used, at no cost, and no model. */
    @Test
    void aWindowWithoutUsesReportsNothing() {
        UsageReports.Report report = reports(TOKYO, "2026-11-20T00:00:00Z").report("ten_a", "app_a", null, null);

        assertThat(report.inputTokens()).isZero();
        assertThat(report.outputTokens()).isZero();
        assertThat(report.cost()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(report.byModel()).isEmpty();
    }

    /** A window ends after it starts, however its ends were given, and a report is of a tenant's application. */
    @Test
    void aWindowEndsAfterItStartsAndAReportIsOfAnApplication() {
        UsageReports tokyo = reports(TOKYO, "2026-11-20T00:00:00Z");
        String rule = "from must come before to; without from the window starts with this month, and without to it ends with this"
                + " month";

        assertThatThrownBy(() -> tokyo.report("ten_a", "app_a", Instant.parse("2026-11-10T00:00:00Z"), Instant.parse("2026-11-10T00:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(rule);
        assertThatThrownBy(() -> tokyo.report("ten_a", "app_a", Instant.parse("2026-11-30T15:00:00Z"), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(rule);
        assertThatThrownBy(() -> tokyo.report("ten_a", "app_a", null, Instant.parse("2026-10-31T15:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(rule);
        assertThatThrownBy(() -> tokyo.report(null, "app_a", null, null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A report is of a tenant's application");
        assertThatThrownBy(() -> tokyo.report("ten_a", null, null, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
