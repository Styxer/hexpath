import json
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parents[2]
COVERAGE = (34.765, 32.060, 34.795, 32.090)  # west, south, east, north
MINI_OSM = REPO / "data" / "fixtures" / "mini" / "osm.json"
MINI_ZONES = REPO / "data" / "fixtures" / "mini" / "zones.geojson"
BAD_ZONES = REPO / "data" / "fixtures" / "bad" / "zones-wrong-extent.geojson"


def load_golden():
    rows = []
    for line in (REPO / "shared" / "normalization-golden.tsv").read_text(encoding="utf-8").splitlines():
        raw, _, expected = line.partition("\t")
        rows.append((raw, expected))
    return rows


@pytest.fixture
def mini_osm():
    return json.loads(MINI_OSM.read_text(encoding="utf-8"))


@pytest.fixture
def mini_zones():
    return json.loads(MINI_ZONES.read_text(encoding="utf-8"))


@pytest.fixture
def mini_dataset(mini_osm, mini_zones):
    from geopipeline.osm.transform import transform

    return transform(mini_osm, mini_zones, COVERAGE)


@pytest.fixture
def bad_zones_dataset(mini_osm):
    from geopipeline.osm.transform import transform

    return transform(mini_osm, json.loads(BAD_ZONES.read_text()), COVERAGE)
