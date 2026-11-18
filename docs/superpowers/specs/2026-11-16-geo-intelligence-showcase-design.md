# Geo Intelligence Showcase: Design (rev 2)

- Rev 1: 2026-11-16, initial design.
- Rev 2: 2026-11-17, all findings from the senior review applied (see section 17 for the change log).
- Status: open questions answered by Ofir on 2026-11-17; ready for the implementation plan.
- Project name: **hexpath** (H3 hexagons + routes).
- Source: job description "14729-1 Software Engineer II, Geo Intelligence (Marketplace)".

## 1. Intent

Ofir is applying for a Java geo-platform role and comes from C#/.NET. One project has to do three jobs:

1. **Show the hiring team**: runs end to end from a README in under 10 minutes, with a map demo.
2. **Talk through in an interview**: small enough that Ofir can explain every component and every trade-off.
3. **Hands-on learning**: uses the real stack the role uses (Java, Spring Boot, PostGIS, H3, Python), and Ofir writes one meaningful component himself.

## 2. Goals, non-goals, anti-goals

**Goals**
- A working slice of a geo platform for central Tel Aviv-Yafo on real OpenStreetMap data: places, boundaries, route-aware search, routing, address geocoding.
- A data pipeline that refuses to publish bad data, with versioned releases, rollback and an event outbox.
- Tests, measured service levels and operational documents treated as first-class deliverables.
- At least one design decision settled by a benchmark rather than an opinion (H3 vs PostGIS corridor search).

**Non-goals**
- Production scale, multi-region, authentication, a real Kafka cluster or a real search engine. Their integration points exist and are documented (outbox, relay interface, ADR).
- Navigation-grade routing (turn restrictions, live traffic).
- A .NET component.

**Anti-goals** (failure even if it technically works)
- Code Ofir cannot explain line by line. Plain SQL and small classes over framework magic.
- Starting the demo needs anything beyond Docker, a JDK and Python.
- Anything that looks like Booking.com's product or branding, or mentions the company in the public repo.
- Claims the code does not back up: no "Kafka" without Kafka, no SLO numbers without a measurement, no "geocoding" that is only fuzzy text search.
- Interview preparation material in the public repo.

## 3. Constraints

- **Target machine**: Windows, root `C:\Users\Ofir\Desktop\JobHunt\Booking`. Scripts are PowerShell first, bash equivalents included.
- **Assumed installed** (to be confirmed by Ofir): JDK 21 or newer, Python 3.10 or newer, Docker Desktop.
- **Verification**: Claude cannot reach Maven Central from either sandbox. Builds and tests run in **GitHub Actions on a private repository** (runners have Maven Central and Docker, so Testcontainers works). Claude reads CI logs with `gh` and fixes failures without Ofir in the loop. Creating the repo needs Ofir's go-ahead. Fallback if he declines: Ofir runs `scripts\verify.ps1` and shares the output.
- **External services**: OpenStreetMap data comes from the Overpass API once, is committed as a fixture, and can be refreshed by a manual CI workflow. No paid APIs, no secrets in the repo.

## 4. Approaches considered

| | A. Self-contained service | B. Realistic slice (chosen) | C. Full platform |
|---|---|---|---|
| Stack | Spring Boot, in-memory JTS + H3 | Spring Boot + PostGIS + H3, Python pipeline | B + Kafka, search engine, Prometheus, Grafana |
| Trade-off | Easy to explain, skips spatial databases (a required qualification) | Covers nearly every JD line; streaming stops at an outbox | Covers everything; fragile demo, too broad to know deeply |

Ofir chose **B**.

## 5. Decided by Ofir

- Purpose: all three of show, talk through, learn.
- Approach B.
- Location: `C:\Users\Ofir\Desktop\JobHunt\Booking`.
- 2026-11-17: apply every finding of the senior review.
- 2026-11-17: Docker Desktop installed; latest JDK installed (so JDK 21+ is satisfied).
- 2026-11-17: private GitHub repository for CI approved; Claude picks the name (hexpath).
- 2026-11-17: Ofir writes AStarRoutingEngine, as recommended.

Everything else is Claude's call unless marked, open to correction.

## 6. Architecture overview

