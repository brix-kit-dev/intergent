package aie.core.metering.internal;

import aie.core.metering.api.Budgets;
import aie.ports.DatabasePort;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

/**
 * {@link Budgets} on the database: a tenant's own limit is its row of {@code metering_budgets}, and what it spent is the
 * sum of its rows of {@code metering_usage} from the first of this month at midnight in the region's time zone on, and
 * before the first of the next, both read in one short tenant transaction.
 */
public final class DatabaseBudgets implements Budgets {

    private static final String LIMIT = "SELECT monthly_limit_usd::text AS monthly_limit FROM metering_budgets WHERE tenant_id = ?";
    private static final String SPENT = """
            SELECT coalesce(sum(cost_usd), 0)::text AS spent FROM metering_usage
            WHERE tenant_id = ? AND created_at >= ? AND created_at < ?
            """;
    private static final String SET = """
            INSERT INTO metering_budgets (tenant_id, monthly_limit_usd) VALUES (?, ?)
            ON CONFLICT (tenant_id) DO UPDATE SET monthly_limit_usd = EXCLUDED.monthly_limit_usd, updated_at = now()
            """;

    private final DatabasePort database;
    private final ZoneId zone;
    private final BigDecimal defaultLimit;
    private final Clock clock;

    /**
     * @param zone the region's time zone, in which a month starts and ends (M3-6 decision 2)
     * @param defaultLimit the cell's monthly limit for a tenant without its own (M3-6 decision 4)
     * @param clock tells which month it is
     * @throws IllegalArgumentException for a default limit below 0
     */
    public DatabaseBudgets(DatabasePort database, ZoneId zone, BigDecimal defaultLimit, Clock clock) {
        this.database = Objects.requireNonNull(database, "Budgets are kept in the database");
        this.zone = Objects.requireNonNull(zone, "A budget's month is a month of one time zone");
        this.clock = Objects.requireNonNull(clock, "Budgets need a clock to know the month");
        if (defaultLimit == null || defaultLimit.signum() < 0) {
            throw new IllegalArgumentException("The cell's default monthly budget is 0 dollars or more, not " + defaultLimit);
        }
        this.defaultLimit = defaultLimit;
    }

    @Override
    public Standing standing(String tenantId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("A budget belongs to a tenant");
        }
        YearMonth month = YearMonth.now(clock.withZone(zone));
        Instant from = month.atDay(1).atStartOfDay(zone).toInstant();
        Instant to = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();
        return database.inTenantTx(tenantId, tx -> {
            List<String> own = tx.query(LIMIT, row -> row.string("monthly_limit"), tenantId);
            BigDecimal spent = new BigDecimal(tx.query(SPENT, row -> row.string("spent"), tenantId, from, to).getFirst());
            return own.isEmpty()
                    ? new Standing(month, defaultLimit, false, spent)
                    : new Standing(month, new BigDecimal(own.getFirst()), true, spent);
        });
    }

    @Override
    public void set(String tenantId, BigDecimal monthlyLimit) {
        if (tenantId == null || monthlyLimit == null || monthlyLimit.signum() < 0) {
            throw new IllegalArgumentException("A tenant's monthly budget is 0 dollars or more, not " + monthlyLimit);
        }
        database.inTenantTx(tenantId, tx -> tx.update(SET, tenantId, monthlyLimit));
    }
}
