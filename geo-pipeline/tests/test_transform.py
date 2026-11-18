from datetime import datetime, timezone

import h3

from conftest import COVERAGE
from geopipeline.osm.transform import transform
from geopipeline.roads import largest_scc_share


def test_transform_mini_counts(mini_osm, mini_zones):
    ds = transform(mini_osm, mini_zones, COVERAGE)
    assert len(ds.places) == 8 and ds.dropped == {"unnamed": 1}
    assert {b.id for b in ds.boundaries} == {"osm-r100", "zone-a"}
    assert ds.source_timestamp == datetime(2026, 11, 1, tzinfo=timezone.utc)


def test_place_h3_is_r9(mini_osm, mini_zones):
    p = next(p for p in transform(mini_osm, mini_zones, COVERAGE).places if p.id == "n1")
    assert h3.get_resolution(h3.int_to_str(p.h3_r9)) == 9


def test_hebrew_name_kept(mini_osm, mini_zones):
    p = next(p for p in transform(mini_osm, mini_zones, COVERAGE).places if p.id == "n6")
    assert p.name_he == "דיזנגוף קפה"


def test_grid_is_strongly_connected(mini_osm, mini_zones):
    ds = transform(mini_osm, mini_zones, COVERAGE)
    assert largest_scc_share(ds.nodes, ds.edges) == 1.0


def test_categories_mapped(mini_osm, mini_zones):
    cats = {p.id: p.category for p in transform(mini_osm, mini_zones, COVERAGE).places}
    assert cats == {"n1": "cafe", "n2": "restaurant", "n3": "bar", "n4": "hotel", "n5": "cafe",
                    "n6": "cafe", "n7": "museum", "n8": "pharmacy"}


def test_addresses_parsed(mini_osm, mini_zones):
    addrs = {a.housenumber: a for a in transform(mini_osm, mini_zones, COVERAGE).addresses}
    assert set(addrs) == {"50", "52"} and addrs["50"].housenumber_int == 50
    assert addrs["50"].street == "Dizengoff"


def test_zone_is_synthetic_regulatory(mini_osm, mini_zones):
    zone = next(b for b in transform(mini_osm, mini_zones, COVERAGE).boundaries if b.id == "zone-a")
    assert (zone.type, zone.origin) == ("REGULATORY", "synthetic")


def test_place_outside_coverage_is_dropped_and_counted(mini_osm, mini_zones):
    mini_osm["elements"].append({"type": "node", "id": 77, "lat": 31.0, "lon": 34.0,
                                 "tags": {"amenity": "cafe", "name": "Far Away"}})
    ds = transform(mini_osm, mini_zones, COVERAGE)
    assert "n77" not in {p.id for p in ds.places} and ds.dropped["outside_coverage"] == 1
