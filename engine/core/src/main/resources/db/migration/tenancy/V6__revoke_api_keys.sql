-- Blueprint 6.3: DELETE /v1/api-keys/{key_id} revokes a key. The row stays, with the time it was revoked, so the key
-- can still be traced; the lookup no longer finds it.

ALTER TABLE tenancy_api_keys ADD COLUMN revoked_at timestamptz;

-- The lookup reads the new column, so engine_system may read it too.
GRANT SELECT (revoked_at) ON tenancy_api_keys TO engine_system;

-- engine_system owns the function, so only engine_system can replace it, and it needs CREATE on the schema for that
-- moment only. CREATE OR REPLACE keeps the owner and the grants but not the settings, so SECURITY DEFINER and
-- search_path are stated again.
GRANT CREATE ON SCHEMA engine TO engine_system;
SET ROLE engine_system;

CREATE OR REPLACE FUNCTION tenancy_find_api_key(wanted_hash text)
    RETURNS TABLE (key_id text, tenant_id text, application_id text)
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = engine, pg_temp
    AS $$
BEGIN
    RETURN QUERY
        SELECT k.key_id, k.tenant_id, k.application_id
        FROM tenancy_api_keys k
        WHERE k.key_hash = wanted_hash AND k.revoked_at IS NULL;
END
$$;

RESET ROLE;
REVOKE CREATE ON SCHEMA engine FROM engine_system;