```
            Overpass API (once)          GitHub Actions (CI)
                  |
                  v
   data/osm/*.json.gz  (committed, ODbL)
                  |
     +------------v-------------+        geo_writer role
     |  geo-pipeline (Python)   |------------------------------+
     |  extract > transform >   |                              |
     |  validate > reconcile >  |   COPY into new partition    |
     |  gate > publish          |   flip ACTIVE + outbox row   |
     +--------------------------+   + NOTIFY, one transaction  |
                                                               v
                                              +-----------------------------+
                                              |  PostGIS (docker compose)   |
                                              |  partitioned by release_id  |
                                              +-------------^---------------+
                                                            | geo_reader role (SELECT only)
                                                            | LISTEN dataset_events + 10 s poll
                                              +-------------+---------------+
   browser (Leaflet demo page) -------------> |  geo-service (Spring Boot)  |
   consuming teams (REST /v1) --------------> |  snapshot: release id +     |
                                              |  road graph, atomic swap    |
                                              +-----------------------------+
```

## 7. Data and database

### 7.1 Schema ownership and roles

- SQL migrations live in one place: `db/migrations/V###__name.sql`. They are applied by `geopipeline migrate` as `geo_owner`.
- A `schema_version` table records the applied version. Both the pipeline and the service check it on startup and fail fast with a clear message on mismatch.
- Java integration tests apply the same SQL files to their Testcontainers database, so there is one source of truth.
- Roles, created by `docker/initdb/01-roles.sql`:
  - `geo_owner`: DDL, migrations only.
  - `geo_writer`: the pipeline; insert, COPY, partition attach and detach, release status changes.
  - `geo_reader`: the service; SELECT only. A bug or injection in the service cannot modify data.
- Credentials come from environment variables, with `.env.example` holding dev-only defaults, stated as such.

### 7.2 Tables

- `dataset_release(id, input_hash unique, source, source_timestamp, created_at, published_at, status, stats jsonb, report jsonb, override jsonb)`.
  - status: STAGED, ACTIVE, RETIRED, REJECTED.
  - **Exactly one ACTIVE**, enforced by `CREATE UNIQUE INDEX one_active ON dataset_release ((true)) WHERE status = 'ACTIVE'`.
  - `source_timestamp` is the OSM data timestamp (`osm3s.timestamp_osm_base` from Overpass), used for freshness.
- `place(release_id, id, osm_id, name, name_he, name_en, category, geom geometry(Point,4326), h3_r9 bigint)`
- `address(release_id, id, street, street_he, street_en, street_norm, housenumber, housenumber_int, geom geometry(Point,4326))`
- `boundary(release_id, id, name, type, origin, geom geometry(MultiPolygon,4326))`
  - type: ADMIN or REGULATORY.
  - origin: `osm` or `synthetic`. Regulatory zones are fictional and labelled synthetic everywhere they appear.
- `road_node(release_id, id, geom)` and `road_edge(release_id, id, from_node, to_node, oneway, length_m, speed_kmh, highway, geom geometry(LineString,4326))`
- `dataset_event(id bigserial, release_id, type, payload jsonb, created_at, relayed_at)`: the outbox.

### 7.3 Partitioning and retention

- `place`, `address`, `boundary`, `road_node` and `road_edge` are **LIST-partitioned by `release_id`**.
- Publishing creates partitions for the new release, loads them with COPY, builds indexes (partitioned indexes cascade), and only then flips status.
- Every service query filters on a single `release_id`, so the planner prunes to one partition. Index size does not grow with release history.
- **Retention**: keep ACTIVE plus the 2 most recent RETIRED releases; rollback targets the newest RETIRED one. `geopipeline gc` detaches and drops older partitions, which takes milliseconds rather than a large `DELETE`. Partitions of REJECTED releases are dropped immediately; their report rows are kept.

### 7.4 Indexes

- Containment (`ST_Contains`, boundaries): GiST on `geom` (geometry).
- Metric distance (`ST_DWithin` in metres): **expression GiST index on `(geom::geography)`** for places, addresses and road edges. Every metric query is written as `ST_DWithin(geom::geography, :g::geography, :m)` so it matches that index.
- KNN nearest-neighbour (`<->`): GiST on `geom`. It is used for ordering only, never as a distance in metres.
- `btree(h3_r9)` on place, for the H3 corridor strategy.
- `pg_trgm` GIN on `street_norm` and on normalised place names.
- **Guard test**: an integration test runs `EXPLAIN (FORMAT JSON)` on every spatial query with `enable_seqscan = off` and asserts the intended index appears. That proves each query can use its index; with a small dataset, the planner is free to choose a sequential scan otherwise.

