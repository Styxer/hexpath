import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from run import percentile, random_route  # noqa: E402


def test_percentile_linear_interpolation():
    xs = list(range(1, 101))
    assert percentile(xs, 50) == 50.5
    assert abs(percentile(xs, 95) - 95.05) < 1e-9


def test_percentile_single_value():
    assert percentile([7.0], 95) == 7.0


def test_random_route_stays_inside_shrunk_coverage():
    import random
    rnd = random.Random(7)
    for _ in range(200):
        coords, length_m = random_route(rnd)
        assert 2 <= len(coords) <= 6 and 1000 <= length_m <= 5000
        for lon, lat in coords:
            assert 34.765 + 0.0021 <= lon <= 34.795 - 0.0021 and 32.060 + 0.0018 <= lat <= 32.090 - 0.0018
