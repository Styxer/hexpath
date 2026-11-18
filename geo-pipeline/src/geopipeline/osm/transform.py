"""Overpass JSON + regulatory zones (GeoJSON) -> Dataset.

Nothing is dropped silently: every skipped feature is counted in Dataset.dropped.
"""
from __future__ import annotations

import re
from collections import Counter
from datetime import datetime

import h3
from shapely import MultiPolygon, Polygon
from shapely.geometry import shape
from shapely.geometry import LineString
from shapely.ops import polygonize, unary_union

from ..geo import Coverage, inside
from ..model import Address, Boundary, Dataset, Place
from ..roads import DEFAULT_SPEED_KMH, build_graph

CATEGORY_MAP = {
    ("amenity", "cafe"): "cafe",
    ("amenity", "restaurant"): "restaurant",
    ("amenity", "bar"): "bar",
    ("amenity", "pub"): "bar",
    ("amenity", "fast_food"): "fast_food",
    ("amenity", "pharmacy"): "pharmacy",
    ("tourism", "hotel"): "hotel",
    ("tourism", "hostel"): "hotel",
    ("tourism", "guest_house"): "hotel",
    ("tourism", "museum"): "museum",
    ("tourism", "attraction"): "attraction",
}
_HEBREW = re.compile("[א-ת]")
_LEADING_INT = re.compile(r"^\s*(\d+)")


def _category(tags: dict) -> str | None:
    for (key, value), cat in CATEGORY_MAP.items():
        if tags.get(key) == value:
            return cat
    if "shop" in tags:
        return "shop"
    return None


def _point(el: dict) -> tuple[float, float]:
    if el["type"] == "node":
        return el["lon"], el["lat"]
    coords = [(g["lon"], g["lat"]) for g in el.get("geometry", [])]
    geom = Polygon(coords) if len(coords) >= 4 and coords[0] == coords[-1] else LineString(coords)
    c = geom.centroid
    return c.x, c.y


def _relation_polygon(el: dict) -> MultiPolygon | None:
    """Assemble a multipolygon from member way geometries: outers minus inners."""
    def rings(role):
        lines = [LineString([(g["lon"], g["lat"]) for g in m["geometry"]])
                 for m in el.get("members", [])
                 if m.get("type") == "way" and m.get("role", "outer") == role and len(m.get("geometry", [])) >= 2]
        return unary_union(list(polygonize(lines))) if lines else None

    outer = rings("outer")
    if outer is None or outer.is_empty:
        return None
    inner = rings("inner")
    if inner is not None and not inner.is_empty:
        outer = outer.difference(inner)
    return outer if isinstance(outer, MultiPolygon) else MultiPolygon([outer])


def _as_multipolygon(geom) -> MultiPolygon:
    return geom if isinstance(geom, MultiPolygon) else MultiPolygon([geom])


def transform(osm: dict, zones: dict, coverage: Coverage) -> Dataset:
    dropped: Counter = Counter()
    places, addresses, boundaries, ways = [], [], [], []

    for el in osm.get("elements", []):
        tags = el.get("tags", {})
        kind = el["type"]
        if kind == "way" and tags.get("highway", "") in DEFAULT_SPEED_KMH:
            ways.append(el)
            continue
        if kind == "relation":
            if tags.get("boundary") == "administrative":
                geom = _relation_polygon(el)
                if geom is None:
                    dropped["boundary_unassembled"] += 1
                else:
                    boundaries.append(Boundary(f"osm-r{el['id']}", tags.get("name", f"r{el['id']}"),
                                               "ADMIN", "osm", geom))
            continue
        if kind not in ("node", "way"):
            continue

        osm_id = f"{kind[0]}{el['id']}"
        lon, lat = _point(el)

        if "addr:housenumber" in tags:
            street = tags.get("addr:street")
            is_he = bool(street and _HEBREW.search(street))
            number = tags["addr:housenumber"]
            m = _LEADING_INT.match(number)
            addresses.append(Address(
                id=f"addr-{osm_id}", street=street,
                street_he=tags.get("addr:street:he") or (street if is_he else None),
                street_en=tags.get("addr:street:en") or (None if is_he else street),
                housenumber=number, housenumber_int=int(m.group(1)) if m else None,
                lon=lon, lat=lat))

        category = _category(tags)
        if category is None:
            if any(k in tags for k in ("amenity", "tourism", "shop")):
                dropped["unmapped_category"] += 1
            continue
        name = tags.get("name") or tags.get("name:he") or tags.get("name:en")
        if not name:
            dropped["unnamed"] += 1
            continue
        if not inside(coverage, lon, lat):
            dropped["outside_coverage"] += 1
            continue
        places.append(Place(
            id=osm_id, osm_id=osm_id, name=name, name_he=tags.get("name:he"),
            name_en=tags.get("name:en"), category=category, lon=lon, lat=lat,
            h3_r9=h3.str_to_int(h3.latlng_to_cell(lat, lon, 9))))

    for f in zones.get("features", []):
        props = f["properties"]
        boundaries.append(Boundary(props["id"], props["name"], "REGULATORY", "synthetic",
                                   _as_multipolygon(shape(f["geometry"]))))

    nodes, edges = build_graph(ways)
    ts = osm.get("osm3s", {}).get("timestamp_osm_base")
    return Dataset(
        source="osm",
        source_timestamp=datetime.fromisoformat(ts.replace("Z", "+00:00")),
        coverage=coverage,
        places=tuple(sorted(places, key=lambda p: p.id)),
        addresses=tuple(sorted(addresses, key=lambda a: a.id)),
        boundaries=tuple(sorted(boundaries, key=lambda b: b.id)),
        nodes=tuple(nodes), edges=tuple(edges), dropped=dict(dropped),
    )
