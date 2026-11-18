-- A release whose partitions were dropped by gc is PURGED: it can never be rolled back to,
-- and its input is evaluated again if re-run. ROLLED_BACK inputs are evaluated again too.

ALTER TABLE dataset_release DROP CONSTRAINT dataset_release_status_check;
ALTER TABLE dataset_release ADD CONSTRAINT dataset_release_status_check
    CHECK (status IN ('STAGED','ACTIVE','RETIRED','REJECTED','ROLLED_BACK','PURGED'));

DROP INDEX release_input_hash;
CREATE UNIQUE INDEX release_input_hash ON dataset_release (input_hash)
    WHERE status NOT IN ('REJECTED','ROLLED_BACK','PURGED');

-- Defence in depth: never drop the data the service is serving.
CREATE OR REPLACE FUNCTION hexpath_drop_partitions(p_release bigint) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    t text;
BEGIN
    IF EXISTS (SELECT 1 FROM dataset_release WHERE id = p_release AND status = 'ACTIVE') THEN
        RAISE EXCEPTION 'refusing to drop partitions of the ACTIVE release %', p_release;
    END IF;
    FOREACH t IN ARRAY ARRAY['place','address','boundary','road_node','road_edge'] LOOP
        IF to_regclass(t || '_r' || p_release) IS NOT NULL THEN
            EXECUTE format('ALTER TABLE %I DETACH PARTITION %I', t, t || '_r' || p_release);
            EXECUTE format('DROP TABLE %I', t || '_r' || p_release);
        END IF;
    END LOOP;
END $$;

UPDATE schema_version SET version = 4;
