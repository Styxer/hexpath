-- Release bookkeeping, outbox and schema version.
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE schema_version (version int NOT NULL);
INSERT INTO schema_version VALUES (0);

CREATE TABLE dataset_release (
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    input_hash       text NOT NULL,
    source           text NOT NULL,
    source_timestamp timestamptz,
    created_at       timestamptz NOT NULL DEFAULT now(),
    published_at     timestamptz,
    retired_at       timestamptz,
    status           text NOT NULL
                     CHECK (status IN ('STAGED','ACTIVE','RETIRED','REJECTED','ROLLED_BACK')),
    stats            jsonb,
    report           jsonb,
    override         jsonb
);

-- The same input may be rejected and later re-run (for example with an override),
-- so uniqueness of the input hash excludes REJECTED rows.
CREATE UNIQUE INDEX release_input_hash ON dataset_release (input_hash) WHERE status <> 'REJECTED';

-- Exactly one ACTIVE release: every ACTIVE row indexes the same key (true).
CREATE UNIQUE INDEX one_active ON dataset_release ((true)) WHERE status = 'ACTIVE';

-- Transactional outbox: written in the same transaction as a status flip.
CREATE TABLE dataset_event (
    id         bigserial PRIMARY KEY,
    release_id bigint,
    type       text NOT NULL,
    payload    jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    relayed_at timestamptz
);

UPDATE schema_version SET version = 1;
