import pytest

from conftest import load_golden
from geopipeline.normalize import normalize


@pytest.mark.parametrize("raw,expected", load_golden())
def test_normalize_matches_golden(raw, expected):
    assert normalize(raw) == expected


def test_normalize_none_is_empty():
    assert normalize(None) == ""