### 7.5 Hebrew text

- The database uses the PostGIS image's default `en_US.utf8` locale, where Hebrew letters count as alphanumeric for `pg_trgm`. A day-one spike test asserts `similarity('דיזנגוף','דיזינגוף') > 0.3`. If it fails, normalisation moves fully into the pipeline.
- Pipeline normalisation (`*_norm` columns):
  - Strip niqqud; normalise geresh and gershayim.
  - Drop street-type words: "רחוב", "רח'", "שדרות", "St", "Street", "Blvd".
  - Lowercase, collapse whitespace.
- The service applies the same normalisation to queries. The rules live in one documented table; Java and Python each implement it and share a golden test file of input to expected output, so they cannot drift.

## 8. Python pipeline (geo-pipeline)

CLI: `python -m geopipeline <command>`

| Command | Purpose |
|---|---|
| `migrate` | Apply `db/migrations` as `geo_owner` |
| `fetch-osm` | Download the central Tel Aviv bounding box from Overpass into `data/osm/` (run once, or by the manual CI workflow) |
| `run --source osm\|fixture:<name> [--override --reason "..."]` | Full pipeline, stopping before publish unless the gate passes |
| `publish <release>` / `rollback` / `gc` | Release lifecycle |
| `relay` | Drain `dataset_event` through an `OutboxRelay` (logging adapter included; Kafka adapter documented, not built) |
| `export-seed` | Write the small Java test seed (see 10.2) |
| `report <release>` | Print the validation and reconcile report |

Stages:

1. **Extract**: from the committed OSM file or a named fixture.
2. **Transform**:
   - Map OSM tags to categories.
   - Normalise names into Hebrew and English (7.5).
   - Compute `h3_r9`.
   - Build the road graph: split ways at intersections; `oneway` from tags; speed from `maxspeed`, else defaults per highway class.
   - Parse `addr:*` into the address table.
   - Take admin boundaries from OSM where present; add the synthetic regulatory zones.
3. **Validate** (each rule has severity ERROR or WARN):
   - Geometry validity.
   - Coordinates inside the coverage envelope.
   - Required fields present.
   - Duplicate places within 5 m with the same normalised name.
   - Overlapping boundaries of the same type.
   - Road graph connectivity: share of nodes in the largest strongly connected component.
   - Address house numbers parse.
4. **Reconcile** against the ACTIVE release:
   - Counts added, removed, moved more than 50 m, and renamed, per entity type.
   - Boundary area change per boundary (this rule catches the postmortem scenario).
   - Breach when a change exceeds the configured threshold.
   - **Bootstrap**: with no ACTIVE release, reconcile reports "no baseline" and passes. Only the first release ever takes this path.
5. **Gate**: any ERROR or reconcile breach marks the release REJECTED and stops.
   - `--override` requires `--reason`.
   - It records OS user, timestamp, reason and the overridden findings in `dataset_release.override` and in the report.
6. **Publish**, in one transaction:
   - Flip the old ACTIVE to RETIRED and the new release to ACTIVE.
   - Insert a `dataset_event` (`RELEASE_ACTIVATED`).
   - `NOTIFY dataset_events`.

Robustness:

- **Single run at a time**: `pg_try_advisory_lock` held for the whole run; a second run exits immediately with a clear message.
- **Idempotent**: `input_hash` is the SHA-256 of the normalised input. Re-running the same input returns the existing release and does nothing.
- **Bulk load**: COPY through psycopg 3.
- **Reports**: every run writes JSON and Markdown to `data/reports/` (git-ignored) and stores the JSON in `dataset_release.report`.

Deferred to phase 3 (section 12): AI-ready JSONL export.

## 9. Java service (geo-service)

Java 21, Spring Boot 4.1.x, Maven wrapper. Layers: `api` (controllers, DTOs, problem details) to `domain` (use cases, ports) to `infra` (JDBC repositories with `NamedParameterJdbcTemplate` and explicit SQL; H3; in-memory graph; LISTEN/NOTIFY listener).

