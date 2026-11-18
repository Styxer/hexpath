# Service levels

## What is measured, and where

| SLI | Definition | Measured by |
|---|---|---|
| Latency | p95 of server-side duration per endpoint | Micrometer histogram `http_server_requests_seconds` (5-minute window); also `GET /v1/status` |
| Availability | Share of probe requests answered successfully | **Externally**: synthetic probes or load balancer logs. A service cannot report its own downtime, so `/v1/status` does not claim this number. Not measured in this project. |
| Freshness | Now minus the OpenStreetMap data timestamp of the active release | `hexpath_dataset_age_seconds`; `data_age_seconds` in `GET /v1/dataset` |

## Targets

| Objective | Target |
|---|---|
| `GET /v1/places/search` latency | p95 < 150 ms |
| `GET /v1/boundaries/containing` latency | p95 < 100 ms |
| `POST /v1/search/along-route` latency (buffer up to 1 km) | p95 < 250 ms |
| Availability | 99.9% per 30 days (measured externally) |
| Data freshness | < 7 days |

## Evidence so far

- **Along-route search:** server-side p95 of 5.1 ms (100 m buffer), 8.0 ms (300 m) and 17.6 ms (1 km)
  with the PostGIS strategy, on the real Tel Aviv data, on a 4-vCPU CI runner
  ([ADR 0003](adr/0003-corridor-search.md)). Well inside the target, but measured one request at a
  time, not under load.
- **Other endpoints:** no load test yet. A Locust load test at a stated request rate is planned for
  phase 2, and its results will replace this line.
- **Freshness: currently breached.** The committed extract carries an OpenStreetMap timestamp of
  2026-07-15. The main Overpass instance was failing when it was fetched, and the mirror that answered
  was months behind. The metric shows this rather than hiding it; the fix is re-running the
  `fetch-osm` workflow when the main instance is healthy, and alerting on
  `hexpath_dataset_age_seconds > 7 days`.

## When a target is missed

Follow the [runbook](runbook.md). A missed latency target is not, on its own, a reason to change the
target: first find out what changed.
