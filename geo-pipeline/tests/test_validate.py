from dataclasses import replace

from shapely import MultiPolygon, Polygon

from geopipeline.model import Address, Boundary, RoadEdge, RoadNode
from geopipeline.validate import Finding, validate


def only(findings, rule):
    hits = [f for f in findings if f.rule == rule]
    assert len(hits) == 1, findings
    return hits[0]


def test_clean_mini_has_no_findings(mini_dataset):
    assert validate(mini_dataset) == []


def test_invalid_boundary_geometry(mini_dataset):
    bowtie = MultiPolygon([Polygon([(34.77, 32.07), (34.78, 32.08), (34.78, 32.07), (34.77, 32.08)])])
    ds = replace(mini_dataset, boundaries=mini_dataset.boundaries + (Boundary("bad", "Bad", "ADMIN", "osm", bowtie),))
    f = only(validate(ds), "boundary_invalid_geometry")
    assert (f.severity, f.count, f.examples) == ("ERROR", 1, ("bad",))


def test_place_outside_coverage(mini_dataset):
    far = replace(mini_dataset.places[0], id="far", lon=32.0750, lat=34.7741)  # swapped
    f = only(validate(replace(mini_dataset, places=mini_dataset.places + (far,))), "place_outside_coverage")
    assert (f.severity, f.count) == ("ERROR", 1)


def test_required_field_missing(mini_dataset):
    blank = replace(mini_dataset.places[0], id="blank", name="  ")
    f = only(validate(replace(mini_dataset, places=mini_dataset.places + (blank,))), "required_field_missing")
    assert (f.severity, f.count) == ("ERROR", 1)


def test_regulatory_overlap(mini_dataset):
    zone = next(b for b in mini_dataset.boundaries if b.id == "zone-a")
    twin = replace(zone, id="zone-a2")
    f = only(validate(replace(mini_dataset, boundaries=mini_dataset.boundaries + (twin,))), "boundary_overlap_same_type")
    assert (f.severity, f.count) == ("ERROR", 1)


def test_admin_overlap_is_allowed(mini_dataset):
    city = next(b for b in mini_dataset.boundaries if b.id == "osm-r100")
    nested = replace(city, id="osm-r101")
    assert validate(replace(mini_dataset, boundaries=mini_dataset.boundaries + (nested,))) == []


def _disconnect(ds, extra_islands):
    nodes, edges = list(ds.nodes), list(ds.edges)
    for k in range(extra_islands):
        a, b = 900000 + 2 * k, 900001 + 2 * k
        nodes += [RoadNode(a, 34.79, 32.08), RoadNode(b, 34.791, 32.08)]
        edges.append(RoadEdge(800000 + k, a, b, True, 90.0, 30.0, "residential", ((34.79, 32.08), (34.791, 32.08))))
    return replace(ds, nodes=tuple(nodes), edges=tuple(edges))


def test_graph_connectivity_error(mini_dataset):   # 9 connected + 6 isolated = 0.6
    f = only(validate(_disconnect(mini_dataset, 3)), "graph_connectivity_low")
    assert f.severity == "ERROR"


def test_graph_connectivity_warn(mini_dataset):    # 9 / 11 = 0.82
    findings = validate(_disconnect(mini_dataset, 1))
    assert only(findings, "graph_connectivity_warn").severity == "WARN"
    assert not [f for f in findings if f.rule == "graph_connectivity_low"]


def test_duplicate_place(mini_dataset):
    p = mini_dataset.places[0]
    dup = replace(p, id="dup", lon=p.lon + 0.00003)   # about 3 m east
    f = only(validate(replace(mini_dataset, places=mini_dataset.places + (dup,))), "duplicate_place")
    assert (f.severity, f.count) == ("WARN", 1)


def test_housenumber_unparsed(mini_dataset):
    odd = Address("addr-x", "Dizengoff", None, "Dizengoff", "א", None, 34.774, 32.077)
    f = only(validate(replace(mini_dataset, addresses=mini_dataset.addresses + (odd,))), "housenumber_unparsed")
    assert (f.severity, f.count) == ("WARN", 1)


def test_examples_capped_at_five(mini_dataset):
    blanks = tuple(replace(mini_dataset.places[0], id=f"b{i}", name="") for i in range(8))
    f = only(validate(replace(mini_dataset, places=mini_dataset.places + blanks)), "required_field_missing")
    assert f.count == 8 and len(f.examples) == 5
