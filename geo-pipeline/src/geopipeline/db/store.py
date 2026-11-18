"""Release lifecycle in PostGIS: stage, publish, rollback, gc. Connects as geo_writer.

Every status change happens in one transaction together with its outbox event and NOTIFY,
so readers see either the old release or the new one, never a mix.
"""
from __future__ import annotations

import json
from dataclasses import asdict
from datetime import timedelta

from psycopg.types.json import Jsonb
from shapely import LineString, Point, set_srid, to_wkb

from ..model import Dataset, input_hash
from ..normalize import normalize
from ..reconcile import Baseline

CHANNEL = "dataset_events"


class NothingToRollBack(RuntimeError):  # no ACTIVE release, or no RETIRED release with data left
    pass


def _ewkb(geom) -> str:
    return to_wkb(set_srid(geom, 4326), hex=True, include_srid=True)


def _counts(ds: Dataset) -> dict:
    return {"place": len(ds.places), "address": len(ds.addresses), "boundary": len(ds.boundaries),
            "road_node": len(ds.nodes), "road_edge": len(ds.edges)}


TABLE_COLUMNS = {
    "place": ("release_id", "id", "osm_id", "name", "name_he", "name_en", "name_norm", "name_he_norm",
              "name_en_norm", "category", "geom", "h3_r9"),
    "address": ("release_id", "id", "street", "street_he", "street_en", "street_norm", "housenumber",
                "housenumber_int", "geom"),
    "boundary": ("release_id", "id", "name", "type", "origin", "geom"),
    "road_node": ("release_id", "id", "geom"),
    "road_edge": ("release_id", "id", "from_node", "to_node", "oneway", "length_m", "speed_kmh", "highway", "geom"),
}


def table_rows(rid: int, ds: Dataset):
    """(table, columns, rows) for every geo table. Used by COPY here and by the test seed export."""
    yield "place", TABLE_COLUMNS["place"], [
        (rid, p.id, p.osm_id, p.name, p.name_he, p.name_en, normalize(p.name), normalize(p.name_he) or None,
         normalize(p.name_en) or None, p.category, _ewkb(Point(p.lon, p.lat)), p.h3_r9) for p in ds.places]
    yield "address", TABLE_COLUMNS["address"], [
        (rid, a.id, a.street, a.street_he, a.street_en, normalize(a.street) or None, a.housenumber,
         a.housenumber_int, _ewkb(Point(a.lon, a.lat))) for a in ds.addresses]
    yield "boundary", TABLE_COLUMNS["boundary"], [
        (rid, b.id, b.name, b.type, b.origin, _ewkb(b.geom)) for b in ds.boundaries]
    yield "road_node", TABLE_COLUMNS["road_node"], [(rid, n.id, _ewkb(Point(n.lon, n.lat))) for n in ds.nodes]
    yield "road_edge", TABLE_COLUMNS["road_edge"], [
        (rid, e.id, e.from_node, e.to_node, e.oneway, e.length_m, e.speed_kmh, e.highway,
         _ewkb(LineString(e.coords))) for e in ds.edges]


def release_stats(ds: Dataset) -> dict:
    return {"counts": _counts(ds), "coverage": list(ds.coverage), "dropped": dict(ds.dropped)}


