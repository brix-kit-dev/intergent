package aie.ports;

import java.util.function.Function;

/**
 * Transactions, tenant context and SQL execution. Modules write their own SQL and run it through {@link Tx}.
 *
 * <p>A transaction never nests, and it ends with the call that opened it. Make no outside calls inside one, or a
 * single streamed answer holds a database connection for its whole length (A-14). {@link #insideTx} tells whether a
 * call would be inside one.
 */
public interface DatabasePort {

    /** Runs work in one transaction bound to this tenant, so row-level security shows only its rows. */
    <T> T inTenantTx(String tenantId, Function<Tx, T> work);

    /** Runs work with no tenant, so business tables read as empty. Only tenancy and events may call this. */
    <T> T inSystemTx(Function<Tx, T> work);

    /**
     * Whether the calling thread is inside a transaction right now. A call that leaves the engine, such as one to a
     * model, asks this first and is refused while it is true. Work handed to another thread is not seen.
     */
    boolean insideTx();
}
