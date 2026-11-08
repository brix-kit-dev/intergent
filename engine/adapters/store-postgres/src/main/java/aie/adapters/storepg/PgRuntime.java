package aie.adapters.storepg;

import aie.ports.DatabasePort;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import org.postgresql.ds.PGSimpleDataSource;

/** The database of the roles that serve traffic or change data: api, worker and admin. */
public final class PgRuntime {

    private static final Duration TRANSACTION_TIMEOUT = Duration.ofSeconds(30);

    private PgRuntime() {
    }

    /**
     * Connects as engine_app once it has passed the self-check of blueprint 7.6, with a new connection for each
     * transaction. That suits admin, which runs a few transactions and stops.
     */
    public static DatabasePort connect(String url, String appPassword) {
        PgSelfCheck.run(url, appPassword);
        return new PgDatabase(runtimeConnections(url, appPassword), TRANSACTION_TIMEOUT);
    }

    /**
     * A pool of at most {@code size} connections for a role that serves traffic. The rules of {@link #connect} hold
     * here too: the self-check comes first, and every connection is engine_app without session options.
     */
    public static PgPool pool(String url, String appPassword, int size) {
        PgSelfCheck.run(url, appPassword);
        HikariConfig config = new HikariConfig();
        config.setDataSource(runtimeConnections(url, appPassword));
        config.setMaximumPoolSize(size);
        config.setPoolName(PgConnections.RUNTIME_ROLE);
        return new PgPool(new HikariDataSource(config), TRANSACTION_TIMEOUT);
    }

    /**
     * The address picks the server and nothing else. Session options would be the worst leak: a default for
     * app.tenant_id there would give every transaction without a tenant one after all.
     */
    private static PGSimpleDataSource runtimeConnections(String url, String appPassword) {
        PGSimpleDataSource dataSource = PgConnections.as(PgConnections.RUNTIME_ROLE, url, appPassword);
        dataSource.setOptions(null);
        return dataSource;
    }
}
