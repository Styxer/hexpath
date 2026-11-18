-- Creates the three hexpath roles. Runs once, as the database superuser, from
-- /docker-entrypoint-initdb.d (alphabetically before the PostGIS image's own script).
-- Passwords come from the container environment; psql's \getenv needs psql 15+.
\set ON_ERROR_STOP on
\getenv owner_pw GEO_OWNER_PASSWORD
\getenv writer_pw GEO_WRITER_PASSWORD
\getenv reader_pw GEO_READER_PASSWORD

CREATE ROLE geo_owner  LOGIN PASSWORD :'owner_pw';   -- DDL and migrations only
CREATE ROLE geo_writer LOGIN PASSWORD :'writer_pw';  -- the pipeline
CREATE ROLE geo_reader LOGIN PASSWORD :'reader_pw';  -- the service: SELECT only

ALTER DATABASE hexpath OWNER TO geo_owner;
ALTER SCHEMA public OWNER TO geo_owner;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO geo_writer, geo_reader;

-- PostGIS is not a trusted extension, so the superuser creates it here.
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
