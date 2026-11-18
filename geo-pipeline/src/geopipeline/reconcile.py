"""Compare a candidate release against the ACTIVE one and flag suspicious shifts."""
from __future__ import annotations

from dataclasses import dataclass, field

from .geo import haversine_m
from .model import Dataset
from .normalize import normalize


@dataclass(frozen=True)
class Baseline:
    release_id: int
    counts: dict[str, int]
    places: dict[str, tuple[float, float, str]]   # id -> (lon, lat, normalized name)
    boundary_areas: dict[str, float]              # id -> planar area (only ratios are used)


@dataclass(frozen=True)
class Thresholds:
    count_change_pct: float = 10.0
    moved_m: float = 50.0
    moved_share_pct: float = 5.0
    boundary_area_change_pct: float = 20.0


@dataclass(frozen=True)
class ReconcileResult:
    bootstrap: bool
    changes: dict = field(default_factory=dict)
    breaches: list[str] = field(default_factory=list)


def _counts(ds: Dataset) -> dict[str, int]:
    return {"place": len(ds.places), "address": len(ds.addresses),
            "boundary": len(ds.boundaries), "road_edge": len(ds.edges)}


def reconcile(ds: Dataset, baseline: Baseline | None, t: Thresholds) -> ReconcileResult:
    if baseline is None:
        return ReconcileResult(True, {"counts": _counts(ds)}, [])

    breaches: list[str] = []
    new = {p.id: p for p in ds.places}
    old = baseline.places
    common = new.keys() & old.keys()
    moved = sum(1 for i in common
                if haversine_m(old[i][0], old[i][1], new[i].lon, new[i].lat) > t.moved_m)
    place_changes = {
        "added": len(new.keys() - old.keys()),
        "removed": len(old.keys() - new.keys()),
        "moved": moved,
        "renamed": sum(1 for i in common if normalize(new[i].name) != old[i][2]),
    }

    def pct(part: int, whole: int) -> float:
        return 100.0 * part / whole if whole else (100.0 if part else 0.0)

    for kind in ("added", "removed"):
        share = pct(place_changes[kind], len(old))
        if share > t.count_change_pct:
            breaches.append(f"place {kind} {share:.1f}% > {t.count_change_pct}%")
    moved_share = pct(moved, len(old))
    if moved_share > t.moved_share_pct:
        breaches.append(f"place moved {moved_share:.1f}% > {t.moved_share_pct}% (moved > {t.moved_m} m)")

    new_counts = _counts(ds)
    for kind in ("address", "boundary", "road_edge"):
        before, after = baseline.counts.get(kind, 0), new_counts[kind]
        share = pct(abs(after - before), before)
        if share > t.count_change_pct:
            breaches.append(f"{kind} count change {share:.1f}% > {t.count_change_pct}% ({before} -> {after})")

    area_change = {}
    for b in ds.boundaries:
        if b.id in baseline.boundary_areas:
            before = baseline.boundary_areas[b.id]
            area_change[b.id] = pct(abs(b.geom.area - before), before)
            if area_change[b.id] > t.boundary_area_change_pct:
                breaches.append(f"boundary_area_change_pct {b.id} {area_change[b.id]:.1f}% > {t.boundary_area_change_pct}%")

    changes = {"place": place_changes, "counts": new_counts, "baseline_counts": dict(baseline.counts),
               "boundary_area_change_pct": area_change}
    return ReconcileResult(False, changes, breaches)
