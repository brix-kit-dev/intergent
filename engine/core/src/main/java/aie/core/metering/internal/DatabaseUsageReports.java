package aie.core.metering.internal;

import aie.core.metering.api.UsageReports;
import aie.ports.DatabasePort;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

/**
 * {@link UsageReports} on the database: the application's rows of {@code metering_usage} in the window, summed by model
 * in one query of the tenant's transaction, and the totals summed from those, exactly. A month starts at midnight on its
 * first day in the region's time zone, as a budget's does (M3-6-2).
 */
public final class DatabaseUsageReports implements UsageReports {

    private static final String BY_MODEL = """
            SELECT model, sum(input_tokens)::text AS input_tokens, sum(output_tokens)::text AS output_tokens,
                sum(cost_usd)::text AS cost
            FROM metering_usage
            WHERE tenant_id = ? AND application_id = ? AND created_at >= ? AND created_at < ?
            GROUP BY model ORDER BY model
            """;

    private final DatabasePort database;
    private final ZoneId zone;
    private final Clock clock;

    /**
     * @param zone the region's time zone, in which a month starts and ends (M3-6 decision 2)
     * @param clock tells which month it is
     */
    public DatabaseUsageReports(DatabasePort database, ZoneId zone, Clock clock) {
        this.database = Objects.requireNonNull(database, "Usage is kept in the database");
        this.zone = Objects.requireNonNull(zone, "A report's month is a month of one time zone");
        this.clock = Objects.requireNonNull(clock, "Reports need a clock to know the month");
    }

    @Override
    public Report report(String tenantId, String applicationId, Instant from, Instant to) {
        if (tenantId == null || applicationId == null) {
            throw new IllegalArgumentException("A report is of a tenant's application");
        }
        YearMonth month = YearMonth.now(clock.withZone(zone));
        Instant start = from != null ? from : month.atDay(1).atStartOfDay(zone).toInstant();
        Instant end = to != null ? to : month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("from must come before to; without from the window starts with this month, and"
                    + " without to it ends with this month");
        }
        List<ModelUse> byModel = database.inTenantTx(tenantId, tx -> tx.query(BY_MODEL, row -> new ModelUse(row.string("model"),
                Long.parseLong(row.string("input_tokens")), Long.parseLong(row.string("output_tokens")),
                new BigDecimal(row.string("cost"))), tenantId, applicationId, start, end));
        long inputTokens = 0;
        long outputTokens = 0;
        BigDecimal cost = BigDecimal.ZERO;
        for (ModelUse use : byModel) {
            inputTokens = Math.addExact(inputTokens, use.inputTokens());
            outputTokens = Math.addExact(outputTokens, use.outputTokens());
            cost = cost.add(use.cost());
        }
        return new Report(start, end, inputTokens, outputTokens, cost, byModel);
    }
}
