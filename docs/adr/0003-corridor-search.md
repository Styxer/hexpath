# ADR 0003: Route-aware search uses PostGIS, not an H3 cell lookup

- Status: accepted
- Date: 2026-11-18
- Evidence: `bench/corridor/run.py`, run by `.github/workflows/bench.yml`

## Context

`POST /v1/search/along-route` returns the places within N metres of a route. Two ways to find them
are implemented behind one interface (`CorridorSearch`), and both are tested to return identical results:

- **postgis**: one query, `ST_DWithin(place.geom::geography, route::geography, N)`, served by the
  expression GiST index on `(geom::geography)`.
- **h3**: compute the resolution-9 H3 cells that cover the route's buffer (densify the route, take each
  sample's cell, expand by k rings), fetch places by `h3_r9 = ANY(cells)` from a btree index, then apply
  the same exact distance filter. The cover is a guaranteed superset of the buffer
  (`H3CoverPropertyTest`, 1,000 random routes), so the exact filter only removes extra candidates.

The question was which one the endpoint should use by default, decided by measurement, not by taste.

## Measurement

- Data: the committed OpenStreetMap extract of central Tel Aviv (1,334 places; data timestamp 2026-07-15).
- 200 random routes (seed 7), 2–6 vertices, 1–5 km long, inside the coverage area shrunk by about 200 m.
- Buffers 100, 300 and 1,000 m; both strategies called per route, alternating which goes first; 3 warm-up calls each.
- Latency is server-side (`Server-Timing: corridor;dur=…`), so HTTP overhead is excluded.
  Candidates are the rows examined before the exact filter. `limit` was 100.
- Runner: GitHub Actions `ubuntu-latest`, 4 vCPU AMD EPYC 9V74, 16 GB RAM, PostGIS in Docker on the same machine.
- Run: 2026-11-18.

| buffer (m) | strategy | p50 (ms) | p95 (ms) | mean candidates | mean results | identical results |
| 100 | postgis | 2.6 | 5.11 | 65.4 | 61.4 | 100.0% |
| 300 | postgis | 4.5 | 8.01 | 210.4 | 97.7 | 100.0% |
| 1000 | postgis | 12.0 | 17.61 | 799.7 | 100 | 100.0% |

## Decision

Keep **postgis** as the default (`hexpath.corridor.default-strategy`). It was faster at every buffer
size, by a factor of about 3.5 at p95 for 300 m buffers, and both strategies returned identical results
for all 600 route and buffer combinations.

## Why H3 lost here

- **Over-fetch.** To guarantee a superset, every route sample is expanded by enough rings to cover the
  buffer plus the sampling gap plus cell-size variation. At 100 m that already fetches about 15 times
  as many places as it returns, and at 1,000 m it fetches the whole dataset.
- **Two steps instead of one.** The cover is computed in Java and sent as an array of up to a few thousand
  cell ids; PostGIS answers the same question with one index probe that already knows the geometry.
- **Small data.** With 1,334 places, any index is fast; the fixed cost of building the cover dominates.

## Where H3 would win instead

- **The candidate store has no geometry index.** A key-value store, a search engine or a cache keyed by
  cell id can answer `cell IN (…)` but not `ST_DWithin`. That is the main reason to keep the strategy.
- **Precomputed per-cell aggregates.** Counts, prices or scores rolled up per cell can be read for a
  corridor without touching individual places at all.
- **Joining datasets that share no geometry index.** Two sources tagged with the same cell ids can be
  joined on an integer instead of a spatial predicate, across systems.
- **Very large, unevenly dense data** where a tighter cover (for example `polygonToCells` on the buffered
  route, instead of ring expansion) would cut the over-fetch. That variant was not built; it is the first
  thing to try if H3 is needed.

## Consequences

- The H3 strategy stays in the code, reachable with the undocumented `?strategy=h3`, and its equality
  test keeps it honest.
- The benchmark is re-runnable (`bench.yml`). Re-run it when the data grows by an order of magnitude or
  when the candidate store changes.
