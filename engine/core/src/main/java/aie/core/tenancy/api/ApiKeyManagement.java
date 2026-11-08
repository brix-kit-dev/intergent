package aie.core.tenancy.api;

import aie.core.tenancy.api.ApiKeyLookup.Owner;
import java.time.Instant;

/**
 * What an application does with its own keys through the public API (blueprint 6.3). The first key comes from admin
 * instead, through {@link Provisioning}.
 */
public interface ApiKeyManagement {

    /**
     * Creates another key for the caller's application, with the application's environment and the tenant's cell.
     * Old and new keys work side by side, which is how a key is rotated. The name may be null.
     */
    CreatedKey create(Owner caller, String name);

    /**
     * Revokes a key of the caller's application from this moment on, the caller's own key included, and the last one
     * too: a leaked key must always be stoppable. Returns false when the application has no such key in use, so a key of
     * another application or tenant reads as missing.
     */
    boolean revoke(Owner caller, String keyId);

    /** The key itself comes back this once and is stored nowhere; the ID names the key from then on. The name may be null. */
    record CreatedKey(String keyId, String key, String name, Instant createdAt) {
    }
}
