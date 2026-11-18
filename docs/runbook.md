# Runbook

Commands assume the repository root, the variables from `.env` loaded, and the pipeline installed
(`scripts/demo.*` does all three). The service runs on port 8080; PostGIS on 55432.

## First, find out what is being served

Every API response carries two headers:

- `X-Dataset-Release`: the release that answered.
- `X-Request-Id`: quote it in any report. Every request writes one log line
  (`GET /v1/places/search -> 200 in 4.2 ms`) carrying `requestId` and `datasetRelease`.

```
curl -si localhost:8080/v1/dataset          # release, source data age, counts
python -m geopipeline report <release-id>   # what the pipeline found when it built that release
```

The service's own view of latency and errors per endpoint: `GET /v1/status`.
Metrics for Prometheus: `GET /actuator/prometheus` (`http_server_requests_seconds`,
`hexpath_dataset_age_seconds`, `hexpath_snapshot_swaps_total`, `hexpath_snapshot_load_seconds`).

## 1. Wrong data was published

Symptoms: a consuming team reports a wrong boundary, a missing place or a moved point.

1. Ask for the `X-Dataset-Release` header of a bad response, or run `curl -s localhost:8080/v1/dataset`.
2. Read that release's report: `python -m geopipeline report <id>`. Look for an override
   (`Override by …`) and for large reconcile changes.
3. Roll back:
   ```
   python -m geopipeline rollback
   ```
   This makes the newest `RETIRED` release that still has its data `ACTIVE`, and marks the bad one
   `ROLLED_BACK`, in one transaction with an outbox event. Re-running the rolled-back input later sends
   it through the gate again; it is not skipped as "already done".
4. Verify: `X-Dataset-Release` changes within about a second (NOTIFY), at most 10 seconds (poll).
   `hexpath_snapshot_swaps_total` goes up by one.
5. Fix the input, re-run the pipeline, and write the postmortem
   ([example](postmortem-example.md)).

Rollback fails with `error: no ACTIVE release, or no RETIRED release to return to` when there is
nothing to return to, including when the older releases were already `PURGED` by `gc`. Publish a
corrected release instead.

## 2. The pipeline rejected a release (exit code 3)

1. Open `data/reports/release-<id>.md`, or `python -m geopipeline report <id>`.
2. `ERROR` findings and `Breach:` lines are the reasons. The service keeps serving the previous release.
3. Decide:
   - **The data is wrong:** fix the input and run again.
   - **The change is real and expected** (for example a boundary that really moved): run again with
     ```
     python -m geopipeline run --source osm --override --reason "why this change is correct"
     ```
     The reason, user and time are stored in the release report.
   - **The threshold is wrong:** change it in `geopipeline/reconcile.py` through a reviewed commit, not
     with an override.

## 3. Latency degradation

1. `GET /v1/status`: which endpoint's `p95_ms` moved, and is `error_ratio_since_start` rising?
2. Check the database first: connections, CPU, and whether a pipeline run is loading at the same time.
3. Check the plan: the spatial index guard (`SpatialIndexGuardIT`) proves each query *can* use its index.
   Run the query with `EXPLAIN (ANALYZE, BUFFERS)` against the active partition to see whether it *does*.
4. If retired partitions have piled up, run `python -m geopipeline gc`. It drops the data of releases
   beyond the two newest retired ones (never within 10 minutes of retirement) and marks them `PURGED`.
5. Compare with the measured baseline in [ADR 0003](adr/0003-corridor-search.md) and the targets in
   [slo.md](slo.md).

## 4. The service does not pick up a new release

Symptoms: the pipeline printed `release N ACTIVE` but `X-Dataset-Release` still shows the old id after
10 seconds.

1. Compare with the database:
   ```
   psql -h localhost -p 55432 -U geo_reader -d hexpath -c "select id, status from dataset_release where status = 'ACTIVE'"
   ```
2. Look in the service log for `dataset listener connection failed, retrying in … ms`. The listener
   retries with backoff up to 30 seconds after a lost connection or a failed snapshot load; the
   10-second poll runs on that same connection.
3. `hexpath_snapshot_load_seconds` errors or a stuck `hexpath_snapshot_swaps_total` point at the loader.
4. If it does not recover, restart the service. Readiness stays DOWN until the snapshot is loaded.

## 5. Database down

- `/actuator/health` reports DOWN; API calls fail with 500 problems.
- Restart it: `docker compose up -d --wait`. The connection pool reconnects on its own, and the
  listener retries with backoff. No data is lost: releases live in the database volume.

## 6. Pipeline exit codes

| Exit | Meaning | Action |
|---|---|---|
| 0 | Published, staged (`--no-publish`), or input unchanged | None |
| 2 | Usage or input error: missing or unreadable extract, missing DSN, nothing to roll back, Overpass down | Read the one-line message |
| 3 | Release rejected by the gate | Section 2 |
| 4 | Another run holds the lock | Wait for it to finish; never run two at once |
| 5 | Database schema version mismatch | `python -m geopipeline migrate` |

The service refuses to start on a schema mismatch with
`schema version X, expected 4 — run: python -m geopipeline migrate`.

## 7. Refreshing the OpenStreetMap extract

Run the `fetch-osm` workflow in GitHub Actions. It tries three Overpass instances, two attempts each,
prints a validation summary, and pushes the extract to the branch `data/osm-refresh`. Review the counts
and findings, then merge it. Data changes go through review like code.
