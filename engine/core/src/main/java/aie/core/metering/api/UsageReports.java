package aie.core.metering.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * What an application's model calls used and cost in a window of time (M3-6-4), as metering recorded them: every call,
 * however it ended. The interface layer reads it for {@code GET /v1/usage}; the tenant's budget is not part of it, since
 * a budget belongs to the whole tenant and a key to one application (M3-6 decision 6).
 */
public interface UsageReports {

    /**
     * The calls of the application from {@code from} on and before {@code to}, in a short transaction of its own. Without
     * {@code from}, the window starts with this month in the region's time zone; without {@code to}, it ends with this
     * month.
     *
     * @throws IllegalArgumentException for a tenant or an application that is missing, or a window that does not end
     *     after it starts
     */
    Report report(String tenantId, String applicationId, Instant from, Instant to);

    /**
     * The usage of a window, in all and for each model called in it, in the order of the models' IDs. The costs are US
     * dollars, exact: the sums of what the calls cost.
     *
     * @param from where the window starts, included
     * @param to where the window ends, left out
     */
    record Report(Instant from, Instant to, long inputTokens, long outputTokens, BigDecimal cost, List<ModelUse> byModel) {

        /** @throws IllegalArgumentException for a field that is missing */
        public Report {
            if (from == null || to == null || cost == null || byModel == null) {
                throw new IllegalArgumentException("A report has its window, its cost and its models");
            }
            byModel = List.copyOf(byModel);
        }
    }

    /** What the calls of one model used and cost in the window. */
    record ModelUse(String model, long inputTokens, long outputTokens, BigDecimal cost) {

        /** @throws IllegalArgumentException for a field that is missing */
        public ModelUse {
            if (model == null || cost == null) {
                throw new IllegalArgumentException("A model's use names the model and its cost");
            }
        }
    }
}
