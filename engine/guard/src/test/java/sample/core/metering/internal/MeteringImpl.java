package sample.core.metering.internal;

import sample.core.metering.api.MeteringApi;
import sample.ports.DatabasePort;

public class MeteringImpl implements MeteringApi {

    DatabasePort database;

    int record(String tenantId) {
        return database.inTenantTx(tenantId, tx -> tx.update("insert into metering_usage default values"));
    }
}
