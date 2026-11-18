# ADR 0002: Releases as partitions, published with a transactional outbox

- Status: accepted
- Date: 2026-11-16

## Context

Data changes must be all-or-nothing for readers, reversible in seconds, and visible to the service
without a restart. Old data must not slow down queries over new data.

## Decision

1. **Every row carries `release_id`, and every geo table is LIST-partitioned by it.** A release is
   loaded into its own partitions while readers keep using the active one.
2. **Publishing is a status flip** in `dataset_release`, guarded by a partial unique index that allows
   one `ACTIVE` row. Rollback is the same flip in the other direction; the bad release becomes
   `ROLLED_BACK` so it is never picked as a later rollback target.
3. **The same transaction writes an outbox row** (`dataset_event`, type `RELEASE_ACTIVATED`) and sends
   `NOTIFY dataset_events`. The service listens, and polls every 10 seconds in case a notification was
   missed.
4. **Retention:** the active release plus the two newest retired ones keep their partitions.
   `geopipeline gc` drops older ones, but never a release retired in the last 10 minutes, so a service
   still pinned to it has time to swap first.

## Alternatives

- **One table, `DELETE` the old release:** slow, bloats tables and indexes, and rollback means re-loading.
- **Swap table names (`ALTER TABLE ... RENAME`):** atomic, but takes an exclusive lock that queues every
  reader, and keeps only one previous version.
- **Publish over Kafka directly from the pipeline:** the database change and the message could
  disagree if one of them fails. The outbox keeps them in one transaction; a relay forwards later
  (ADR 0005).

## Consequences

- Readers must always filter on one `release_id`. The service enforces this by pinning a snapshot per
  request.
- Partition DDL needs the table owner, so the pipeline calls two `SECURITY DEFINER` functions instead of
  owning tables.
- The `NOTIFY` is delivered only after commit, which is exactly when the new release is visible.
