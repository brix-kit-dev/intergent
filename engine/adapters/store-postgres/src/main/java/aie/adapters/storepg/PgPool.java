package aie.adapters.storepg;

import aie.ports.DatabasePort;
import aie.ports.Tx;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.util.function.Function;

/**
 * The connection pool of a role that serves traffic, such as api. It comes from {@link PgRuntime#pool}. A tenant lasts
 * for one transaction only, so the next transaction on the same connection starts without one. Close the pool when
 * the role stops.
 */
public final class PgPool implements DatabasePort, AutoCloseable {

    private final HikariDataSource pool;
    private final PgDatabase database;

    PgPool(HikariDataSource pool, Duration transactionTimeout) {
        this.pool = pool;
        this.database = new PgDatabase(pool, transactionTimeout);
    }

    @Override
    public <T> T inTenantTx(String tenantId, Function<Tx, T> work) {
        return database.inTenantTx(tenantId, work);
    }

    @Override
    public <T> T inSystemTx(Function<Tx, T> work) {
        return database.inSystemTx(work);
    }

    /** Closes every connection of the pool. Calling it again does nothing. */
    @Override
    public void close() {
        pool.close();
    }
}
