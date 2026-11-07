package aie.core.tenancy.internal;

import aie.core.Ids;
import aie.core.tenancy.api.ApiKeys;
import aie.core.tenancy.api.ApiVersion;
import aie.core.tenancy.api.Provisioning;
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

    /** The key takes the application's environment and the tenant's cell, so it can be routed without the database. */
    private static final String KEY_PARTS = """
            SELECT a.environment, t.cell_id
            FROM tenancy_applications a
            JOIN tenancy_tenants t ON t.tenant_id = a.tenant_id
            WHERE a.application_id = ?
            """;
    private static final String ADD_KEY = "INSERT INTO tenancy_api_keys (key_id, tenant_id, application_id, key_hash) VALUES (?, ?, ?, ?)";

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
        String keyId = Ids.next("key");
        String key = database.inTenantTx(tenantId, tx -> {
            List<String> issued = tx.query(KEY_PARTS, row -> ApiKeys.issue(row.string("environment"), row.string("cell_id")), applicationId);
            if (issued.isEmpty()) {
                throw new IllegalArgumentException("Application " + applicationId + " does not exist in tenant " + tenantId);
            }
            tx.update(ADD_KEY, keyId, tenantId, applicationId, ApiKeys.hash(issued.getFirst()));
            return issued.getFirst();
        });
        return new IssuedKey(keyId, key);
    }
}
