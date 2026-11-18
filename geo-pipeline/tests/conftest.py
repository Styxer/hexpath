from datetime import datetime, timezone
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parents[2]
COVERAGE = (34.765, 32.060, 34.795, 32.090)  # west, south, east, north


def load_golden():
    rows = []
    for line in (REPO / "shared" / "normalization-golden.tsv").read_text(encoding="utf-8").splitlines():
        raw, _, expected = line.partition("\t")
        rows.append((raw, expected))
    return rows


@pytest.fixture
def mini_dataset():
    from geopipeline.model import Dataset, Place

    places = (
        Place("n1", "n1", "Cafe Dizengoff", None, None, "cafe", 34.7741, 32.0750, 0),
        Place("n2", "n2", "Square Bistro", None, None, "restaurant", 34.7750, 32.0779, 0),
        Place("n3", "n3", "North Bar", None, None, "bar", 34.7770, 32.0820, 0),
    )
    return Dataset(
        source="test",
        source_timestamp=datetime(2026, 11, 1, tzinfo=timezone.utc),
        coverage=COVERAGE,
        places=places, addresses=(), boundaries=(), nodes=(), edges=(), dropped={},
    )
