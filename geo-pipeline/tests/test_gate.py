from datetime import datetime, timezone

import pytest

from geopipeline.gate import Override, decide
from geopipeline.reconcile import ReconcileResult
from geopipeline.validate import Finding

NOW = datetime(2026, 11, 18, tzinfo=timezone.utc)
OK_REC = ReconcileResult(False, {}, [])


def test_override_requires_reason():
    with pytest.raises(ValueError):
        Override("ofir", "   ", NOW)


def test_override_passes_and_is_marked():
    d = decide([Finding("boundary_invalid_geometry", "ERROR", 1, ("zone-a",))], OK_REC, Override("ofir", "hotfix", NOW))
    assert d.passed and d.overridden


def test_warn_only_passes():
    assert decide([Finding("duplicate_place", "WARN", 2, ())], OK_REC, None).passed


def test_error_blocks_and_is_listed():
    d = decide([Finding("place_outside_coverage", "ERROR", 3, ())], OK_REC, None)
    assert not d.passed and not d.overridden and d.reasons == ["place_outside_coverage: 3"]


def test_breach_blocks():
    d = decide([], ReconcileResult(False, {}, ["place removed 50.0% > 10.0%"]), None)
    assert not d.passed and d.reasons == ["place removed 50.0% > 10.0%"]


def test_override_on_clean_release_is_not_marked_overridden():
    d = decide([], OK_REC, Override("ofir", "just in case", NOW))
    assert d.passed and not d.overridden
