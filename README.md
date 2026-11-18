# hexpath

[![ci](https://github.com/Styxer/hexpath/actions/workflows/ci.yml/badge.svg)](https://github.com/Styxer/hexpath/actions/workflows/ci.yml)

A working slice of a geo data platform, built on real OpenStreetMap data for central Tel Aviv-Yafo:

- a **release-gated pipeline** (Python) that validates and reconciles each new dataset against the live
  one, refuses bad data, and publishes or rolls back in one transaction;
- a **read API** (Java 21, Spring Boot 4.1, PostGIS) for place search in Hebrew and English, boundary
  lookup, and route-aware search, with every response tagged with the dataset release that served it;
- the **operational side** treated as part of the product: a contract test, a spatial-index guard,
  end-to-end tests including a rollback drill, a benchmark that settled a design question, a runbook,
  service levels and a worked postmortem.

## Quick start

Needs Docker, a JDK 21 or newer, and Python 3.10 or newer.

```powershell
# Windows
.\scripts\demo.ps1
```

```bash
# Linux / macOS
./scripts/demo.sh
```

This starts PostGIS, publishes the committed OpenStreetMap extract as release 1, starts the service and
opens the demo map at <http://localhost:8080/>. Run every test stage with `scripts/verify.ps1` (or `.sh`).

## Try the API

```bash
curl -s "localhost:8080/v1/places/search?q=dizengof&lat=32.0779&lon=34.7740"          # typo-tolerant
curl -s "localhost:8080/v1/places/search?q=%D7%A7%D7%A4%D7%94&lat=32.0779&lon=34.7740" # Hebrew: קפה
curl -s "localhost:8080/v1/boundaries/containing?lat=32.0779&lon=34.7740"
curl -s -X POST localhost:8080/v1/search/along-route -H 'Content-Type: application/json' \
  -d '{"route":{"type":"LineString","coordinates":[[34.7740,32.0700],[34.7740,32.0850]]},"buffer_m":300}'
curl -si localhost:8080/v1/dataset | grep -i x-dataset-release
```

The full contract is [`api/openapi.yaml`](api/openapi.yaml); Swagger UI runs at `/swagger-ui.html`.

## Try a bad release

```bash
python -m geopipeline run --source osm --zones data/fixtures/bad/zones-wrong-extent.geojson
#   release N REJECTED — report: data/reports/release-N.json
#     blocked: boundary_area_change_pct zone-a 800.0% > 20.0%        (exit code 3)
python -m geopipeline run --source osm --zones data/fixtures/bad/zones-wrong-extent.geojson \
  --override --reason "demo"
#   now live; the map's release badge changes within seconds
python -m geopipeline rollback
#   back to the previous release, in one transaction
```

That sequence is the [postmortem drill](docs/postmortem-example.md), and CI runs it on every push.

## How it fits together

| Part | What it is |
|---|---|
| [`db/`](db) | Roles (`geo_owner`, `geo_writer`, `geo_reader`), partitioned schema, migrations |
| [`geo-pipeline/`](geo-pipeline) | Extract, transform, validate, reconcile, gate, publish, rollback, gc, outbox relay |
| [`geo-service/`](geo-service) | REST API and demo page; pins one release snapshot per request |
| [`e2e/`](e2e) | Whole-stack tests on real data |
| [`bench/`](bench) | PostGIS vs H3 benchmark for route-aware search |
| [`docs/`](docs) | Architecture, decisions, runbook, SLOs, postmortem, licensing |

Start with [docs/architecture.md](docs/architecture.md). Decisions:
[0001 PostGIS](docs/adr/0001-postgis.md) ·
[0002 releases as partitions + outbox](docs/adr/0002-release-partitions-outbox.md) ·
[0003 route-aware search, settled by benchmark](docs/adr/0003-corridor-search.md) ·
[0005 where Kafka and a search engine plug in](docs/adr/0005-streaming-and-search.md).

## Tests

| Suite | What it proves |
|---|---|
| Pipeline unit tests (pytest) | Transform, every validation rule, reconcile thresholds, gate, CLI exit codes |
| Pipeline database tests (Testcontainers, PostGIS 17) | Roles and privileges, publish, rollback, gc grace, lock, outbox, idempotency |
| Service unit tests (JUnit, jqwik) | Shared Hebrew/English normalisation, cursors, route validation, the H3 cover never missing a place (1,000 random routes) |
| Service integration tests (Testcontainers) | Every endpoint and error, snapshot refresh by NOTIFY and by poll, schema check |
| Spatial index guard | Each query can use the index written for it, with a negative control |
| Contract test | The generated OpenAPI spec equals the committed `api/openapi.yaml` |
| Seed drift | The Java test data is exactly what the pipeline produces |
| End-to-end | Real data, contract validation of live responses, limits, the rollback drill |

## Status

| Phase | Scope | State |
|---|---|---|
| 1 | Pipeline, release lifecycle, places, boundaries, route-aware search, contract, CI, docs | Done |
| 2 | Routing (A*), address geocoding and reverse geocoding, load test | Planned |
| 3 | Vendor geocoder benchmark, AI-ready export, Kafka relay | Optional |

## How this was built

Built with AI assistance (Claude), in a plan-then-test-first workflow: tests were written before the code
and, almost always, seen failing first; the exceptions are recorded in the build log. An independent
review of the whole branch followed, and its findings were fixed with tests that reproduce them.
`AStarRoutingEngine` (phase 2) is written by Ofir.

## Data and licence

Map data © OpenStreetMap contributors, under the ODbL. The regulatory zones are fictional test data.
Code: MIT. Details in [docs/data-licensing.md](docs/data-licensing.md).
