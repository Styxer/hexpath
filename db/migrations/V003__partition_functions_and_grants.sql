-- Creating or dropping a partition requires owning the parent table. Rather than
-- giving the pipeline ownership, two SECURITY DEFINER functions owned by geo_owner
-- do exactly that and nothing else; geo_writer may only call them.

CREATE FUNCTION hexpath_create_partitions(p_release bigint) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['place','address','boundary','road_node','road_edge'] LOOP
        EXECUTE format('CREATE TABLE IF NOT EXISTS %I PARTITION OF %I FOR VALUES IN (%s)',
                       t || '_r' || p_release, t, p_release);
    END LOOP;
END $$;

CREATE FUNCTION hexpath_drop_partitions(p_release bigint) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['place','address','boundary','road_node','road_edge'] LOOP
        IF to_regclass(t || '_r' || p_release) IS NOT NULL THEN
            EXECUTE format('ALTER TABLE %I DETACH PARTITION %I', t, t || '_r' || p_release);
            EXECUTE format('DROP TABLE %I', t || '_r' || p_release);
        END IF;
    END LOOP;
END $$;

REVOKE ALL ON FUNCTION hexpath_create_partitions(bigint) FROM PUBLIC;
REVOKE ALL ON FUNCTION hexpath_drop_partitions(bigint)   FROM PUBLIC;
GRANT EXECUTE ON FUNCTION hexpath_create_partitions(bigint) TO geo_writer;
GRANT EXECUTE ON FUNCTION hexpath_drop_partitions(bigint)   TO geo_writer;

-- Pipeline: read and write data, never DDL. Service: read only.
-- Tables are listed explicitly so PostGIS's own catalogue tables are left alone.
-- Privileges on a partitioned parent cover rows reached through it, so partitions
-- created later need no grants of their own.
GRANT SELECT, INSERT, UPDATE
    ON schema_version, dataset_release, dataset_event,
       place, address, boundary, road_node, road_edge
    TO geo_writer;
GRANT USAGE ON SEQUENCE dataset_event_id_seq, dataset_release_id_seq TO geo_writer;
GRANT SELECT
    ON schema_version, dataset_release, dataset_event,
       place, address, boundary, road_node, road_edge
    TO geo_reader;

UPDATE schema_version SET version = 3;
