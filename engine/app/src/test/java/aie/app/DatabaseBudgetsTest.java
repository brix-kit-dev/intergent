package aie.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import aie.adapters.storepg.PgPool;
import aie.adapters.storepg.PgRuntime;
import aie.adapters.storepg.TestDatabase;
import aie.core.metering.api.Budgets;
import aie.core.metering.internal.DatabaseBudgets;
import aie.ports.DatabaseException;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
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
 * M3-6-2: budgets on the real scripts, as engine_app through a pool. A tenant's limit is its own or the cell's default,
 * and what it spent is the exact sum of its uses of this month, a month of the time zone the budgets were given. That
 * each tenant reads and changes only its own limit, and deletes none, TenantIsolationTest shows for every tenant table.
 */
@Testcontainers
class DatabaseBudgetsTest {

    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final BigDecimal DEFAULT = new BigDecimal("50");

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
        TestDatabase.asAdmin(DATABASE, "TRUNCATE engine.metering_usage, engine.metering_budgets");
    }

    /** Budgets of this time zone, at this moment. */
    private static Budgets budgets(ZoneId zone, String now) {
        return new DatabaseBudgets(pool, zone, DEFAULT, Clock.fixed(Instant.parse(now), ZoneOffset.UTC));
    }

    /** Adds a use of the tenant at this time, as the superuser, so its time is the test's. */
    private static void usedAt(String tenant, String time, String cost) throws SQLException {
        TestDatabase.asAdmin(DATABASE, """
                INSERT INTO engine.metering_usage (use_id, tenant_id, application_id, message_id, task, model, input_tokens,
                    output_tokens, cost_usd, created_at)
                VALUES ('use_%s_%s', '%s', 'app_a', 'msg_a', 'customer_chat', 'fake', 1, 1, %s, '%s')
                """.formatted(tenant, time.replaceAll("[^0-9]", ""), tenant, cost, time));
    }

    /** M3-6 decision 4: a tenant without a limit of its own has the cell's default, and a tenant without uses spent 0. */
    @Test
    void aTenantWithoutALimitOfItsOwnHasTheDefault() {
        Budgets.Standing standing = budgets(TOKYO, "2026-11-20T00:00:00Z").standing("ten_a");

        assertThat(standing.month()).isEqualTo(YearMonth.of(2026, 11));
        assertThat(standing.limit()).isEqualTo(DEFAULT);
        assertThat(standing.own()).isFalse();
        assertThat(standing.spent()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(standing.allowsACall()).isTrue();
    }

    /**
     * M3-6 decision 2: what a tenant spent is the exact sum of its uses from the first of this month at midnight in the time
     * zone on, and before the first of the next. In Tokyo, 2026-10-31T15:00Z is the first moment of November and
     * 2026-11-30T15:00Z the first of December, so the month changes then too; in UTC it changes 9 hours later. Another
     * tenant's uses are not its own.
     */
    @Test
    void whatATenantSpentIsTheExactSumOfItsUsesOfThisMonthInTheTimeZone() throws SQLException {
        usedAt("ten_a", "2026-10-31T14:59:59.999999Z", "1");
        usedAt("ten_a", "2026-10-31T15:00:00Z", "0.000002");
        usedAt("ten_a", "2026-11-15T00:00:00Z", "0.012207");
        usedAt("ten_a", "2026-11-30T14:59:59.999999Z", "0.000000375");
        usedAt("ten_a", "2026-11-30T15:00:00Z", "10");
        usedAt("ten_b", "2026-11-15T00:00:00Z", "7");

        Budgets.Standing november = budgets(TOKYO, "2026-11-30T14:59:59.999999Z").standing("ten_a");
        Budgets.Standing december = budgets(TOKYO, "2026-11-30T15:00:00Z").standing("ten_a");
        Budgets.Standing utc = budgets(ZoneOffset.UTC, "2026-11-30T15:00:00Z").standing("ten_a");

        assertThat(november.month()).isEqualTo(YearMonth.of(2026, 11));
        assertThat(november.spent().toPlainString()).isEqualTo("0.012209375");
        assertThat(december.month()).isEqualTo(YearMonth.of(2026, 12));
        assertThat(december.spent()).isEqualByComparingTo("10");
        assertThat(utc.month()).isEqualTo(YearMonth.of(2026, 11));
        assertThat(utc.spent().toPlainString()).isEqualTo("10.012207375");
        assertThat(budgets(TOKYO, "2026-11-20T00:00:00Z").standing("ten_b").spent()).isEqualByComparingTo("7");
    }

    /**
     * Decision 6 of M3: a tenant's own limit holds for it in place of the default, exactly as it was set, and setting it
     * again changes it. Every other tenant keeps the default. A limit of 0 allows no call.
     */
    @Test
    void aTenantsOwnLimitHoldsInPlaceOfTheDefaultAndChanges() throws SQLException {
        Budgets budgets = budgets(TOKYO, "2026-11-20T00:00:00Z");
        usedAt("ten_a", "2026-11-15T00:00:00Z", "12.5");

        budgets.set("ten_a", new BigDecimal("12.50"));
        Budgets.Standing spent = budgets.standing("ten_a");
        budgets.set("ten_a", new BigDecimal("30.000001"));
        Budgets.Standing raised = budgets.standing("ten_a");
        budgets.set("ten_c", BigDecimal.ZERO);

        assertThat(spent.limit().toPlainString()).isEqualTo("12.50");
        assertThat(spent.own()).isTrue();
        assertThat(spent.allowsACall()).isFalse();
        assertThat(raised.limit().toPlainString()).isEqualTo("30.000001");
        assertThat(raised.allowsACall()).isTrue();
        assertThat(budgets.standing("ten_b").limit()).isEqualTo(DEFAULT);
        assertThat(budgets.standing("ten_b").own()).isFalse();
        assertThat(budgets.standing("ten_c").allowsACall()).isFalse();
        assertThat(TestDatabase.firstColumn(DATABASE,
                "SELECT tenant_id || ' ' || monthly_limit_usd FROM engine.metering_budgets ORDER BY tenant_id"))
                .containsExactly("ten_a 30.000001", "ten_c 0");
    }

    /** A limit, the tenant's own or the default, is 0 dollars or more, and a budget belongs to a tenant. */
    @Test
    void aLimitIsZeroDollarsOrMoreAndABudgetBelongsToATenant() {
        Budgets budgets = budgets(TOKYO, "2026-11-20T00:00:00Z");

        assertThatThrownBy(() -> budgets.set("ten_a", new BigDecimal("-0.01"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A tenant's monthly budget is 0 dollars or more, not -0.01");
        assertThatThrownBy(() -> budgets.set("ten_a", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> budgets.set(null, BigDecimal.ONE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> budgets.standing(null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A budget belongs to a tenant");
        Clock clock = Clock.systemUTC();
        assertThatThrownBy(() -> new DatabaseBudgets(pool, TOKYO, new BigDecimal("-1"), clock)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The cell's default monthly budget is 0 dollars or more, not -1");
        assertThatThrownBy(() -> new DatabaseBudgets(pool, TOKYO, null, clock)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DatabaseBudgets(pool, null, DEFAULT, clock)).isInstanceOf(NullPointerException.class);
        assertThat(new DatabaseBudgets(pool, TOKYO, BigDecimal.ZERO, clock).standing("ten_a").allowsACall()).isFalse();
    }

    /** The table refuses a limit below 0 by its own check, whoever writes it, and keeps nothing of it. */
    @Test
    void theTableRefusesALimitBelowZero() throws SQLException {
        assertThatThrownBy(() -> pool.inTenantTx("ten_a", tx -> tx.update(
                        "INSERT INTO metering_budgets (tenant_id, monthly_limit_usd) VALUES (?, ?)", "ten_a", new BigDecimal("-1"))))
                .isInstanceOf(DatabaseException.class)
                .rootCause().hasMessageContaining("metering_budgets_monthly_limit_usd_check");
        assertThat(TestDatabase.firstColumn(DATABASE, "SELECT tenant_id FROM engine.metering_budgets")).isEmpty();
    }

    /** Every ask sums its tenant's uses of the month, so the uses are kept by tenant and time. */
    @Test
    void theUsesAreKeptByTenantAndTime() throws SQLException {
        assertThat(TestDatabase.firstColumn(DATABASE, "SELECT indexdef FROM pg_indexes WHERE indexname = 'metering_usage_by_time'"))
                .containsExactly("CREATE INDEX metering_usage_by_time ON engine.metering_usage USING btree (tenant_id, created_at)");
    }
}
