package aie.core.tenancy.internal;

import aie.core.tenancy.api.ApiKeyLookup.Owner;
import aie.core.tenancy.api.LowerTenants;
import aie.ports.DatabasePort;
import java.util.List;
import java.util.Optional;

/**
 * {@link LowerTenants} on the database. The caller's tenant transaction would hide the lower tenant's rows, so this
 * asks the system function, which reads only the columns the answer needs (blueprint 7.6).
 */
public final class DatabaseLowerTenants implements LowerTenants {

    private static final String FIND = "SELECT application_id FROM tenancy_find_lower_applications(?, ?, ?)";

    private final DatabasePort database;

    public DatabaseLowerTenants(DatabasePort database) {
        this.database = database;
    }

    /** No row means no lower tenant; a row without an application means a lower tenant without one. */
    @Override
    public Acting actFor(Owner caller, String environment, String lowerTenantId) {
        List<Optional<String>> applications = database.inSystemTx(tx -> tx.query(
                FIND, row -> Optional.ofNullable(row.string("application_id")), caller.tenantId(), lowerTenantId, environment));
        if (applications.isEmpty()) {
            return new Acting.NotALowerTenant();
        }
        if (applications.size() != 1 || applications.getFirst().isEmpty()) {
            return new Acting.NoSingleApplication();
        }
        return new Acting.As(new Owner(caller.keyId(), lowerTenantId, applications.getFirst().get()));
    }
}
