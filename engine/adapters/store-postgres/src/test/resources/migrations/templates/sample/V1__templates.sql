-- The templates of blueprint 5.6 and 7.6. CatalogCheckTest runs this the way migrate does, and all of it must pass.

-- A tenant table.
CREATE TABLE sample_items (
    item_id   text PRIMARY KEY,
    tenant_id text NOT NULL
);
ALTER TABLE sample_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE sample_items FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sample_items
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''));
GRANT SELECT, INSERT, UPDATE, DELETE ON sample_items TO engine_app;

-- A system table. It goes on the system table list and holds no business content.
CREATE TABLE sample_schedule (
    tenant_id text NOT NULL,
    job_id    text NOT NULL
);
GRANT SELECT ON sample_schedule TO engine_app;

-- A system function. It goes on the system function list and reads only what its own policy and column grant open up.
CREATE POLICY system_read ON sample_items FOR SELECT TO engine_system USING (true);
GRANT SELECT (item_id) ON sample_items TO engine_system;

-- PL/pgSQL, because migrate runs with row_security off and PostgreSQL would refuse a LANGUAGE sql body that reads a
-- forced table. It also means nothing checks the body here, so every system function needs a test that calls it.
CREATE FUNCTION sample_find(wanted text) RETURNS text
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = engine, pg_temp
    AS $$
BEGIN
    RETURN (SELECT item_id FROM sample_items WHERE item_id = wanted);
END
$$;

-- Revoke before the handover. Afterwards engine_owner no longer owns it, and a revoke does nothing without an error.
REVOKE ALL ON FUNCTION sample_find(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION sample_find(text) TO engine_app;

-- The new owner needs CREATE on the schema for the handover and at no other time.
GRANT CREATE ON SCHEMA engine TO engine_system;
ALTER FUNCTION sample_find(text) OWNER TO engine_system;
REVOKE CREATE ON SCHEMA engine FROM engine_system;
