"""Validation rules. ERROR findings block a release at the gate; WARN findings are reported."""
from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass
from itertools import combinations
from typing import Literal

from .geo import haversine_m, inside
from .model import Dataset
from .normalize import normalize
from .roads import largest_scc_share

MAX_EXAMPLES = 5
DUPLICATE_RADIUS_M = 5.0
SCC_ERROR_BELOW = 0.80
SCC_WARN_BELOW = 0.95


@dataclass(frozen=True)
class Finding:
    rule: str
    severity: Literal["ERROR", "WARN"]
    count: int
    examples: tuple[str, ...]


def _finding(rule, severity, ids) -> Finding | None:
    ids = list(ids)
    return Finding(rule, severity, len(ids), tuple(ids[:MAX_EXAMPLES])) if ids else None


def _duplicates(ds: Dataset):
    by_name = defaultdict(list)
    for p in ds.places:
        key = normalize(p.name)
        if key:
            by_name[key].append(p)
    for group in by_name.values():
        seen = []
        for p in group:
            if any(haversine_m(p.lon, p.lat, q.lon, q.lat) <= DUPLICATE_RADIUS_M for q in seen):
                yield p.id
            else:
                seen.append(p)


def validate(ds: Dataset) -> list[Finding]:
    zones = [b for b in ds.boundaries if b.type == "REGULATORY"]
    share = largest_scc_share(ds.nodes, ds.edges)
    candidates = [
        _finding("boundary_invalid_geometry", "ERROR",
                 (b.id for b in ds.boundaries if not b.geom.is_valid)),
        _finding("place_outside_coverage", "ERROR",
                 (p.id for p in ds.places if not inside(ds.coverage, p.lon, p.lat))),
        _finding("required_field_missing", "ERROR",
                 (p.id or "<no id>" for p in ds.places
                  if not (p.id or "").strip() or not (p.name or "").strip() or not (p.category or "").strip())),
        # Only regulatory zones must not overlap; admin levels nest by design.
        _finding("boundary_overlap_same_type", "ERROR",
                 (f"{a.id}+{b.id}" for a, b in combinations(zones, 2)
                  if a.geom.is_valid and b.geom.is_valid and a.geom.intersection(b.geom).area > 0)),
        _finding("graph_connectivity_low", "ERROR",
                 [f"largest_scc_share={share:.3f}"] if share < SCC_ERROR_BELOW else []),
        _finding("graph_connectivity_warn", "WARN",
                 [f"largest_scc_share={share:.3f}"] if SCC_ERROR_BELOW <= share < SCC_WARN_BELOW else []),
        _finding("duplicate_place", "WARN", _duplicates(ds)),
        _finding("housenumber_unparsed", "WARN",
                 (a.id for a in ds.addresses if a.housenumber_int is None)),
    ]
    return [f for f in candidates if f is not None]
