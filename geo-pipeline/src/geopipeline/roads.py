"""Road graph: OSM ways split into edges between intersections."""
from __future__ import annotations

from collections import Counter, defaultdict

from .geo import haversine_m
from .model import RoadEdge, RoadNode

DEFAULT_SPEED_KMH = {
    "trunk": 70, "primary": 50, "secondary": 50, "tertiary": 40,
    "unclassified": 30, "residential": 30, "living_street": 10, "service": 15,
}
ONEWAY_YES = {"yes", "1", "true"}


def _speed(tags: dict) -> float:
    raw = (tags.get("maxspeed") or "").strip()
    if raw.isdigit():
        return float(raw)
    return float(DEFAULT_SPEED_KMH.get(tags.get("highway", ""), 30))


def build_graph(ways: list[dict]) -> tuple[list[RoadNode], list[RoadEdge]]:
    """Split ways at every node shared with another way (or at their ends).

    Node ids are OSM node ids. Edge ids are sequential over ways sorted by id,
    so the same input always yields the same graph.
    """
    ways = sorted(ways, key=lambda w: w["id"])
    usage = Counter(n for w in ways for n in w["nodes"])
    nodes: dict[int, RoadNode] = {}
    edges: list[RoadEdge] = []

    for w in ways:
        tags = w.get("tags", {})
        oneway_tag = tags.get("oneway", "no")
        reverse = oneway_tag == "-1"
        oneway = reverse or oneway_tag in ONEWAY_YES
        speed = _speed(tags)
        ids, geom = w["nodes"], [(g["lon"], g["lat"]) for g in w["geometry"]]

        start = 0
        for k in range(1, len(ids)):
            if k != len(ids) - 1 and usage[ids[k]] < 2:
                continue  # interior node used by this way only: not an intersection
            seg_ids, seg = ids[start:k + 1], geom[start:k + 1]
            if reverse:
                seg_ids, seg = seg_ids[::-1], seg[::-1]
            for nid, (lon, lat) in ((seg_ids[0], seg[0]), (seg_ids[-1], seg[-1])):
                nodes.setdefault(nid, RoadNode(nid, lon, lat))
            length = sum(haversine_m(*seg[i], *seg[i + 1]) for i in range(len(seg) - 1))
            edges.append(RoadEdge(len(edges) + 1, seg_ids[0], seg_ids[-1], oneway, length,
                                  speed, tags.get("highway", ""), tuple(seg)))
            start = k

    return sorted(nodes.values(), key=lambda n: n.id), edges


def largest_scc_share(nodes, edges) -> float:
    """Share of nodes in the largest strongly connected component (Kosaraju, iterative)."""
    if not nodes:
        return 1.0
    fwd, back = defaultdict(list), defaultdict(list)
    for e in edges:
        fwd[e.from_node].append(e.to_node)
        back[e.to_node].append(e.from_node)
        if not e.oneway:
            fwd[e.to_node].append(e.from_node)
            back[e.from_node].append(e.to_node)

    ids = [n.id for n in nodes]
    order, seen = [], set()
    for root in ids:  # pass 1: finish order on the forward graph
        if root in seen:
            continue
        seen.add(root)
        stack = [(root, iter(fwd[root]))]
        while stack:
            node, it = stack[-1]
            nxt = next((m for m in it if m not in seen), None)
            if nxt is None:
                stack.pop()
                order.append(node)
            else:
                seen.add(nxt)
                stack.append((nxt, iter(fwd[nxt])))

    best, assigned = 0, set()
    for root in reversed(order):  # pass 2: components on the reversed graph
        if root in assigned:
            continue
        size, stack = 0, [root]
        assigned.add(root)
        while stack:
            node = stack.pop()
            size += 1
            for m in back[node]:
                if m not in assigned:
                    assigned.add(m)
                    stack.append(m)
        best = max(best, size)
    return best / len(ids)
