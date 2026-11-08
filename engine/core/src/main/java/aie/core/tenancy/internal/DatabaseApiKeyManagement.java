package aie.core.tenancy.internal;

import aie.core.tenancy.api.ApiKeyLookup.Owner;
import aie.core.tenancy.api.ApiKeyManagement;
import aie.ports.DatabasePort;

/**
 * {@link ApiKeyManagement} on the database. It works in the caller's tenant transaction, so row-level security hides
 * other tenants' keys, and the statement names the caller's application, which hides the tenant's other applications.
 */
public final class DatabaseApiKeyManagement implements ApiKeyManagement {

    /** The row stays, so a revoked key can still be traced. */
    private static final String REVOKE = """
            UPDATE tenancy_api_keys SET revoked_at = now()
            WHERE application_id = ? AND key_id = ? AND revoked_at IS NULL
            """;

    private final DatabasePort database;

    public DatabaseApiKeyManagement(DatabasePort database) {
        this.database = database;
    }

    @Override
    public boolean revoke(Owner caller, String keyId) {
        return database.inTenantTx(caller.tenantId(), tx -> tx.update(REVOKE, caller.applicationId(), keyId)) == 1;
    }
}
