"""Download the coverage area from the Overpass API (run rarely; the result is committed)."""
from __future__ import annotations

import gzip
from pathlib import Path

import httpx

OVERPASS_URL = "https://overpass-api.de/api/interpreter"
HIGHWAYS = "trunk|primary|secondary|tertiary|unclassified|residential|living_street|service"

QUERY = """[out:json][timeout:180];
(
  node["amenity"]({s},{w},{n},{e});
  way["amenity"]({s},{w},{n},{e});
  node["tourism"]({s},{w},{n},{e});
  way["tourism"]({s},{w},{n},{e});
  node["shop"]({s},{w},{n},{e});
  way["shop"]({s},{w},{n},{e});
  node["addr:housenumber"]({s},{w},{n},{e});
  way["addr:housenumber"]({s},{w},{n},{e});
  way["highway"~"^({hw})$"]({s},{w},{n},{e});
  relation["boundary"="administrative"]({s},{w},{n},{e});
);
out body geom;"""


def build_query(bbox: tuple[float, float, float, float]) -> str:
    w, s, e, n = bbox
    return QUERY.format(w=w, s=s, e=e, n=n, hw=HIGHWAYS)


def fetch_overpass(bbox, out_path: Path, endpoint: str = OVERPASS_URL) -> Path:
    resp = httpx.post(endpoint, data={"data": build_query(bbox)}, timeout=300,
                      headers={"User-Agent": "hexpath-geopipeline/0.1 (portfolio project)"})
    resp.raise_for_status()
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with gzip.open(out_path, "wb") as f:
        f.write(resp.content)
    return out_path
