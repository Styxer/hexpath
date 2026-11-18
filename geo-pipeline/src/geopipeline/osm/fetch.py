"""Download the coverage area from the Overpass API (run rarely; the result is committed)."""
from __future__ import annotations

import gzip
import time
from pathlib import Path

import httpx

OVERPASS_URL = "https://overpass-api.de/api/interpreter"
# Public instances are often overloaded; try the main one, then community mirrors.
OVERPASS_ENDPOINTS = (
    OVERPASS_URL,
    "https://overpass.kumi.systems/api/interpreter",
    "https://overpass.private.coffee/api/interpreter",
)
TRIES_PER_ENDPOINT = 2


class FetchError(RuntimeError):
    pass
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


def fetch_overpass(bbox, out_path: Path, endpoints=OVERPASS_ENDPOINTS, client: httpx.Client | None = None,
                   pause_s: float = 10.0) -> Path:
    """Download to out_path (gzipped). Writes nothing unless a download succeeds."""
    client = client or httpx.Client(timeout=300, headers={"User-Agent": "hexpath-geopipeline/0.1 (portfolio project)"})
    query = build_query(bbox)
    failures = []
    for endpoint in endpoints:
        for attempt in range(1, TRIES_PER_ENDPOINT + 1):
            try:
                resp = client.post(endpoint, data={"data": query})
                if resp.status_code == 200:
                    out_path.parent.mkdir(parents=True, exist_ok=True)
                    with gzip.open(out_path, "wb") as f:
                        f.write(resp.content)
                    return out_path
                failures.append(f"{endpoint} attempt {attempt}: HTTP {resp.status_code}")
            except httpx.HTTPError as e:
                failures.append(f"{endpoint} attempt {attempt}: {type(e).__name__}")
            time.sleep(pause_s)
    raise FetchError("Overpass download failed on every endpoint:\n  " + "\n  ".join(failures))
