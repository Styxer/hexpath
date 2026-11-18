import pytest

from geopipeline.db.lock import PipelineBusy, pipeline_lock

from .conftest import connect

pytestmark = pytest.mark.db


def test_lock_is_exclusive(pg):
    with pipeline_lock(connect(pg.writer)):
        with pytest.raises(PipelineBusy):
            pipeline_lock(connect(pg.writer)).__enter__()


def test_lock_released_on_exit(pg):
    with pipeline_lock(connect(pg.writer)):
        pass
    with pipeline_lock(connect(pg.writer)):
        pass
