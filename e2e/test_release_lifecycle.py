"""The postmortem drill, executed: a wrong regulatory boundary is rejected, forced through, and rolled back."""
from conftest import wait_for_release_change

BAD_ZONES = "data/fixtures/bad/zones-wrong-extent.geojson"
ONLY_IN_WRONG_EXTENT = {"lat": 32.0700, "lon": 34.7700}


def zone_a_contains(api, point):
    r = api.get("/v1/boundaries/containing", params={**point, "type": "REGULATORY"})
    return "zone-a" in [b["id"] for b in r.json()["results"]]


def test_reject_override_rollback(api, cli):
    before = api.release()
    assert not zone_a_contains(api, ONLY_IN_WRONG_EXTENT)

    assert cli("run", "--source", "osm", "--zones", BAD_ZONES).returncode == 3
    assert api.release() == before

    assert cli("run", "--source", "osm", "--zones", BAD_ZONES, "--override", "--reason", "e2e drill").returncode == 0
    bad = wait_for_release_change(api, before)
    assert zone_a_contains(api, ONLY_IN_WRONG_EXTENT)

    assert cli("rollback").returncode == 0
    assert wait_for_release_change(api, bad) == before
    assert not zone_a_contains(api, ONLY_IN_WRONG_EXTENT)
