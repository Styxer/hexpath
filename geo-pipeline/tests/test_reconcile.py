from dataclasses import replace
from unittest.mock import ANY

from conftest import baseline_of
from geopipeline.reconcile import ReconcileResult, Thresholds, reconcile


def test_bootstrap_passes(mini_dataset):
    assert reconcile(mini_dataset, None, Thresholds()) == ReconcileResult(True, ANY, [])


def test_identical_release_has_no_changes(mini_dataset):
    r = reconcile(mini_dataset, baseline_of(mini_dataset), Thresholds())
    assert r.breaches == [] and not r.bootstrap
    assert r.changes["place"] == {"added": 0, "removed": 0, "moved": 0, "renamed": 0}


def test_wrong_zone_extent_breaches(mini_dataset, bad_zones_dataset):
    r = reconcile(bad_zones_dataset, baseline_of(mini_dataset), Thresholds())
    assert any(b.startswith("boundary_area_change_pct zone-a") for b in r.breaches)
    assert r.changes["boundary_area_change_pct"]["zone-a"] > 20


def test_mass_deletion_breaches(mini_dataset):
    half = replace(mini_dataset, places=mini_dataset.places[:4])
    assert any("place removed" in b for b in reconcile(half, baseline_of(mini_dataset), Thresholds()).breaches)


def test_small_move_is_not_moved(mini_dataset):
    p = mini_dataset.places[0]
    nudged = replace(p, lat=p.lat + 0.0002)   # about 22 m
    ds = replace(mini_dataset, places=(nudged,) + mini_dataset.places[1:])
    assert reconcile(ds, baseline_of(mini_dataset), Thresholds()).changes["place"]["moved"] == 0


def test_one_far_move_breaches_share(mini_dataset):   # 1 of 8 places = 12.5% > 5%
    p = mini_dataset.places[0]
    far = replace(p, lat=p.lat + 0.001)              # about 111 m
    ds = replace(mini_dataset, places=(far,) + mini_dataset.places[1:])
    r = reconcile(ds, baseline_of(mini_dataset), Thresholds())
    assert r.changes["place"]["moved"] == 1 and any(b.startswith("place moved") for b in r.breaches)


def test_rename_is_counted(mini_dataset):
    p = mini_dataset.places[0]
    ds = replace(mini_dataset, places=(replace(p, name="Cafe Renamed"),) + mini_dataset.places[1:])
    assert reconcile(ds, baseline_of(mini_dataset), Thresholds()).changes["place"]["renamed"] == 1
