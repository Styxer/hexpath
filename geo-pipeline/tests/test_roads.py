from geopipeline.roads import build_graph, largest_scc_share


def way(coords, tags, way_id=1, node_ids=None):
    node_ids = node_ids or list(range(1, len(coords) + 1))
    return {"type": "way", "id": way_id, "nodes": node_ids,
            "geometry": [{"lon": x, "lat": y} for x, y in coords], "tags": tags}


def test_oneway_minus_one_reverses():
    nodes, edges = build_graph([way(coords=[(0, 0), (0, 1)], tags={"highway": "residential", "oneway": "-1"})])
    assert edges[0].oneway and edges[0].coords[0] == (0, 1)


def test_way_split_at_shared_node():
    a = way([(0, 0), (0, 1), (0, 2)], {"highway": "residential"}, 1, [1, 2, 3])
    b = way([(1, 1), (0, 1)], {"highway": "residential"}, 2, [4, 2])
    nodes, edges = build_graph([a, b])
    assert len(edges) == 3 and {n.id for n in nodes} == {1, 2, 3, 4}


def test_speed_from_maxspeed_or_class_default():
    _, e1 = build_graph([way([(0, 0), (0, 1)], {"highway": "primary", "maxspeed": "60"})])
    _, e2 = build_graph([way([(0, 0), (0, 1)], {"highway": "primary", "maxspeed": "signals"})])
    assert e1[0].speed_kmh == 60 and e2[0].speed_kmh == 50


def test_oneway_chain_is_not_strongly_connected():
    nodes, edges = build_graph([way([(0, 0), (0, 1), (0, 2)], {"highway": "residential", "oneway": "yes"})])
    assert len(nodes) == 2  # the middle node is not an intersection
    assert largest_scc_share(nodes, edges) == 0.5
