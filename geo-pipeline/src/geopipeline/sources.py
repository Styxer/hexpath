"""Where pipeline input comes from. Every input problem becomes an InputError (exit code 2)."""
from __future__ import annotations

import gzip
import json
import os
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
COVERAGE = (34.765, 32.060, 34.795, 32.090)  # central Tel Aviv-Yafo: west, south, east, north
DEFAULT_OSM = REPO / "data" / "osm" / "tel-aviv-center.json.gz"
MINI_OSM = REPO / "data" / "fixtures" / "mini" / "osm.json"
MINI_ZONES = REPO / "data" / "fixtures" / "mini" / "zones.geojson"
SYNTHETIC_ZONES = REPO / "data" / "synthetic" / "regulatory_zones.geojson"


class InputError(RuntimeError):
    pass


def osm_path(source: str) -> Path:
    if source == "osm":
        return Path(os.environ.get("HEXPATH_OSM_PATH", DEFAULT_OSM))
    if source == "fixture:mini":
        return MINI_OSM
    raise InputError(f"unknown source {source!r}; use 'osm' or 'fixture:mini'")


def default_zones(source: str) -> Path:
    return MINI_ZONES if source == "fixture:mini" else SYNTHETIC_ZONES


def load_json(path: Path) -> dict:
    if not Path(path).exists():
        raise InputError(f"input file not found: {path}")
    try:
        opener = gzip.open if str(path).endswith(".gz") else open
        with opener(path, "rt", encoding="utf-8") as f:
            return json.load(f)
    except (OSError, EOFError, ValueError) as e:
        raise InputError(f"cannot read {path}: {e}") from None
