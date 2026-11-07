-- Blueprint 6.2. A key belongs to one tenant and one application, and lives for 24 hours.

ALTER TABLE tenancy_applications
    ADD CONSTRAINT tenancy_applications_tenant_application_key UNIQUE (tenant_id, application_id);

CREATE TABLE tenancy_idempotency_keys (
    tenant_id       text NOT NULL,
    application_id  text NOT NULL,
    idempotency_key text NOT NULL,
    -- Identifies the request, so the same key on a different request can be refused.
    fingerprint     text NOT NULL,
    -- Both stay NULL until the first request has its answer. bigint, because Row.number reads only that.
    response_status bigint,
    response_body   text,
    -- A process that dies mid-request never finishes the key, so after this a retry may take it over.
    locked_until    timestamptz NOT NULL DEFAULT now() + interval '5 minutes',
    expires_at      timestamptz NOT NULL DEFAULT now() + interval '24 hours',
    PRIMARY KEY (tenant_id, application_id, idempotency_key),
    -- Foreign keys ignore row-level security, so the tenant is part of this one.
    FOREIGN KEY (tenant_id, application_id) REFERENCES tenancy_applications (tenant_id, application_id)
);

ALTER TABLE tenancy_idempotency_keys ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy_idempotency_keys FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON tenancy_idempotency_keys
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''));

GRANT SELECT, INSERT, UPDATE, DELETE ON tenancy_idempotency_keys TO engine_app;
