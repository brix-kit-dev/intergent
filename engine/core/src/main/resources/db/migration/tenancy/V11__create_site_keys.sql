-- Blueprint 6.5: a browser trades a site key for an anonymous token, and only from an origin the key lists. The key sits
-- in the host's pages, so it is public, but it is still kept as a hash only: blueprint 7.6 looks site keys up by hash,
-- as it does API keys, and the database never holds a value that works on its own. Revoking keeps the row.

CREATE TABLE tenancy_site_keys (
    site_key_id    text PRIMARY KEY,
    tenant_id      text NOT NULL,
    application_id text NOT NULL,
    -- SHA-256 of the whole key, in hex. Unique, because a request is matched to its key by this alone.
    key_hash       text NOT NULL UNIQUE CHECK (key_hash ~ '^[0-9a-f]{64}$'),
    -- 1 to 10 exact origins such as https://shop.example.com, as SiteKeys.origin writes them. No element may be null,
    -- and none may hold a path, a space or a comma.
    origins        text[] NOT NULL CHECK (
        cardinality(origins) BETWEEN 1 AND 10
        AND array_position(origins, NULL) IS NULL
        AND array_to_string(origins, ' ') ~ '^https?://[^ /,]+( https?://[^ /,]+)*$'),
    created_at     timestamptz NOT NULL DEFAULT now(),
    revoked_at     timestamptz,
    -- Foreign keys ignore row-level security, so the tenant is part of this one.
    FOREIGN KEY (tenant_id, application_id) REFERENCES tenancy_applications (tenant_id, application_id)
);

CREATE INDEX tenancy_site_keys_application_idx ON tenancy_site_keys (tenant_id, application_id);

ALTER TABLE tenancy_site_keys ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy_site_keys FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON tenancy_site_keys
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''));

GRANT SELECT, INSERT, UPDATE, DELETE ON tenancy_site_keys TO engine_app;
