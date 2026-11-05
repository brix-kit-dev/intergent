package sample.core.tenancy.internal;

import sample.core.tenancy.api.TenancyApi;
import sample.ports.DatabasePort;

public class TenancyImpl implements TenancyApi {

    DatabasePort database;

    int resolve() {
        return database.inSystemTx(tx -> tx.update("select tenancy_resolve()"));
    }
}
