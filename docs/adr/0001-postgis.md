# ADR 0001: PostGIS as the spatial store, not an in-memory index

- Status: accepted
- Date: 2026-11-16

## Context

The service answers point-in-polygon, radius, nearest-neighbour, fuzzy-text and route-buffer queries
over places, addresses, boundaries and roads. Two shapes were considered:

1. **An in-memory index in the service** (JTS STRtree plus H3 maps), loaded from files.
2. **PostGIS**, with the service as a read-only client.

## Decision

PostGIS.

## Reasons

- **One copy of the truth, shared by two languages.** The Python pipeline writes and the Java service
  reads the same tables. With an in-memory index, every service instance would rebuild its own copy,
  and the pipeline would need a second serialisation format.
- **Releases and rollback for free.** Partitions per release plus a status flip give atomic publishing
  and one-statement rollback (ADR 0002). An in-memory design needs its own versioned file store.
- **Mature operators.** `ST_DWithin` on geography, `ST_Contains`, KNN `<->` and `pg_trgm` cover every
  query with indexes, and `EXPLAIN` makes their cost visible and testable (`SpatialIndexGuardIT`).
- **It is what the job looks like.** A geo platform's read path usually sits on a spatial database or
  a search engine, not on a hand-rolled index.

## Costs

- Every query is a network round trip. At this data size the server-side latency is a few
  milliseconds (ADR 0003), well inside the SLO.
- The road graph for routing (phase 2) will still be held in memory, loaded per release, because graph
  search inside SQL (pgRouting) is a separate trade-off, recorded in ADR 0004 when routing is built.
