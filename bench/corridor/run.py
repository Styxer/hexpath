"""Benchmark the two along-route strategies against a running service.

For each random route and buffer, both strategies are called (alternating which goes first),
and the server-side duration (Server-Timing), the candidates examined before the exact filter,
the hits, and whether both returned the same ids are recorded.

  python bench/corridor/run.py --base-url http://localhost:8080 --routes 200 --out results.json --emit-md table.md
"""
from __future__ import annotations

import argparse
import json
import math
import random
import re
import statistics
import sys
import urllib.request

COVERAGE = (34.765, 32.060, 34.795, 32.090)
SHRINK = (0.0021, 0.0018)          # about 200 m in lon and lat at 32°N
M_PER_DEG_LAT = 111_320.0


def percentile(values, p):
    """Linear interpolation between closest ranks (numpy's default)."""
    xs = sorted(values)
    if len(xs) == 1:
        return float(xs[0])
    k = (len(xs) - 1) * p / 100
    lo, hi = math.floor(k), math.ceil(k)
    return xs[lo] + (xs[hi] - xs[lo]) * (k - lo)


def _inside(lon, lat):
    w, s, e, n = COVERAGE
    return w + SHRINK[0] <= lon <= e - SHRINK[0] and s + SHRINK[1] <= lat <= n - SHRINK[1]


def _haversine(lon1, lat1, lon2, lat2):
    p1, p2 = math.radians(lat1), math.radians(lat2)
    a = math.sin((p2 - p1) / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(math.radians(lon2 - lon1) / 2) ** 2
    return 2 * 6_371_008.8 * math.asin(math.sqrt(a))


def random_route(rnd: random.Random):
    """2-6 vertices, 1-5 km long, entirely inside the coverage area shrunk by about 200 m."""
    w, s, e, n = COVERAGE
    while True:
        vertices = rnd.randint(2, 6)
        target = rnd.uniform(1000, 5000)
        leg = target / (vertices - 1)
        lon = rnd.uniform(w + SHRINK[0], e - SHRINK[0])
        lat = rnd.uniform(s + SHRINK[1], n - SHRINK[1])
        coords = [(lon, lat)]
        for _ in range(vertices - 1):
            b = rnd.uniform(0, 2 * math.pi)
            lat2 = lat + leg * math.cos(b) / M_PER_DEG_LAT
            lon2 = lon + leg * math.sin(b) / (M_PER_DEG_LAT * math.cos(math.radians(lat)))
            coords.append((lon2, lat2))
            lon, lat = lon2, lat2
        length = sum(_haversine(*coords[i], *coords[i + 1]) for i in range(len(coords) - 1))
        if all(_inside(x, y) for x, y in coords) and 1000 <= length <= 5000:
            return coords, length


def call(base, strategy, coords, buffer_m):
    body = json.dumps({"route": {"type": "LineString", "coordinates": coords}, "buffer_m": buffer_m,
                       "limit": 100}).encode()
    req = urllib.request.Request(f"{base}/v1/search/along-route?strategy={strategy}&debug=true", data=body,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        timing = float(re.search(r"dur=([0-9.]+)", resp.headers["Server-Timing"]).group(1))
        data = json.load(resp)
    return timing, data["debug"]["candidates"], [r["id"] for r in data["results"]]


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default="http://localhost:8080")
    ap.add_argument("--routes", type=int, default=200)
    ap.add_argument("--buffers", default="100,300,1000")
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--out", default="results.json")
    ap.add_argument("--emit-md", default="table.md")
    a = ap.parse_args(argv)
    rnd = random.Random(a.seed)
    buffers = [float(b) for b in a.buffers.split(",")]
    routes = [random_route(rnd)[0] for _ in range(a.routes)]
    strategies = ("postgis", "h3")

    for s in strategies:                      # warm-up: JIT, connection pool, Postgres caches
        for _ in range(3):
            call(a.base_url, s, routes[0], 300)

    rows = []
    for b in buffers:
        samples = {s: {"ms": [], "candidates": [], "hits": []} for s in strategies}
        identical = 0
        for i, coords in enumerate(routes):
            order = strategies if i % 2 == 0 else strategies[::-1]
            ids = {}
            for s in order:
                ms, cand, hit_ids = call(a.base_url, s, coords, b)
                samples[s]["ms"].append(ms)
                samples[s]["candidates"].append(cand)
                samples[s]["hits"].append(len(hit_ids))
                ids[s] = hit_ids
            identical += ids["postgis"] == ids["h3"]
        for s in strategies:
            rows.append({"buffer_m": b, "strategy": s,
                         "p50_ms": round(percentile(samples[s]["ms"], 50), 2),
                         "p95_ms": round(percentile(samples[s]["ms"], 95), 2),
                         "mean_candidates": round(statistics.mean(samples[s]["candidates"]), 1),
                         "mean_results": round(statistics.mean(samples[s]["hits"]), 1),
                         "identical_pct": round(100.0 * identical / len(routes), 1)})

    with open(a.out, "w") as f:
        json.dump({"routes": a.routes, "seed": a.seed, "rows": rows}, f, indent=2)
    lines = ["| buffer (m) | strategy | p50 (ms) | p95 (ms) | mean candidates | mean results | identical results |",
             "|---:|---|---:|---:|---:|---:|---:|"]
    lines += [f"| {r['buffer_m']:.0f} | {r['strategy']} | {r['p50_ms']} | {r['p95_ms']} | {r['mean_candidates']} |"
              f" {r['mean_results']} | {r['identical_pct']}% |" for r in rows]
    with open(a.emit_md, "w") as f:
        f.write("\n".join(lines) + "\n")
    print("\n".join(lines))
    return 0


if __name__ == "__main__":
    sys.exit(main())