class ReleaseStore:
    def __init__(self, conn):
        self.conn = conn  # autocommit connection; transactions are explicit

    # ---- lookups -------------------------------------------------------------
    def find_by_hash(self, h: str) -> int | None:
        row = self.conn.execute(
            "select id from dataset_release where input_hash = %s"
            " and status not in ('REJECTED', 'ROLLED_BACK', 'PURGED')", (h,)).fetchone()
        return row[0] if row else None

    def active_id(self) -> int | None:
        row = self.conn.execute("select id from dataset_release where status = 'ACTIVE'").fetchone()
        return row[0] if row else None

    def load_baseline(self) -> Baseline | None:
        rid = self.active_id()
        if rid is None:
            return None
        stats = self.conn.execute("select stats from dataset_release where id = %s", (rid,)).fetchone()[0]
        places = {i: (x, y, n or "") for i, x, y, n in self.conn.execute(
            "select id, ST_X(geom), ST_Y(geom), name_norm from place where release_id = %s", (rid,))}
        areas = dict(self.conn.execute(
            "select id, ST_Area(geom) from boundary where release_id = %s", (rid,)).fetchall())
        return Baseline(rid, dict(stats["counts"]), places, areas)

    # ---- writes ----------------------------------------------------------------
    def _insert_release(self, ds: Dataset, status: str, report: dict, override) -> int:
        stats = release_stats(ds)
        return self.conn.execute(
            """insert into dataset_release (input_hash, source, source_timestamp, status, stats, report, override)
               values (%s, %s, %s, %s, %s, %s, %s) returning id""",
            (input_hash(ds), ds.source, ds.source_timestamp, status, Jsonb(stats), Jsonb(report),
             Jsonb(json.loads(json.dumps(asdict(override), default=str))) if override else None),
        ).fetchone()[0]

    def record_rejected(self, ds: Dataset, report: dict) -> int:
        return self._insert_release(ds, "REJECTED", report, None)

    def stage(self, ds: Dataset, report: dict, override) -> int:
        with self.conn.transaction():
            rid = self._insert_release(ds, "STAGED", report, override)
            self.conn.execute("select hexpath_create_partitions(%s)", (rid,))
            self._copy(rid, ds)
        return rid

    def _copy(self, rid: int, ds: Dataset) -> None:
        cur = self.conn.cursor()
        for table, columns, rows in table_rows(rid, ds):
            with cur.copy(f"copy {table} ({', '.join(columns)}) from stdin") as cp:
                for row in rows:
                    cp.write_row(row)

    def _activate(self, target: int, previous: int | None, previous_status: str, reason: str) -> None:
        if previous is not None:  # retire first: the one-ACTIVE index is checked per statement
            self.conn.execute("update dataset_release set status = %s, retired_at = now() where id = %s",
                              (previous_status, previous))
        self.conn.execute("update dataset_release set status = 'ACTIVE', published_at = now(), retired_at = null"
                          " where id = %s", (target,))
        payload = {"release_id": target, "previous": previous}
        if reason:
            payload["reason"] = reason
        self.conn.execute("insert into dataset_event (release_id, type, payload) values (%s, 'RELEASE_ACTIVATED', %s)",
                          (target, Jsonb(payload)))
        self.conn.execute("select pg_notify(%s, %s)", (CHANNEL, str(target)))

    def publish(self, release_id: int) -> None:
        with self.conn.transaction():
            row = self.conn.execute("select status from dataset_release where id = %s for update",
                                    (release_id,)).fetchone()
            if row is None or row[0] != "STAGED":
                raise ValueError(f"release {release_id} is {row[0] if row else 'missing'}, only STAGED can be published")
            self._activate(release_id, self.active_id(), "RETIRED", "")

    def rollback(self) -> int:
        with self.conn.transaction():
            current = self.active_id()
            # Only a RETIRED release whose data still exists can be returned to.
            target = self.conn.execute(
                "select id from dataset_release where status = 'RETIRED'"
                " and to_regclass('place_r' || id) is not null"
                " order by retired_at desc, id desc limit 1").fetchone()
            if current is None or target is None:
                raise NothingToRollBack("no ACTIVE release, or no RETIRED release to return to")
            self._activate(target[0], current, "ROLLED_BACK", "rollback")
            return target[0]

    def gc(self, keep_retired: int = 2, grace: timedelta = timedelta(minutes=10)) -> list[int]:
        retired = [r for (r,) in self.conn.execute(
            "select id from dataset_release where status = 'RETIRED' order by retired_at desc, id desc")]
        candidates = set(retired[keep_retired:]) | {r for (r,) in self.conn.execute(
            "select id from dataset_release where status = 'ROLLED_BACK'")}
        dropped = []
        for rid in sorted(candidates):
            old_enough, exists = self.conn.execute(
                "select retired_at < now() - %s, to_regclass(%s) is not null from dataset_release where id = %s",
                (grace, f"place_r{rid}", rid)).fetchone()
            if old_enough and exists:
                with self.conn.transaction():
                    self.conn.execute("select hexpath_drop_partitions(%s)", (rid,))
                    self.conn.execute("update dataset_release set status = 'PURGED' where id = %s", (rid,))
                dropped.append(rid)
        return dropped
