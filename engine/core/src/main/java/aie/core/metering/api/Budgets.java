package aie.core.metering.api;

import java.math.BigDecimal;
import java.time.YearMonth;

/**
 * Budgets (blueprint 7.4, M3-6-2): each tenant may spend up to a monthly limit on model calls, in US dollars, its own or
 * the cell's default, so that no tenant is without one (T-4). What a tenant spent is the sum of its uses this month, in
 * the region's time zone. A call may start only while the tenant has spent less than its limit; a call under way when
 * the limit is reached finishes (M3-6 decision 3).
 */
public interface Budgets {

    /**
     * Where the tenant stands this month, in a short transaction of its own, outside any other.
     *
     * @throws IllegalArgumentException for a tenant that is missing
     */
    Standing standing(String tenantId);

    /**
     * Sets the tenant's own monthly limit, which holds for it in place of the cell's default from then on.
     *
     * @throws IllegalArgumentException for a tenant or a limit that is missing, or a limit below 0
     */
    void set(String tenantId, BigDecimal monthlyLimit);

    /**
     * Where a tenant stands.
     *
     * @param month this month, in the region's time zone
     * @param limit the limit that holds for the tenant this month
     * @param own whether the limit is the tenant's own, or else the cell's default
     * @param spent what the tenant's model calls of this month cost so far
     */
    record Standing(YearMonth month, BigDecimal limit, boolean own, BigDecimal spent) {

        /** @throws IllegalArgumentException for a field that is missing */
        public Standing {
            if (month == null || limit == null || spent == null) {
                throw new IllegalArgumentException("A standing has its month, its limit and what was spent");
            }
        }

        /** Whether a model call may start: only while the tenant has spent less than its limit. */
        public boolean allowsACall() {
            return spent.compareTo(limit) < 0;
        }
    }
}
