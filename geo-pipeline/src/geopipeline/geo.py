"""Small geodesy helpers. Haversine is accurate to well under 0.5% at city scale."""
from math import asin, cos, radians, sin, sqrt

EARTH_RADIUS_M = 6_371_008.8  # mean Earth radius (IUGG)

Coverage = tuple[float, float, float, float]  # west, south, east, north


def haversine_m(lon1: float, lat1: float, lon2: float, lat2: float) -> float:
    p1, p2 = radians(lat1), radians(lat2)
    dlat, dlon = p2 - p1, radians(lon2 - lon1)
    a = sin(dlat / 2) ** 2 + cos(p1) * cos(p2) * sin(dlon / 2) ** 2
    return 2 * EARTH_RADIUS_M * asin(sqrt(a))


def inside(coverage: Coverage, lon: float, lat: float) -> bool:
    west, south, east, north = coverage
    return west <= lon <= east and south <= lat <= north
