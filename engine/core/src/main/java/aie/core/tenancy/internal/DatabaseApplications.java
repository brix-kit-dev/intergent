package aie.core.tenancy.internal;

import aie.core.tenancy.api.ApiKeyLookup.Owner;
import aie.core.tenancy.api.Applications;
import aie.core.trust.api.DataClass;
import aie.ports.DatabasePort;
import java.util.List;

/** {@link Applications} on the database, in the caller's own tenant, so row-level security keeps it to that tenant. */
public final class DatabaseApplications implements Applications {

    private static final String DEFAULT_DATA_CLASS = "SELECT default_data_class FROM tenancy_applications WHERE application_id = ?";

    private final DatabasePort database;

    public DatabaseApplications(DatabasePort database) {
        this.database = database;
    }

    @Override
    public DataClass defaultDataClass(Owner caller) {
        List<String> found = database.inTenantTx(caller.tenantId(), tx -> tx.query(
                DEFAULT_DATA_CLASS, row -> row.string("default_data_class"), caller.applicationId()));
        if (found.isEmpty()) {
            throw new IllegalStateException("The application " + caller.applicationId() + " of tenant " + caller.tenantId()
                    + " does not exist");
        }
        return DataClass.fromWireName(found.getFirst()).orElseThrow(() -> new IllegalStateException(
                "The application " + caller.applicationId() + " has the unknown data class " + found.getFirst()));
    }
}
