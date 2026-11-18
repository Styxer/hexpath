# Architecture

hexpath is a slice of a geo data platform for central Tel Aviv-Yafo: a Python pipeline that turns
OpenStreetMap data into versioned, validated releases in PostGIS, and a Spring Boot service that
answers geo queries over whichever release is active.

```
            Overpass API (rarely)               GitHub Actions
                  │  fetch-osm.yml → review branch → merge
                  ▼
   data/osm/tel-aviv-center.json.gz  (committed, ODbL)
                  │
     ┌────────────▼─────────────┐        role geo_writer
     │  geo-pipeline (Python)   │──────────────────────────────┐
     │  extract → transform →   │   COPY into a new partition  │
     │  validate → reconcile →  │   flip ACTIVE + outbox row   │
     │  gate → stage → publish  │   + NOTIFY, one transaction  │
     └──────────────────────────┘                              ▼
                                              ┌─────────────────────────────┐
                                              │  PostgreSQL 17 / PostGIS 3.5│
                                              │  tables LIST-partitioned    │
                                              │  by release_id              │
                                              └─────────────▲───────────────┘
                                                            │ role geo_reader (SELECT only)
                                                            │ LISTEN dataset_events + 10 s poll
                                              ┌─────────────┴───────────────┐
   browser (demo page at /) ────────────────► │  geo-service (Spring Boot)  │
   consuming teams (REST /v1) ──────────────► │  immutable release snapshot │
                                              │  swapped atomically         │
                                              └─────────────────────────────┘
```

## Components

| Part | Where | Responsibility |
|---|---|---|
| Schema | `db/roles.sql`, `db/migrations/` | Three roles, partitioned tables, partition functions. One source of truth for both services. |
| Pipeline | `geo-pipeline/` | Builds a candidate release, refuses bad ones, publishes, rolls back, cleans up. |
| Service | `geo-service/` | Read-only REST API over the active release; the demo page. |
| Contract | `api/openapi.yaml` | The published API, generated from the code and enforced by a test. |
| End-to-end | `e2e/` | The whole stack on real data, including the rollback drill. |
| Benchmark | `bench/corridor/` | PostGIS vs H3 for route-aware search (ADR 0003). |

## Data model

- `dataset_release`: one row per candidate release, with status `STAGED`, `ACTIVE`, `RETIRED`,
  `REJECTED`, `ROLLED_BACK` or `PURGED` (data dropped by `gc`). A partial unique index allows exactly
  one `ACTIVE` row.
- `place`, `address`, `boundary`, `road_node`, `road_edge`: LIST-partitioned by `release_id`, one
  partition per release (`place_r42`, ...). Every query filters on one `release_id`, so PostgreSQL reads
  one partition. Dropping an old release is `DROP TABLE`, not a large `DELETE`.
- `dataset_event`: the outbox. Written in the same transaction as every status change.
- Indexes: geometry GiST (containment, nearest), GiST on `(geom::geography)` (distances in metres),
  btree on `h3_r9`, trigram GIN on normalised names. `SpatialIndexGuardIT` proves each query can use
  the index written for it.

Details and alternatives: [ADR 0001](adr/0001-postgis.md), [ADR 0002](adr/0002-release-partitions-outbox.md).

## How a release is published

1. `python -m geopipeline run --source osm` reads the committed extract and the synthetic zones.
2. It takes a Postgres advisory lock, so only one run happens at a time.
3. If the same input was already accepted (same SHA-256 input hash), it stops: nothing to do.
4. It validates the data (geometry, coverage, required fields, overlaps, road connectivity) and
   reconciles it against the active release (added, removed, moved, renamed, boundary area change).
5. The gate rejects the release on any `ERROR` finding or reconcile breach, unless `--override` is given
   with a `--reason`, which is recorded.
6. Accepted data is COPYed into new partitions (`STAGED`), then one transaction retires the old release,
   activates the new one, writes a `RELEASE_ACTIVATED` outbox row and sends `NOTIFY dataset_events`.

## How a request is served

Take `POST /v1/search/along-route`:

1. `RequestContextFilter` assigns `X-Request-Id`, reads the current snapshot **once**, pins it to the
   request and sets `X-Dataset-Release`. With no snapshot loaded, `/v1` returns `503 service-not-ready`;
   bodies over 256 KB get `413`.
2. The controller receives the pinned `DatasetSnapshot` as a parameter, validates the route
   (`RouteInput`: 2+ distinct positions, at most 1,000, at most 50 km) and checks every vertex against
   the release's coverage (`CoverageGuard`; swapped coordinates give `422 out-of-coverage`).
3. `PostgisCorridorSearch` runs one query against the pinned release's partition.
4. Errors anywhere become RFC 9457 problems (`GlobalExceptionHandler`), still carrying both headers.

Because the release id comes from the pinned snapshot, a request never mixes two releases, even if a
new one is activated while it runs.

## How the service learns about a new release

`DatasetEventListener` holds its own connection with `LISTEN dataset_events`. Every wake-up, whether a
notification or the 10-second timeout, compares `max(dataset_event.id)` with the snapshot's last event
id. A newer event means: load the new snapshot, then swap it in atomically. A missed notification is
therefore caught within 10 seconds, and a dropped connection is retried with backoff up to 30 seconds.
Readiness (`/actuator/health/readiness`) stays DOWN until the first snapshot loads.

## Security

- The service connects as `geo_reader`, which can only SELECT. A bug or an injection in the service
  cannot change data.
- The pipeline connects as `geo_writer`: insert and update, no DDL. Creating and dropping partitions
  goes through two `SECURITY DEFINER` functions owned by `geo_owner` that do only that.
- Migrations run as `geo_owner`. Passwords come from the environment; `.env.example` holds
  development-only values.
- SQL is assembled from fixed fragments only; user input is always a bind parameter.
