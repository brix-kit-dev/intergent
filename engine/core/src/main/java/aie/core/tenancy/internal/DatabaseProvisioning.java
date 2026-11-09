package aie.core.tenancy.internal;

import aie.core.Ids;
import aie.core.tenancy.api.ApiVersion;
import aie.core.tenancy.api.Provisioning;
import aie.core.tenancy.api.SiteKeys;
import aie.ports.DatabasePort;
import java.util.List;

/** {@link Provisioning} on the database. Each new row goes in as its own tenant, as row-level security requires. */
public final class DatabaseProvisioning implements Provisioning {

    private static final String ADD_TENANT = "INSERT INTO tenancy_tenants (tenant_id, parent_tenant_id, cell_id) VALUES (?, ?, ?)";
    private static final String TENANT_EXISTS = "SELECT count(*) AS found FROM tenancy_tenants";
    private static final String ADD_APPLICATION = """
            INSERT INTO tenancy_applications (application_id, tenant_id, environment, api_version, default_data_class)
            VALUES (?, ?, ?, ?, ?)
            """;
    /** A checked origin never holds a comma, so the list goes in as one comma-separated text. */
    private static final String ADD_SITE_KEY = """
            INSERT INTO tenancy_site_keys (site_key_id, tenant_id, application_id, key_hash, origins)
            VALUES (?, ?, ?, ?, string_to_array(?, ','))
            """;
    private static final String REVOKE_SITE_KEY =
            "UPDATE tenancy_site_keys SET revoked_at = now() WHERE site_key_id = ? AND revoked_at IS NULL";


    private final DatabasePort database;

    public DatabaseProvisioning(DatabasePort database) {
        this.database = database;
    }

    @Override
    public String createTenant(String cellCode, String parentTenantId) {
        String tenantId = Ids.next("ten");
        database.inTenantTx(tenantId, tx -> tx.update(ADD_TENANT, tenantId, parentTenantId, cellCode));
        return tenantId;
    }

    /** Row-level security shows the tenant only its own row, so the count is 1 exactly when the tenant exists. */
    @Override
    public String createApplication(String tenantId, String environment, String defaultDataClass) {
        String applicationId = Ids.next("app");
        database.inTenantTx(tenantId, tx -> {
            if (tx.query(TENANT_EXISTS, row -> row.number("found")).get(0) == 0) {
                throw new IllegalArgumentException("Tenant " + tenantId + " does not exist");
            }
            return tx.update(ADD_APPLICATION, applicationId, tenantId, environment, ApiVersion.CURRENT, defaultDataClass);
        });
        return applicationId;
    }

    /** Row-level security hides the applications of other tenants, so one of them reads as missing. */
    @Override
    public IssuedKey createKey(String tenantId, String applicationId) {
        KeyIssuing.Issued issued = database.inTenantTx(tenantId, tx -> KeyIssuing.issue(tx, tenantId, applicationId, null, null))
                .orElseThrow(() -> missingApplication(tenantId, applicationId));
        return new IssuedKey(issued.keyId(), issued.key());
    }

    /** The key takes the application's environment and the tenant's cell, as an API key does. */
    @Override
    public IssuedKey createSiteKey(String tenantId, String applicationId, List<String> origins) {
        List<String> checked = SiteKeys.origins(origins);
        String siteKeyId = Ids.next("site");
        String siteKey = database.inTenantTx(tenantId, tx -> {
            List<String> keys = tx.query(
                    KeyIssuing.KEY_PARTS, row -> SiteKeys.issue(row.string("environment"), row.string("cell_id")), applicationId);
            if (keys.isEmpty()) {
                throw missingApplication(tenantId, applicationId);
            }
            tx.update(ADD_SITE_KEY, siteKeyId, tenantId, applicationId, SiteKeys.hash(keys.getFirst()), String.join(",", checked));
            return keys.getFirst();
        });
        return new IssuedKey(siteKeyId, siteKey);
    }

    @Override
    public boolean revokeSiteKey(String tenantId, String siteKeyId) {
        return database.inTenantTx(tenantId, tx -> tx.update(REVOKE_SITE_KEY, siteKeyId)) == 1;
    }

    private static IllegalArgumentException missingApplication(String tenantId, String applicationId) {
        return new IllegalArgumentException("Application " + applicationId + " does not exist in tenant " + tenantId);
    }
}
