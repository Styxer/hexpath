# ADR 0005: Where Kafka and a search engine plug in

- Status: accepted (integration points built; Kafka and the search engine are not)
- Date: 2026-11-18

## Context

A production geo platform usually tells other systems when data changes, and often serves text search
from a search engine. Neither is needed to show the read path here, and running them would make the
demo heavier than the rest of the project.

## Decision

Build the seams, not the systems:

- **Events.** Every release change writes a `dataset_event` row in the same transaction as the change
  (transactional outbox). `python -m geopipeline relay` drains unsent rows through an `OutboxRelay`, a
  one-method protocol (`send(event)`). Only `LoggingRelay` exists. A `KafkaRelay` would publish each
  event to a topic such as `geo.dataset.release`, keyed by release id, and mark the row relayed only
  after the broker acknowledges it (at-least-once; consumers deduplicate by event id).
- **Search engine.** A consumer of `RELEASE_ACTIVATED` would build a new index named after the
  release (for example `places-r42`) from that release's partition, then move an alias to it: the same
  pattern as the database, one level up. The H3 cell id stored on every place (`h3_r9`) is what such an
  index would use for corridor queries, since it has no PostGIS (ADR 0003).

## What is not claimed

There is no Kafka cluster, no consumer and no search index in this repository. The outbox table, the
relay protocol, the logging relay and the cell ids are real and tested.
