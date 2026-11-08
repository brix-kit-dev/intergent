package aie.core.tenancy.internal;

import aie.core.Ids;
import aie.core.tenancy.api.ApiKeyLookup.Owner;
import aie.core.tenancy.api.EndUsers;
import aie.ports.DatabasePort;

/**
 * {@link EndUsers} on the database. The unique key decides between two requests that bring a new ID at the same
 * moment: the second one's insert waits for the first, does nothing, and then reads the first one's row.
 */
public final class DatabaseEndUsers implements EndUsers {

    private static final String ADD = """
            INSERT INTO tenancy_end_users (end_user_id, tenant_id, application_id, external_id)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (tenant_id, application_id, external_id) DO NOTHING
            """;
    private static final String FIND = "SELECT end_user_id FROM tenancy_end_users WHERE application_id = ? AND external_id = ?";

    private final DatabasePort database;

    public DatabaseEndUsers(DatabasePort database) {
        this.database = database;
    }

    @Override
    public String resolve(Owner caller, String externalId) {
        return database.inTenantTx(caller.tenantId(), tx -> {
            tx.update(ADD, Ids.next("eu"), caller.tenantId(), caller.applicationId(), externalId);
            return tx.query(FIND, row -> row.string("end_user_id"), caller.applicationId(), externalId).getFirst();
        });
    }
}
