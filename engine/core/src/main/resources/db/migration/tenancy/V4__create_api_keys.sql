-- Blueprint 6.5: an application's key is kept as a hash only. The key itself is shown once, when it is issued.

CREATE TABLE tenancy_api_keys (
    key_id         text PRIMARY KEY,
    tenant_id      text NOT NULL,
    application_id text NOT NULL,
    -- SHA-256 of the whole key, in hex. The check refuses anything else, such as the key itself stored by mistake.
    -- Unique, because a request is matched to its key by this alone.
    key_hash       text NOT NULL UNIQUE CHECK (key_hash ~ '^[0-9a-f]{64}$'),
    created_at     timestamptz NOT NULL DEFAULT now(),
    -- Foreign keys ignore row-level security, so the tenant is part of this one.
    FOREIGN KEY (tenant_id, application_id) REFERENCES tenancy_applications (tenant_id, application_id)
);

CREATE INDEX tenancy_api_keys_application_idx ON tenancy_api_keys (tenant_id, application_id);

ALTER TABLE tenancy_api_keys ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy_api_keys FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON tenancy_api_keys
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''));

GRANT SELECT, INSERT, UPDATE, DELETE ON tenancy_api_keys TO engine_app;
