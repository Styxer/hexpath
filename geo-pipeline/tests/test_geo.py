import pytest

from conftest import COVERAGE
from geopipeline.geo import haversine_m, inside


def test_haversine_known_distance():  # 0.001 degrees of latitude at 32°N
    assert haversine_m(34.774, 32.077, 34.774, 32.078) == pytest.approx(111.19, abs=0.2)


def test_inside_coverage():
    assert inside(COVERAGE, 34.774, 32.077)
    assert not inside(COVERAGE, 32.077, 34.774)  # swapped coordinates
