package aie.adapters.storepg;

import aie.ports.DatabaseException;
import aie.ports.DatabasePort;
import aie.ports.Tx;
import java.time.Duration;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link DatabasePort} on PostgreSQL.
 *
 * <p>The tenant goes in with {@code set_config(..., true)}, which lasts for the transaction only. A session-level
 * setting would stay on the pooled connection and reach the next request.
 */
public final class PgDatabase implements DatabasePort {

    private static final String SET_TENANT = "SELECT set_config('app.tenant_id', ?, true)";

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    public PgDatabase(DataSource dataSource, Duration transactionTimeout) {
        this.jdbc = JdbcClient.create(dataSource);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.transactions.setTimeout(Math.toIntExact(transactionTimeout.toSeconds()));
    }

    @Override
    public <T> T inTenantTx(String tenantId, Function<Tx, T> work) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("A tenant transaction needs a tenant");
        }
        return inTransaction(tx -> {
            jdbc.sql(SET_TENANT).param(tenantId).query().singleValue();
            return work.apply(tx);
        });
    }

    @Override
    public <T> T inSystemTx(Function<Tx, T> work) {
        return inTransaction(work);
    }

    /** Spring keeps the transaction of each thread apart, so this answers for the calling thread alone. */
    @Override
    public boolean insideTx() {
        return TransactionSynchronizationManager.isActualTransactionActive();
    }

    private <T> T inTransaction(Function<Tx, T> work) {
        if (insideTx()) {
            throw new IllegalStateException("Transactions do not nest");
        }
        try {
            return transactions.execute(status -> {
                try (PgTx tx = new PgTx(jdbc)) {
                    return work.apply(tx);
                }
            });
        } catch (DataAccessException | TransactionException e) {
            throw new DatabaseException("The transaction failed", e);
        }
    }
}
