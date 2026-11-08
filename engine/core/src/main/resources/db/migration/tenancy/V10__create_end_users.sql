-- Blueprint 7.1: an end user is the host's user as the engine knows them. The engine keeps only the host's own ID
-- and gives each end user a random end_user_id, the only ID other tables refer to: blueprint 7.5 keeps the host's
-- ID, often a phone number, out of audit and deletion records. One external ID is one end user per application.

CREATE TABLE tenancy_end_users (
    end_user_id    text PRIMARY KEY,
    tenant_id      text NOT NULL,
    application_id text NOT NULL,
    -- The contract allows 1 to 255 characters.
    external_id    text NOT NULL CHECK (char_length(external_id) BETWEEN 1 AND 255),
    created_at     timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, application_id, external_id),
    -- Foreign keys ignore row-level security, so the tenant is part of this one.
    FOREIGN KEY (tenant_id, application_id) REFERENCES tenancy_applications (tenant_id, application_id)
);

ALTER TABLE tenancy_end_users ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy_end_users FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON tenancy_end_users
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''));

GRANT SELECT, INSERT, UPDATE, DELETE ON tenancy_end_users TO engine_app;
