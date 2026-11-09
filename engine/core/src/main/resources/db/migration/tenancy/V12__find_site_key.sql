-- Blueprint 7.6: the system path that checks a site key. A browser's request carries no tenant, so this only finds the
-- tenant and the application, and the request then works in that tenant's transaction. The status it returns says
-- whether the key lists the request's origin, so the origins themselves never leave the database. A revoked key is not
-- found, the same as an unknown one. It follows tenancy_find_api_key in V5__find_api_key.sql.

-- engine_system reads the columns the function needs, and nothing else of the table.
CREATE POLICY system_find_site_key ON tenancy_site_keys FOR SELECT TO engine_system USING (true);
GRANT SELECT (key_hash, site_key_id, tenant_id, application_id, origins, revoked_at) ON tenancy_site_keys TO engine_system;

-- request_origin is the origin as SiteKeys.origin writes it, or null when the request has none it could read.
CREATE FUNCTION tenancy_find_site_key(wanted_hash text, request_origin text)
    RETURNS TABLE (site_key_id text, tenant_id text, application_id text, origin_allowed boolean)
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = engine, pg_temp
    AS $$
BEGIN
    RETURN QUERY
        SELECT s.site_key_id, s.tenant_id, s.application_id, coalesce(request_origin = ANY (s.origins), false)
        FROM tenancy_site_keys s
        WHERE s.key_hash = wanted_hash
            AND s.revoked_at IS NULL;
END
$$;

-- Revoke before the handover. Afterwards engine_owner no longer owns it, and a revoke does nothing without an error.
REVOKE ALL ON FUNCTION tenancy_find_site_key(text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tenancy_find_site_key(text, text) TO engine_app;

-- The new owner needs CREATE on the schema for the handover and at no other time.
GRANT CREATE ON SCHEMA engine TO engine_system;
ALTER FUNCTION tenancy_find_site_key(text, text) OWNER TO engine_system;
REVOKE CREATE ON SCHEMA engine FROM engine_system;