### 9.1 Release snapshot and refresh

- `DatasetSnapshot` is immutable: the active release id, its road graph, and its coverage envelope. It is held in an `AtomicReference`.
- **Refresh**: a dedicated JDBC connection runs `LISTEN dataset_events` on a background thread (pgjdbc `getNotifications(timeout)`). A 10-second poll of `max(dataset_event.id)` is the fallback in case a notification is missed or the connection drops.
- **Swap**: on a new event, the next snapshot is built off-thread and swapped in atomically. In-flight requests finish on the snapshot they started with.
- **Pinning**: each request reads the snapshot once and passes its `release_id` into every SQL query. One request never mixes data from two releases.
- **Readiness**: `/actuator/health/readiness` stays DOWN until the first snapshot loads.
- Retention (7.3) keeps the previous release's partitions, so a request pinned to the old release never hits a dropped table.

### 9.2 Endpoints

All under `/v1`, JSON responses, GeoJSON geometries in lon,lat order (query parameters are `lat`/`lon`, as documented), RFC 9457 problem responses. Every response carries:

- `X-Dataset-Release`: the release that served it.
- `X-Request-Id`: echoed if the caller sent one, generated otherwise.

| Endpoint | Purpose | Technique |
|---|---|---|
| `GET /places/search?q,lat,lon,radius_m,category,limit,cursor` | Place search | `ST_DWithin` on geography + trigram; score = text similarity, then distance; keyset cursor |
| `GET /places/{id}` | Place detail | |
| `GET /boundaries/containing?lat,lon,type` | Admin and regulatory lookup | `ST_Contains`, GiST |
| `POST /search/along-route` | Places within N metres of a route | `CorridorSearch` port, two strategies (9.3) |
| `GET /routes?from=lat,lon&to=lat,lon` | Route, distance, duration | `RoutingEngine` port (9.4) |
| `GET /geocode?q` | Address to point | Address parser + street trigram + exact house number (9.5) |
| `GET /reverse-geocode?lat,lon` | Point to nearest address + containing boundaries | KNN on address, then `ST_Contains` |
| `GET /dataset` | Active release, source timestamp, counts | |
| `GET /status` | The service's own view: data age, in-process p95 and error rate per endpoint | Micrometer |
| `/actuator/health`, `/actuator/prometheus` | Liveness, readiness, metrics | |

**Input limits**, returned as 400 or 422 problems with distinct `type` URIs:

- `lat`/`lon` must be in range.
- Points outside the coverage envelope return `out-of-coverage`.
- `limit` ≤ 100, `radius_m` ≤ 5,000, corridor buffer ≤ 2,000 m.
- Route ≤ 1,000 vertices and ≤ 50 km.
- Request body ≤ 256 KB.

### 9.3 Route-aware search: two strategies, settled by benchmark

`CorridorSearch` has two implementations behind one interface. A `strategy` query parameter exists for the benchmark; it is hidden from the public docs and defaults to the winner.

- **PostgisCorridorSearch**: `ST_DWithin(p.geom::geography, :route::geography, :buffer)`. Exact, one indexed query.
- **H3CorridorSearch**:
  1. Densify the route so vertices are no more than half an r9 cell edge apart (about 87 m).
  2. Map each vertex to its r9 cell.
  3. Expand with `gridDisk(k)`, where k = ceil(buffer / minimum r9 edge length) + 1, so the cover is a guaranteed superset.
  4. Fetch candidates with `h3_r9 = ANY(:cells)`.
  5. Filter exactly by distance to the route.

Both strategies rank results the same way:

- **Default ranking**: distance to the route line (`ST_Distance` on geography), with `ST_LineLocatePoint` returned as "how far along the route".
- **Optional `rank=detour`**: true detour, computed only for the top 10 candidates: `route(from, p) + route(p, to) - route(from, to)`, using the routing engine. The cap is part of the API, so cost is bounded.

**Benchmark** (`bench/corridor/`):

