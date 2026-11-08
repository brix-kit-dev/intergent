package aie.core.tenancy.api;

import java.util.Optional;

/**
 * Finds whose an API key is: the system path of blueprint 7.6. It runs before the tenant is known, so it returns only
 * what the request needs to work in that tenant's transaction.
 */
public interface ApiKeyLookup {

    /** Returns the owner of the key, or nothing for a key that is malformed or unknown. A malformed key never reaches the database. */
    Optional<Owner> find(String key);

    /** The key and the application it belongs to. */
    record Owner(String keyId, String tenantId, String applicationId) {
    }
}
