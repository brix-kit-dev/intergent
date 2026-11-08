-- Blueprint 6.3 and 6.10: a key may carry an expiry, set when it is created. Once it has passed, the key reads as
-- invalid, the same as a revoked one (401 unauthorized). The row stays.

ALTER TABLE tenancy_api_keys ADD COLUMN expires_at timestamptz;

-- The lookup reads the new column, so engine_system may read it too.
GRANT SELECT (expires_at) ON tenancy_api_keys TO engine_system;

-- engine_system owns the function, so it is replaced the way V6__revoke_api_keys.sql explains.
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
        WHERE k.key_hash = wanted_hash
            AND k.revoked_at IS NULL
            AND (k.expires_at IS NULL OR k.expires_at > now());
END
$$;

RESET ROLE;
REVOKE CREATE ON SCHEMA engine FROM engine_system;
