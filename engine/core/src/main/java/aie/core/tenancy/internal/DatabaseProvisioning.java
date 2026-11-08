package aie.core.tenancy.internal;

import aie.core.Ids;
import aie.core.tenancy.api.ApiVersion;
import aie.core.tenancy.api.Provisioning;
import aie.ports.DatabasePort;

/** {@link Provisioning} on the database. Each new row goes in as its own tenant, as row-level security requires. */
public final class DatabaseProvisioning implements Provisioning {

    private static final String ADD_TENANT = "INSERT INTO tenancy_tenants (tenant_id, parent_tenant_id, cell_id) VALUES (?, ?, ?)";
    private static final String TENANT_EXISTS = "SELECT count(*) AS found FROM tenancy_tenants";
    private static final String ADD_APPLICATION = """
            INSERT INTO tenancy_applications (application_id, tenant_id, environment, api_version, default_data_class)
            VALUES (?, ?, ?, ?, ?)
            """;


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
                .orElseThrow(() -> new IllegalArgumentException("Application " + applicationId + " does not exist in tenant " + tenantId));
        return new IssuedKey(issued.keyId(), issued.key());
    }
}
