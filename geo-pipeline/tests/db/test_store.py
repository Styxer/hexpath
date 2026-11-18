from datetime import timedelta

import psycopg
import pytest

from geopipeline.db.store import NothingToRollBack
from geopipeline.model import input_hash

from .conftest import Listener, connect, partition_exists, status, variant

pytestmark = pytest.mark.db


def last_event(pg):
    with connect(pg.owner) as c:
        return c.execute("select type, payload from dataset_event order by id desc limit 1").fetchone()


def test_stage_loads_rows_and_stats(clean, store, mini_dataset):
    rid = store.stage(mini_dataset, {"note": "x"}, None)
    assert status(clean, rid) == "STAGED"
    with connect(clean.reader) as c:
        assert c.execute("select count(*) from place where release_id = %s", (rid,)).fetchone()[0] == 8
        name_norm, = c.execute("select name_he_norm from place where release_id = %s and id = 'n6'", (rid,)).fetchone()
        stats, = c.execute("select stats from dataset_release where id = %s", (rid,)).fetchone()
    assert name_norm == "דיזנגוף קפה"
    assert stats["coverage"] == [34.765, 32.06, 34.795, 32.09] and stats["counts"]["place"] == 8


def test_only_one_active(clean, store, mini_dataset):
    a = store.stage(mini_dataset, {}, None)
    store.publish(a)
    b = store.stage(variant(mini_dataset, 1), {}, None)
    with pytest.raises(psycopg.errors.UniqueViolation):
        store.conn.execute("update dataset_release set status = 'ACTIVE' where id = %s", (b,))


def test_publish_flips_and_emits_event(clean, store, mini_dataset):
    a = store.stage(mini_dataset, {}, None)
    store.publish(a)
    b = store.stage(variant(mini_dataset, 1), {}, None)
    with Listener(clean.reader, "dataset_events") as notes:
        store.publish(b)
        assert notes.next(timeout=5) == str(b)
    assert status(clean, a) == "RETIRED" and status(clean, b) == "ACTIVE"
    assert last_event(clean) == ("RELEASE_ACTIVATED", {"release_id": b, "previous": a})


def test_rollback_marks_rolled_back(clean, store, mini_dataset):
    a = store.stage(mini_dataset, {}, None); store.publish(a)
    b = store.stage(variant(mini_dataset, 1), {}, None); store.publish(b)
    assert store.rollback() == a
    assert status(clean, b) == "ROLLED_BACK" and status(clean, a) == "ACTIVE"


def test_rollback_twice_does_not_return_to_bad_release(clean, store, mini_dataset):
    ids = []
    for n in range(3):
        ids.append(store.stage(variant(mini_dataset, n), {}, None)); store.publish(ids[-1])
    a, b, c = ids
    assert store.rollback() == b
    assert store.rollback() == a
    assert status(clean, c) == "ROLLED_BACK"


def test_rollback_without_target(clean, store, mini_dataset):
    store.publish(store.stage(mini_dataset, {}, None))
    with pytest.raises(NothingToRollBack):
        store.rollback()


def test_gc_keeps_two_retired_and_respects_grace(clean, store, mini_dataset):
    r = []
    for n in range(5):
        r.append(store.stage(variant(mini_dataset, n), {}, None)); store.publish(r[-1])
    with connect(clean.owner) as c:   # r5 ACTIVE, r1..r4 RETIRED; newest 2 retired (r4, r3) are kept
        c.execute("update dataset_release set retired_at = now() - interval '11 minutes' where id = %s", (r[0],))
        c.execute("update dataset_release set retired_at = now() - interval '1 minute' where id = %s", (r[1],))
    assert store.gc() == [r[0]]       # r2 is inside the 10-minute grace window
    assert not partition_exists(clean, r[0]) and partition_exists(clean, r[1])
    assert store.gc() == []           # nothing left to drop; already-dropped releases are skipped


def test_gc_drops_rolled_back_after_grace(clean, store, mini_dataset):
    a = store.stage(mini_dataset, {}, None); store.publish(a)
    b = store.stage(variant(mini_dataset, 1), {}, None); store.publish(b)
    store.rollback()
    assert store.gc() == []                          # inside grace
    assert store.gc(grace=timedelta(0)) == [b]


def test_baseline_roundtrip(clean, store, mini_dataset):
    store.publish(store.stage(mini_dataset, {}, None))
    base = store.load_baseline()
    assert base.counts["place"] == 8
    assert base.places["n1"][2] == "cafe dizengoff"
    assert base.boundary_areas["zone-a"] == pytest.approx(0.004 * 0.006)


def test_no_baseline_before_first_publish(clean, store):
    assert store.load_baseline() is None


def test_rejected_then_same_input_can_stage(clean, store, mini_dataset):
    store.record_rejected(mini_dataset, {"gate": "no"})
    assert store.find_by_hash(input_hash(mini_dataset)) is None
    rid = store.stage(mini_dataset, {}, None)
    assert store.find_by_hash(input_hash(mini_dataset)) == rid