- 200 random routes inside the coverage area, at buffers of 100, 300 and 1,000 m.
- Measures p50 and p95 latency, candidate count before exact filtering, and **result equality** with the PostGIS strategy as ground truth.
- Results are recorded in `docs/adr/0003-corridor-search.md`.
- That ADR states which strategy won here and where H3 wins instead: a cell-keyed store or search engine without PostGIS, precomputed per-cell aggregates, or joining datasets that share no geometry index.

### 9.4 Routing

- The `RoutingEngine` port. The graph is loaded per snapshot: a few thousand edges for central Tel Aviv, held in memory.
- **Snapping**: nearest **edge**, not nearest node. KNN on `road_edge`, then `ST_LineLocatePoint`, splitting the edge into two virtual nodes so a route can start mid-block. One-way edges are respected.
- **DijkstraRoutingEngine**: written by Claude. It is the baseline and the correctness oracle.
- **AStarRoutingEngine**: **written by Ofir**. Claude provides the interface, an empty class and the tests first; the tests assert A* returns the same cost as Dijkstra on 500 random pairs and expands fewer nodes. The heuristic is haversine distance divided by the network's maximum speed, so it never overestimates.
- `docs/adr/0004-routing.md`: why in-memory, and when to move to pgRouting (larger graphs, routing inside SQL) or a dedicated engine such as GraphHopper (Java), OSRM or Valhalla (turn restrictions, traffic, continental scale).

### 9.5 Geocoding

- **Parser**: "Dizengoff 50", "50 Dizengoff St", "דיזנגוף 50" and "רח' דיזנגוף 50, תל אביב" all become street + house number + optional city.
- **Match**:
  1. Fuzzy-match the street with trigram on `street_norm`.
  2. Match the house number exactly.
  3. If the number is missing, interpolate between the nearest lower and higher numbers on that street.
  4. Otherwise fall back to the street centroid.
- The response includes `match_type` (EXACT, INTERPOLATED or STREET_ONLY) and a `confidence` value. Consumers can tell how good a match is, and the integration guide explains each value.
- Behind the `GeocodingProvider` port, so a vendor adapter can be added later (phase 3).

### 9.6 Observability

