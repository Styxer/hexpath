import psycopg
import pytest

from geopipeline.db.migrate import EXPECTED_SCHEMA_VERSION, SchemaMismatch, require_schema

from .conftest import connect

pytestmark = pytest.mark.db


def test_schema_version_is_4(pg):
    assert EXPECTED_SCHEMA_VERSION == 4
    assert require_schema(connect(pg.writer)) is None


def test_schema_mismatch_is_reported(pg):
    with pytest.raises(SchemaMismatch) as e:
        require_schema(connect(pg.writer), expected=5)
    assert (e.value.found, e.value.expected) == (4, 5)


def test_reader_cannot_write(pg):
    with pytest.raises(psycopg.errors.InsufficientPrivilege):
        connect(pg.reader).execute("insert into dataset_event(type) values ('x')")


def test_writer_cannot_create_tables(pg):
    with pytest.raises(psycopg.errors.InsufficientPrivilege):
        connect(pg.writer).execute("create table sneaky (x int)")


def test_trigram_handles_hebrew(pg):          # spec §7.5 day-one spike
    assert connect(pg.reader).execute("select similarity('דיזנגוף','דיזינגוף')").fetchone()[0] > 0.3


def test_migrate_is_idempotent(pg):
    from geopipeline.db.migrate import migrate
    assert migrate(pg.owner) == 4
