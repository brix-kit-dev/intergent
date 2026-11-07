package aie.adapters.storepg;

import aie.ports.DatabasePort;
import java.time.Duration;
import org.postgresql.ds.PGSimpleDataSource;

/** The database of the roles that serve traffic or change data: api, worker and admin. */
public final class PgRuntime {

    private static final Duration TRANSACTION_TIMEOUT = Duration.ofSeconds(30);

    private PgRuntime() {
    }

    /**
     * Connects as engine_app once it has passed the self-check of blueprint 7.6. The address cannot set session options
     * either: a default for app.tenant_id there would give every transaction without a tenant one after all.
     */
    public static DatabasePort connect(String url, String appPassword) {
        PgSelfCheck.run(url, appPassword);
        PGSimpleDataSource dataSource = PgConnections.as(PgConnections.RUNTIME_ROLE, url, appPassword);
        dataSource.setOptions(null);
        return new PgDatabase(dataSource, TRANSACTION_TIMEOUT);
    }
}
