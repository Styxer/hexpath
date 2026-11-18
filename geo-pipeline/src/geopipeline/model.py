"""Pipeline data model: one Dataset is one candidate release."""
from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass, field
from datetime import datetime

from shapely import MultiPolygon
from shapely import wkt as shapely_wkt

from .geo import Coverage


@dataclass(frozen=True)
class Place:
    id: str
    osm_id: str
    name: str
    name_he: str | None
    name_en: str | None
    category: str
    lon: float
    lat: float
    h3_r9: int


@dataclass(frozen=True)
class Address:
    id: str
    street: str | None
    street_he: str | None
    street_en: str | None
    housenumber: str
    housenumber_int: int | None
    lon: float
    lat: float


@dataclass(frozen=True)
class Boundary:
    id: str
    name: str
    type: str    # ADMIN | REGULATORY
    origin: str  # osm | synthetic
    geom: MultiPolygon


@dataclass(frozen=True)
class RoadNode:
    id: int
    lon: float
    lat: float


@dataclass(frozen=True)
class RoadEdge:
    id: int
    from_node: int
    to_node: int
    oneway: bool
    length_m: float
    speed_kmh: float
    highway: str
    coords: tuple[tuple[float, float], ...]


@dataclass(frozen=True)
class Dataset:
    source: str
    source_timestamp: datetime
    coverage: Coverage
    places: tuple[Place, ...]
    addresses: tuple[Address, ...]
    boundaries: tuple[Boundary, ...]
    nodes: tuple[RoadNode, ...]
    edges: tuple[RoadEdge, ...]
    dropped: dict[str, int] = field(default_factory=dict)


def _r(x: float) -> float:
    return round(x, 7)


def input_hash(ds: Dataset) -> str:
    """SHA-256 over a canonical form of the data, independent of entity order.

    Same input, same hash: re-running the pipeline on unchanged data is a no-op.
    """
    canonical = {
        "coverage": [_r(c) for c in ds.coverage],
        "source_timestamp": ds.source_timestamp.isoformat(),
        "places": sorted(
            [p.id, p.name, p.name_he, p.name_en, p.category, _r(p.lon), _r(p.lat)] for p in ds.places
        ),
        "addresses": sorted(
            [a.id, a.street, a.street_he, a.street_en, a.housenumber, _r(a.lon), _r(a.lat)]
            for a in ds.addresses
        ),
        "boundaries": sorted(
            [b.id, b.name, b.type, b.origin, shapely_wkt.dumps(b.geom, rounding_precision=7)]
            for b in ds.boundaries
        ),
        "nodes": sorted([n.id, _r(n.lon), _r(n.lat)] for n in ds.nodes),
        "edges": sorted(
            [e.id, e.from_node, e.to_node, e.oneway, e.speed_kmh, [[_r(x), _r(y)] for x, y in e.coords]]
            for e in ds.edges
        ),
    }
    blob = json.dumps(canonical, ensure_ascii=False, sort_keys=True, default=str).encode("utf-8")
    return hashlib.sha256(blob).hexdigest()
