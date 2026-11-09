package aie.core.tenancy.api;

import java.util.List;

/** Opens tenants and applications. Only the admin role does this, never the public API (blueprint 6.3). */
public interface Provisioning {

    /**
     * Creates a tenant in this cell and returns its ID. The parent may live in another database or cell, so it is not
     * checked; pass null for a tenant without one.
     */
    String createTenant(String cellCode, String parentTenantId);

    /** Creates an application of the tenant, locked to the current API version, and returns its ID. */
    String createApplication(String tenantId, String environment, String defaultDataClass);

    /** Issues a key for an application of the tenant. The key itself comes back this once and is stored nowhere. */
    IssuedKey createKey(String tenantId, String applicationId);

    /**
     * Issues a site key for an application of the tenant, for browsers on these origins only. Like an API key, it comes
     * back this once and only its hash is stored.
     *
     * @throws IllegalArgumentException when an origin is not one a site key may list, see {@link SiteKeys#origins}
     */
    IssuedKey createSiteKey(String tenantId, String applicationId, List<String> origins);

    /** Revokes a site key of the tenant. False when the tenant has no such key, or it was revoked before. */
    boolean revokeSiteKey(String tenantId, String siteKeyId);

    /** The ID names the key from then on. The key is what the host keeps. */
    record IssuedKey(String keyId, String key) {
    }
}
