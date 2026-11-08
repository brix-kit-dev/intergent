package aie.core.tenancy.internal;

import aie.core.tenancy.api.ApiKeyLookup;
import aie.core.tenancy.api.ApiKeys;
import aie.ports.DatabasePort;
import java.util.Optional;

/**
 * {@link ApiKeyLookup} on the database. A system transaction holds no tenant, so it reads no business table, and the
 * key table is reached only through the system function that searches it by hash.
 */
public final class DatabaseApiKeyLookup implements ApiKeyLookup {

    private static final String FIND = "SELECT key_id, tenant_id, application_id FROM tenancy_find_api_key(?)";

    private final DatabasePort database;

    public DatabaseApiKeyLookup(DatabasePort database) {
        this.database = database;
    }

    /** The hash is unique, so at most one row comes back. */
    @Override
    public Optional<Owner> find(String key) {
        if (key == null || ApiKeys.parse(key).isEmpty()) {
            return Optional.empty();
        }
        String hash = ApiKeys.hash(key);
        return database.inSystemTx(tx -> tx.query(FIND, row -> new Owner(
                        row.string("key_id"), row.string("tenant_id"), row.string("application_id")), hash))
                .stream()
                .findFirst();
    }
}
