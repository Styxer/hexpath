"""Outbox relay. The pipeline writes dataset_event rows; a relay forwards them elsewhere.

Only a logging relay exists. A Kafka relay would implement the same one-method protocol
(see docs/adr/0005-streaming-and-search.md).
"""
from __future__ import annotations

import json
import logging
from typing import Protocol

log = logging.getLogger("geopipeline.events")


class OutboxRelay(Protocol):
    def send(self, event: dict) -> None: ...


class LoggingRelay:
    def send(self, event: dict) -> None:
        log.info("event %s", json.dumps(event, default=str))


def relay_pending(conn, relay: OutboxRelay) -> int:
    with conn.transaction():
        rows = conn.execute(
            "select id, release_id, type, payload, created_at from dataset_event"
            " where relayed_at is null order by id for update skip locked").fetchall()
        for id_, release_id, type_, payload, created_at in rows:
            relay.send({"id": id_, "release_id": release_id, "type": type_, "payload": payload,
                        "created_at": created_at})
            conn.execute("update dataset_event set relayed_at = now() where id = %s", (id_,))
    return len(rows)
