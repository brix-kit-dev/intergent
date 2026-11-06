-- The engine's database roles and schema, from blueprint 7.6.
-- A database administrator runs this once per cell, before the first migration,
-- so that migrate needs no superuser rights:
--
--   psql -v ON_ERROR_STOP=1 -v owner_password=... -v app_password=... -d engine -f create-roles.sql

-- Owns the system functions and cannot log in.
CREATE ROLE engine_system NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;

-- Used by migrate only. It belongs to engine_system so it can hand functions over. NOINHERIT keeps it
-- from inheriting the read policies of engine_system, which would read tenant tables with no context.
CREATE ROLE engine_owner LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS
    PASSWORD :'owner_password' IN ROLE engine_system;

-- Used by api, worker and admin. Owns nothing, so row-level security always applies to it.
CREATE ROLE engine_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS
    PASSWORD :'app_password';

-- Nothing is open to everyone. Revoking TEMPORARY also stops a temporary table shadowing a real one.
REVOKE ALL ON DATABASE :"DBNAME" FROM PUBLIC;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT CONNECT ON DATABASE :"DBNAME" TO engine_owner, engine_app;

CREATE SCHEMA engine AUTHORIZATION engine_owner;
GRANT USAGE ON SCHEMA engine TO engine_system, engine_app;

ALTER ROLE engine_owner SET search_path = engine;
ALTER ROLE engine_app SET search_path = engine;
