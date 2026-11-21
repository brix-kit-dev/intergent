package aie.core.metering.api;

import java.math.BigDecimal;

/**
 * Metering (blueprint 7.4, M3-6): what each model call used and cost, one record a call, added and never changed. The
 * gateway adds the records at each call's end, so no module goes around them (blueprint 9.1); budgets are counted from
 * them (M3-6-2). Costs are in US dollars, as the providers price their models (M3-6 decision 1), and exact: a sum of
 * records is the sum of what the calls cost. A record keeps no text of what anyone said.
 */
public interface Metering {

    /**
     * Records one model call, in a short transaction of its own, outside any other.
     *
     * @throws RuntimeException when the record cannot be added, such as {@link aie.ports.DatabaseException}; nothing is
     *     added then
     */
    void record(Use use);

    /**
     * What one model call used and cost.
     *
     * @param requestId the request it belongs to, as its log lines carry it, or null for a call that no request made
     * @param tenantId the tenant that pays for it
     * @param applicationId the application that made it
     * @param messageId the message its answer was written to
     * @param task the task, such as {@code customer_chat}
     * @param model the model's ID in the registry
     * @param inputTokens the tokens the model was sent, as it counts them
     * @param outputTokens the tokens it wrote
     * @param cost what that cost, in US dollars, by the registry's price
     */
    record Use(String requestId, String tenantId, String applicationId, String messageId, String task, String model,
            long inputTokens, long outputTokens, BigDecimal cost) {

        /** @throws IllegalArgumentException for a field that is missing, a count below 0 or a cost below 0 */
        public Use {
            if (tenantId == null || applicationId == null || messageId == null || task == null || model == null || cost == null) {
                throw new IllegalArgumentException("A use names its tenant, application, message, task and model, and its cost");
            }
            if (inputTokens < 0 || outputTokens < 0 || cost.signum() < 0) {
                throw new IllegalArgumentException("A use counts 0 tokens or more and costs 0 or more, not " + inputTokens
                        + " and " + outputTokens + " tokens for " + cost);
            }
        }
    }
}
