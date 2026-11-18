-- Geo tables, LIST-partitioned by release_id: one partition per release.
-- Every query filters on a single release_id, so the planner prunes to one partition.

CREATE TABLE place (
    release_id   bigint NOT NULL,
    id           text   NOT NULL,
    osm_id       text,
    name         text   NOT NULL,
    name_he      text,
    name_en      text,
    name_norm    text,
    name_he_norm text,
    name_en_norm text,
    category     text   NOT NULL,
    geom         geometry(Point, 4326) NOT NULL,
    h3_r9        bigint NOT NULL,
    PRIMARY KEY (release_id, id)
) PARTITION BY LIST (release_id);

CREATE TABLE address (
    release_id      bigint NOT NULL,
    id              text   NOT NULL,
    street          text,
    street_he       text,
    street_en       text,
    street_norm     text,
    housenumber     text,
    housenumber_int int,
    geom            geometry(Point, 4326) NOT NULL,
    PRIMARY KEY (release_id, id)
) PARTITION BY LIST (release_id);

CREATE TABLE boundary (
    release_id bigint NOT NULL,
    id         text   NOT NULL,
    name       text   NOT NULL,
    type       text   NOT NULL CHECK (type IN ('ADMIN','REGULATORY')),
    origin     text   NOT NULL CHECK (origin IN ('osm','synthetic')),
    geom       geometry(MultiPolygon, 4326) NOT NULL,
    PRIMARY KEY (release_id, id)
) PARTITION BY LIST (release_id);

CREATE TABLE road_node (
    release_id bigint NOT NULL,
    id         bigint NOT NULL,
    geom       geometry(Point, 4326) NOT NULL,
    PRIMARY KEY (release_id, id)
) PARTITION BY LIST (release_id);

CREATE TABLE road_edge (
    release_id bigint NOT NULL,
    id         bigint NOT NULL,
    from_node  bigint NOT NULL,
    to_node    bigint NOT NULL,
    oneway     boolean NOT NULL,
    length_m   float8 NOT NULL,
    speed_kmh  float8 NOT NULL,
    highway    text,
    geom       geometry(LineString, 4326) NOT NULL,
    PRIMARY KEY (release_id, id)
) PARTITION BY LIST (release_id);

-- Containment and KNN ordering: geometry GiST.
CREATE INDEX place_geom_gist     ON place     USING gist (geom);
CREATE INDEX address_geom_gist   ON address   USING gist (geom);
CREATE INDEX boundary_geom_gist  ON boundary  USING gist (geom);
CREATE INDEX road_edge_geom_gist ON road_edge USING gist (geom);

-- Distances in metres: queries are written as ST_DWithin(geom::geography, ...),
-- which can only use an index on that same expression.
CREATE INDEX place_geog_gist     ON place     USING gist ((geom::geography));
CREATE INDEX address_geog_gist   ON address   USING gist ((geom::geography));
CREATE INDEX road_edge_geog_gist ON road_edge USING gist ((geom::geography));

-- H3 corridor strategy.
CREATE INDEX place_h3_r9 ON place (h3_r9);

-- Fuzzy text (trigram).
CREATE INDEX place_name_norm_trgm    ON place   USING gin (name_norm gin_trgm_ops);
CREATE INDEX place_name_he_norm_trgm ON place   USING gin (name_he_norm gin_trgm_ops);
CREATE INDEX place_name_en_norm_trgm ON place   USING gin (name_en_norm gin_trgm_ops);
CREATE INDEX address_street_trgm     ON address USING gin (street_norm gin_trgm_ops);

UPDATE schema_version SET version = 2;
