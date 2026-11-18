from datetime import datetime, timezone

import pytest

from conftest import BAD_ZONES, MINI_ZONES
from geopipeline.gate import Override
from geopipeline.pipeline import run

from .conftest import connect, status

pytestmark = pytest.mark.db
NOW = datetime(2026, 11, 18, tzinfo=timezone.utc)


@pytest.fixture
def reports(tmp_path, monkeypatch):
    monkeypatch.setenv("HEXPATH_REPORTS_DIR", str(tmp_path))
    return tmp_path


def report(pg, rid):
    with connect(pg.owner) as c:
        return c.execute("select report from dataset_release where id = %s", (rid,)).fetchone()[0]


def test_first_run_bootstraps_and_publishes(clean, reports):
    r = run("fixture:mini", MINI_ZONES, clean.writer, None)
    assert r.status == "ACTIVE" and not r.noop
    assert report(clean, r.release_id)["reconcile"]["bootstrap"] is True
    assert r.report_path.exists() and r.report_path.with_suffix(".md").exists()


def test_same_input_is_noop(clean, reports):
    first = run("fixture:mini", MINI_ZONES, clean.writer, None)
    again = run("fixture:mini", MINI_ZONES, clean.writer, None)
    assert again.noop and again.release_id == first.release_id


def test_wrong_zone_rejected_then_override(clean, reports):
    run("fixture:mini", MINI_ZONES, clean.writer, None)
    rejected = run("fixture:mini", BAD_ZONES, clean.writer, None)
    assert rejected.status == "REJECTED" and status(clean, rejected.release_id) == "REJECTED"
    r = run("fixture:mini", BAD_ZONES, clean.writer, Override("ofir", "test", NOW))
    assert r.status == "ACTIVE" and report(clean, r.release_id)["override"]["reason"] == "test"
    assert report(clean, r.release_id)["gate"]["overridden"] is True


def test_no_publish_leaves_staged(clean, reports):
    r = run("fixture:mini", MINI_ZONES, clean.writer, None, publish=False)
    assert r.status == "STAGED"
