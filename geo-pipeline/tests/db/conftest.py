"""PostGIS for database tests.

CI: Testcontainers with the production image. Local: set HEXPATH_TEST_ADMIN_DSN to a
superuser DSN of a disposable server; the hexpath database and roles are recreated there.
"""
import os
import subprocess
from dataclasses import dataclass, replace
from datetime import timedelta

import psycopg
import pytest

from conftest import REPO
from geopipeline.db.migrate import migrate
from geopipeline.db.store import ReleaseStore

PASSWORDS = {"GEO_OWNER_PASSWORD": "owner-dev", "GEO_WRITER_PASSWORD": "writer-dev",
             "GEO_READER_PASSWORD": "reader-dev"}
ROLES_SQL = REPO / "db" / "roles.sql"
pytestmark = pytest.mark.db


@dataclass(frozen=True)
class Pg:
    owner: str
    writer: str
    reader: str


def connect(dsn):
    return psycopg.connect(dsn, autocommit=True)


def _dsns(host, port):
    return Pg(*(f"postgresql://geo_{r}:{r}-dev@{host}:{port}/hexpath" for r in ("owner", "writer", "reader")))


def _local(admin_dsn):
    with connect(admin_dsn) as c:
        c.execute("select pg_terminate_backend(pid) from pg_stat_activity where datname = 'hexpath' and pid <> pg_backend_pid()")
        c.execute("drop database if exists hexpath")
        for role in ("geo_owner", "geo_writer", "geo_reader"):
            c.execute(f"drop role if exists {role}")
        c.execute("create database hexpath template template0 encoding 'UTF8'")
    info = psycopg.conninfo.conninfo_to_dict(admin_dsn)
    target = psycopg.conninfo.make_conninfo(admin_dsn, dbname="hexpath")
    subprocess.run(["psql", "-q", "-v", "ON_ERROR_STOP=1", "-d", target, "-f", str(ROLES_SQL)],
                   check=True, env={**os.environ, **PASSWORDS})
    return _dsns(info.get("host", "localhost"), info.get("port", "5432"))


@pytest.fixture(scope="session")
def pg():
    admin = os.environ.get("HEXPATH_TEST_ADMIN_DSN")
    if admin:
        dsns = _local(admin)
        migrate(dsns.owner)
        yield dsns
        return
    from testcontainers.postgres import PostgresContainer

    container = (PostgresContainer("postgis/postgis:17-3.5", username="postgres", password="postgres-dev", dbname="hexpath")
                 .with_volume_mapping(str(ROLES_SQL), "/roles.sql"))
    for k, v in PASSWORDS.items():
        container.with_env(k, v)
    with container:
        code, out = container.get_wrapped_container().exec_run(
            ["psql", "-q", "-v", "ON_ERROR_STOP=1", "-U", "postgres", "-d", "hexpath", "-f", "/roles.sql"],
            environment=PASSWORDS)
        assert code == 0, out.decode()
        dsns = _dsns(container.get_container_host_ip(), container.get_exposed_port(5432))
        migrate(dsns.owner)
        yield dsns


@pytest.fixture
def clean(pg):
    with connect(pg.owner) as c:
        for (rid,) in c.execute("select id from dataset_release").fetchall():
            c.execute("select hexpath_drop_partitions(%s)", (rid,))
        c.execute("truncate dataset_release, dataset_event restart identity")
    return pg


@pytest.fixture
def store(clean):
    return ReleaseStore(connect(clean.writer))


def variant(ds, n):
    """A distinct release: same data, different source timestamp, so a different input hash."""
    return replace(ds, source_timestamp=ds.source_timestamp + timedelta(days=n))


def status(pg, rid):
    with connect(pg.owner) as c:
        return c.execute("select status from dataset_release where id = %s", (rid,)).fetchone()[0]


def partition_exists(pg, rid):
    with connect(pg.owner) as c:
        return c.execute("select to_regclass(%s) is not null", (f"place_r{rid}",)).fetchone()[0]


class Listener:
    def __init__(self, dsn, channel):
        self.conn = connect(dsn)
        self.conn.execute(f"listen {channel}")

    def next(self, timeout):
        for note in self.conn.notifies(timeout=timeout, stop_after=1):
            return note.payload
        return None

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.conn.close()
