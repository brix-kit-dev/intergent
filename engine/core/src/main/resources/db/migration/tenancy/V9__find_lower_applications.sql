-- Blueprint 6.5 and 7.6: a platform host's key acts for one of its lower tenants with the On-Behalf-Of header. The
-- request then runs as the lower tenant's application of the key's environment, the way a Stripe Connect platform's
-- test key works on a connected account's test data. This system function answers whether the tenant is a lower
-- tenant of the caller's tenant, and which applications of that environment it has. It follows the template of V5.

-- engine_system reads what the answer needs and nothing else.
CREATE POLICY system_find_lower_applications ON tenancy_tenants FOR SELECT TO engine_system USING (true);
GRANT SELECT (tenant_id, parent_tenant_id) ON tenancy_tenants TO engine_system;
CREATE POLICY system_find_lower_applications ON tenancy_applications FOR SELECT TO engine_system USING (true);
GRANT SELECT (tenant_id, application_id, environment) ON tenancy_applications TO engine_system;

-- No row: not a lower tenant of that parent, or not in this database. One row with no application: a lower tenant
-- without an application of that environment. Otherwise one row for each such application.
CREATE FUNCTION tenancy_find_lower_applications(parent_tenant text, lower_tenant text, wanted_environment text)
    RETURNS TABLE (application_id text)
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = engine, pg_temp
    AS $$
BEGIN
    RETURN QUERY
        SELECT a.application_id
        FROM tenancy_tenants t
        LEFT JOIN tenancy_applications a ON a.tenant_id = t.tenant_id AND a.environment = wanted_environment
        WHERE t.tenant_id = lower_tenant AND t.parent_tenant_id = parent_tenant;
END
$$;

-- Revoke before the handover. Afterwards engine_owner no longer owns it, and a revoke does nothing without an error.
REVOKE ALL ON FUNCTION tenancy_find_lower_applications(text, text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tenancy_find_lower_applications(text, text, text) TO engine_app;

-- The new owner needs CREATE on the schema for the handover and at no other time.
GRANT CREATE ON SCHEMA engine TO engine_system;
ALTER FUNCTION tenancy_find_lower_applications(text, text, text) OWNER TO engine_system;
REVOKE CREATE ON SCHEMA engine FROM engine_system;
