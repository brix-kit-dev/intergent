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
    private static final String ADD_ANONYMOUS =
            "INSERT INTO tenancy_end_users (end_user_id, tenant_id, application_id) VALUES (?, ?, ?)";

    private final DatabasePort database;

    public DatabaseEndUsers(DatabasePort database) {
        this.database = database;
    }

    /** A null ID would go in as an anonymous end user, which the lookup after it could never find. */
    @Override
    public String resolve(Owner caller, String externalId) {
        if (externalId == null) {
            throw new IllegalArgumentException("An end user found by the host's ID needs the ID");
        }
        return database.inTenantTx(caller.tenantId(), tx -> {
            tx.update(ADD, Ids.next("eu"), caller.tenantId(), caller.applicationId(), externalId);
            return tx.query(FIND, row -> row.string("end_user_id"), caller.applicationId(), externalId).getFirst();
        });
    }

    @Override
    public String anonymous(Owner caller) {
        String endUserId = Ids.next("eu");
        database.inTenantTx(caller.tenantId(),
                tx -> tx.update(ADD_ANONYMOUS, endUserId, caller.tenantId(), caller.applicationId()));
        return endUserId;
    }
}
