-- Blueprint 7.1. This and every other table follows the template of 5.6: row-level security, FORCE,
-- the tenant policy and the grants.

CREATE TABLE tenancy_tenants (
    tenant_id        text PRIMARY KEY,
    -- No foreign key: a child tenant may sit in another database or another cell (blueprint 7.2).
    parent_tenant_id text,
    isolation_tier   text NOT NULL DEFAULT 'pool' CHECK (isolation_tier IN ('pool', 'silo', 'cell')),
    -- The cell code goes into API keys. Anything else here would break parsing them (blueprint 12.1).
    cell_id          text NOT NULL CHECK (cell_id ~ '^[a-z0-9]{1,8}$'),
    created_at       timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE tenancy_tenants ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy_tenants FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON tenancy_tenants
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''));

GRANT SELECT, INSERT, UPDATE, DELETE ON tenancy_tenants TO engine_app;