- Structured JSON logs (Boot's built-in structured logging, ECS format). Every line carries `requestId`, `datasetRelease`, endpoint and latency.
- Micrometer timers per endpoint with percentile histograms; a gauge for data age (now minus `source_timestamp`); a counter of snapshot swaps; a timer for snapshot load.
- `/status` is labelled as the service's own view. Availability is **not** reported by the service itself (see 10.5).

## 10. Quality

### 10.1 Python

pytest coverage:
- Each validation rule with a passing and a failing input.
- Reconcile thresholds, bootstrap, gate and override.
- Idempotency and the advisory lock (against Testcontainers PostGIS via `testcontainers-python`).
- The shared normalisation golden file.

### 10.2 Java

- **Unit tests**:
  - The H3 cover is a superset of the exact buffer (property test with jqwik over random routes).
  - Dijkstra vs A* equivalence.
  - Snapping; the address parser; the shared normalisation golden file.
- **Integration tests**: Testcontainers PostGIS, the same `db/migrations`, and a **seed generated by the pipeline** (`export-seed`, committed). CI regenerates the seed and fails if it differs from the committed copy, so the Python-to-Java seam is tested on every build.
- **Index guard tests**: section 7.4.
- **Snapshot refresh test**: publish a second release while the service runs; assert the swap and the change in `X-Dataset-Release`.

### 10.3 API contract

- `api/openapi.yaml` is committed and is the contract.
- A test fetches the spec springdoc generates and compares it to the committed file with openapi-diff:
  - A **breaking** difference fails the build.
  - **Any** difference also fails until the committed file is updated, so every API change is deliberate and shows up in review.
- An e2e suite validates live responses against the committed spec.

### 10.4 End to end (CI job and `scripts/demo`)

1. `docker compose up`.
2. `geopipeline migrate`, then `run --source osm` and publish.
3. Start the service.
4. Pytest e2e: every endpoint, response schemas, headers, and limit violations.
5. Publish the **bad-boundary fixture**: the gate rejects it.
6. Force it with `--override` and confirm the service swaps to it.
7. `rollback` and confirm the service swaps back.

Steps 5 to 7 are exactly the postmortem scenario and the runbook procedure, executed.

### 10.5 Service levels (`docs/slo.md`)

- **SLIs**:
  - Latency: p95 per endpoint, from server-side histograms.
  - Availability: share of successful probe requests. In production this is measured externally (synthetic probes, load balancer logs); the doc says so and does not claim availability numbers for this showcase.
  - Freshness: now minus the OSM `source_timestamp` of the ACTIVE release.
- **Targets**, for example: search p95 < 150 ms, routing p95 < 300 ms, freshness < 7 days.
- **Evidence**: a Locust load test (`bench/load/`) at a stated request rate. Results, machine spec and date are written into `slo.md`. Targets the run misses are reported as missed.

### 10.6 CI (`.github/workflows/`)

| Workflow | Runs |
|---|---|
| `ci.yml`, on push and PR | Python job; Java job (matrix: JDK 21 and 25, `mvn verify` with Testcontainers); e2e job (10.4); seed-drift check |
| `bench.yml`, manual | Corridor benchmark and load test; uploads results as artifacts |
| `fetch-osm.yml`, manual | Refreshes `data/osm/` and opens a PR, so data changes go through review: governed change |

## 11. Documents

**Public, in `docs/`:**
- `architecture.md`: the diagram in section 6 and a request walkthrough.
- `adr/`:
  - 0001 PostGIS over an in-memory index.
  - 0002 Release pointer with partitions, plus outbox.
  - 0003 Corridor search (benchmark results).
  - 0004 Routing.
  - 0005 Where Kafka and a search engine plug in: the outbox relay and a search index fed from `RELEASE_ACTIVATED` events.
- `runbook.md`:
  - Bad data published: identify the release from `X-Dataset-Release` and logs, `rollback`, verify the swap.
  - Latency degradation.
  - Pipeline rejected a release.
  - Snapshot not refreshing: check LISTEN, then the poll fallback.
  - Database down.
- `postmortem-example.md`: "Regulatory boundary shipped with the wrong extent". Blameless; timeline; root cause (a reconcile rule missing for area change); fix (the rule in section 8); reproducible with the bad fixture.
- `slo.md`: section 10.5.
- `integration-guide.md`: for consuming teams. Coordinate order, `match_type` and `confidence`, pagination, error types, release header and caching (responses are stable for the same release), limits.
- `data-licensing.md`: OpenStreetMap data © OpenStreetMap contributors under ODbL 1.0. The committed extract and derived database are shared under ODbL; code is MIT. Attribution on the demo map. Synthetic regulatory zones are fictional.

**README.md**:
- Quick start, the demo script, and phase status.
- **An AI-assistance statement**: built with Claude's assistance, with AStarRoutingEngine written by Ofir, plus anything else he writes.

**Private, in `notes/` (git-ignored, never pushed):**
- `interview-walkthrough.md`: a 20-minute talk track, likely questions, known limitations.
- `dotnet-to-java.md`: ASP.NET Core, EF Core, xUnit and DI mapped to the Spring equivalents used in this code.
- `jd-coverage.md`: each JD line mapped to files, with gaps stated honestly.

## 12. Scope and phases

The project stops at the end of any phase in a coherent, demoable state.

**Phase 1, core**
- `db/` (roles, migrations, partitions, indexes); docker compose.
- Pipeline: fetch-osm, run, validate, reconcile, gate, publish, rollback, gc, outbox with logging relay, export-seed.
- Service: snapshot and refresh, places search, boundaries, `/dataset`, `/status`, headers, limits, observability.
- Along-route search with both strategies, the benchmark and ADR 0003.
- All tests for the above; CI.
- Minimal demo page: place search, and along-route search on a route the user draws by clicking points on the map.
- runbook, postmortem, slo (latency target only, until phase 2's load test), architecture, ADRs 0001–0003 and 0005, data-licensing, README.

**Phase 2**
- Routing: Dijkstra and edge snapping by Claude; A* by Ofir; ADR 0004; `rank=detour`.
- Geocoding and reverse geocoding.
- Demo page: click to reverse-geocode, routing, along-route search on a computed route.
- Locust load test and SLO evidence.
- integration-guide.

**Phase 3, optional**
- Vendor benchmark: local geocoder against Nominatim over a 50-address golden set. Nominatim's usage policy limits this to 1 request per second with attribution; recorded responses allow offline reruns.
- AI-ready JSONL export.
- Kafka relay adapter.

## 13. Versions

| Component | Version | Reason |
|---|---|---|
| Java | 21 language level; CI also runs on 25 | 25 is the newest LTS; 21 is the safer baseline for H3's native library and Testcontainers |
| Spring Boot | 4.1.x | 3.5 reached end of open-source support on 2026-06-30; 4.1 has OSS support to July 2027 |
| PostGIS | `postgis/postgis:17-3.5` | Current stable |
| H3 | h3-java 4.x, h3-py 4.x | Same cell ids on both sides |
| Python | 3.10+, psycopg 3, shapely 2 | |

Notes for the builder on Spring Boot 4:
- Jackson 3 is the default.
- Starters are more modular, so add only what is used.
- `RestTestClient` is available for API tests.

## 14. Left to the builder

- Exact SQL, ranking weights, validation thresholds, package names, the openapi-diff library.
- The exact bounding box: about 3 × 3 km of central Tel Aviv. Keep the committed extract under about 15 MB gzipped.
- Which OSM admin levels exist for Tel Aviv neighbourhoods. If they are missing, admin boundaries fall back to the city outline plus synthetic districts, labelled as such.
- Build order within each phase. Suggested: db, pipeline, seed, service read path, corridor search, tests, demo page, documents.

## 15. Open questions for Ofir

All answered on 2026-11-17 (see section 5). CI adds the JDK version Ofir has installed to the test matrix once known, so local and CI runs match.

## 16. Definition of done (phase 1)

- CI is green: Python, Java on JDK 21 and 25, e2e including reject, override and rollback, and the seed-drift check.
- `scripts\demo.ps1` on Ofir's machine shows the map with search and along-route results, and the `X-Dataset-Release` header changes after a rollback.
- ADR 0003 contains real benchmark numbers.
- No document claims anything the code or a recorded measurement does not show.

## 17. Change log (rev 1 to rev 2)

| Finding | Resolution |
|---|---|
| Service never learns about new releases | Outbox + LISTEN/NOTIFY + poll fallback; immutable snapshot swap; per-request release pinning (8, 9.1) |
| Geography queries could not use a geometry index | Expression GiST on `geom::geography`; EXPLAIN guard tests (7.4) |
| H3 unjustified; line cover and detour undefined | Two strategies, precise cover algorithm, benchmark, ADR; detour bounded to top 10 (9.3) |
| Synthetic data hurts the demo | Real OSM extract, ODbL licensing doc; synthetic only for regulatory zones and bad fixtures (3, 7.2, 11) |
| "Geocoding" was fuzzy search | Address table, parser, exact numbers, interpolation, `match_type` (9.5) |
| Hebrew trigram risk | Locale choice, day-one spike test, shared normalisation golden file (7.5) |
| "Exactly one ACTIVE" unenforced | Partial unique index (7.2) |
| Unbounded release history | Partitions by release, retention of 2, `gc` (7.3) |
| No least privilege; unclear schema ownership | `db/migrations` single source; owner, writer and reader roles; version check (7.1) |
| Pipeline robustness | Bootstrap, advisory lock, input hash idempotency, COPY, recorded override (8) |
| No way to tell which data served a response | `X-Dataset-Release` and `X-Request-Id` headers, in logs and runbook (9.2, 9.6) |
| `/slo` overclaimed | Renamed `/status`, labelled as own view; availability measured externally; freshness from source timestamp (9.2, 10.5) |
| SLO numbers unbacked | Locust load test, results recorded (10.5) |
| Python-to-Java seam untested; vague contract tests | Pipeline-generated seed with drift check; openapi-diff gate; e2e schema validation (10.2–10.4) |
| No input limits | Limits with problem types (9.2) |
| Outdated versions | Java 21 (CI also 25), Spring Boot 4.1 (13) |
| Nearest-node snapping | Nearest-edge snapping with virtual nodes (9.4) |
| Scope too large to explain | Three phases with a coherent stop after each (12) |
| Interview notes would be public; authorship questions | `notes/` git-ignored; README AI statement; Ofir writes A* (9.4, 11) |
| Verification relied on manual paste loop | Private GitHub repo + Actions, read with `gh` (3, 10.6) |
