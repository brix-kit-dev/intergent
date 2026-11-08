package aie.core.tenancy.api;

import aie.core.tenancy.api.ApiKeyLookup.Owner;

/**
 * What an application does with its own keys through the public API (blueprint 6.3). The first key comes from admin
 * instead, through {@link Provisioning}.
 */
public interface ApiKeyManagement {

    /**
     * Revokes a key of the caller's application from this moment on, the caller's own key included, and the last one
     * too: a leaked key must always be stoppable. Returns false when the application has no such key in use, so a key of
     * another application or tenant reads as missing.
     */
    boolean revoke(Owner caller, String keyId);
}
