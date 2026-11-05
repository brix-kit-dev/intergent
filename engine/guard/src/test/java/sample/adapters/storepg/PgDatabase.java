package sample.adapters.storepg;

import java.util.function.Function;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import sample.ports.DatabasePort;
import sample.ports.Tx;

public class PgDatabase implements DatabasePort {

    DataSource source;
    JdbcClient jdbc;
    PGConnection connection;

    @Override
    @Transactional
    public <T> T inTenantTx(String tenantId, Function<Tx, T> work) {
        return work.apply(null);
    }

    @Override
    @Transactional
    public <T> T inSystemTx(Function<Tx, T> work) {
        return work.apply(null);
    }

    int startupCheck() {
        return inSystemTx(tx -> tx.update("select 1"));
    }
}
