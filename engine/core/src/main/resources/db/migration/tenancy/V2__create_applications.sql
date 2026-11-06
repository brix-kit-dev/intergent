-- Blueprint 7.1. An application is one integration: test and live are two applications.

CREATE TABLE tenancy_applications (
    application_id     text PRIMARY KEY,
    tenant_id          text NOT NULL REFERENCES tenancy_tenants (tenant_id),
    environment        text NOT NULL CHECK (environment IN ('live', 'test')),
    api_version        text NOT NULL,
    default_data_class text NOT NULL CHECK (default_data_class IN ('public', 'internal', 'personal', 'sensitive')),
    created_at         timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX tenancy_applications_tenant_id_idx ON tenancy_applications (tenant_id);

ALTER TABLE tenancy_applications ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy_applications FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON tenancy_applications
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''));

GRANT SELECT, INSERT, UPDATE, DELETE ON tenancy_applications TO engine_app;
