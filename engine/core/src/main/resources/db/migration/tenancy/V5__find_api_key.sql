-- Blueprint 7.6: the system path that checks an API key. It runs before the tenant is known, so it only finds the
-- tenant, and the request then works in that tenant's transaction. It follows the system function template of the
-- catalog check's sample.

-- engine_system reads the hash and the three columns the function returns, and nothing else of the table.
CREATE POLICY system_find_api_key ON tenancy_api_keys FOR SELECT TO engine_system USING (true);
GRANT SELECT (key_hash, key_id, tenant_id, application_id) ON tenancy_api_keys TO engine_system;

-- PL/pgSQL, because migrate runs with row_security off and PostgreSQL would refuse a LANGUAGE sql body that reads a
-- forced table. The output columns share their names with the table's, so every column here names its table.
CREATE FUNCTION tenancy_find_api_key(wanted_hash text)
    RETURNS TABLE (key_id text, tenant_id text, application_id text)
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = engine, pg_temp
    AS $$
BEGIN
    RETURN QUERY
        SELECT k.key_id, k.tenant_id, k.application_id
        FROM tenancy_api_keys k
        WHERE k.key_hash = wanted_hash;
END
$$;

-- Revoke before the handover. Afterwards engine_owner no longer owns it, and a revoke does nothing without an error.
REVOKE ALL ON FUNCTION tenancy_find_api_key(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tenancy_find_api_key(text) TO engine_app;

-- The new owner needs CREATE on the schema for the handover and at no other time.
GRANT CREATE ON SCHEMA engine TO engine_system;
ALTER FUNCTION tenancy_find_api_key(text) OWNER TO engine_system;
REVOKE CREATE ON SCHEMA engine FROM engine_system;
