package aie.core.metering.internal;

import aie.core.Ids;
import aie.core.metering.api.Metering;
import aie.ports.DatabasePort;
import java.util.Objects;

/**
 * {@link Metering} on the database: each model call is a row of {@code metering_usage}, added in a tenant transaction of
 * its own. The table lets the engine read and add rows, never change or delete them, and keeps each cost as the exact
 * decimal it was given.
 */
public final class DatabaseMetering implements Metering {

    private static final String RECORD = """
            INSERT INTO metering_usage (use_id, tenant_id, request_id, application_id, message_id, task, model, input_tokens,
                output_tokens, cost_usd)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final DatabasePort database;

    public DatabaseMetering(DatabasePort database) {
        this.database = Objects.requireNonNull(database, "Metering is kept in the database");
    }

    @Override
    public void record(Use use) {
        database.inTenantTx(use.tenantId(), tx -> tx.update(RECORD, Ids.next("use"), use.tenantId(), use.requestId(),
                use.applicationId(), use.messageId(), use.task(), use.model(), use.inputTokens(), use.outputTokens(), use.cost()));
    }
}
