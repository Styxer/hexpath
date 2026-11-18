"""Apply db/migrations/V###__*.sql in order, as geo_owner. One source of truth for both services."""
from __future__ import annotations

import os
import re
from pathlib import Path

import psycopg

EXPECTED_SCHEMA_VERSION = 4
_VERSION = re.compile(r"^V(\d+)__.+\.sql$")


class SchemaMismatch(RuntimeError):
    def __init__(self, found: int, expected: int):
        super().__init__(f"database schema version {found}, expected {expected} — run: python -m geopipeline migrate")
        self.found, self.expected = found, expected


def migrations_dir() -> Path:
    env = os.environ.get("HEXPATH_MIGRATIONS")
    return Path(env) if env else Path(__file__).resolve().parents[4] / "db" / "migrations"


def current_version(conn) -> int:
    if conn.execute("select to_regclass('public.schema_version')").fetchone()[0] is None:
        return 0
    return conn.execute("select version from schema_version").fetchone()[0]


def migrate(owner_dsn: str) -> int:
    with psycopg.connect(owner_dsn, autocommit=True) as conn:
        version = current_version(conn)
        files = sorted((int(m.group(1)), p) for p in migrations_dir().iterdir()
                       if (m := _VERSION.match(p.name)))
        for number, path in files:
            if number <= version:
                continue
            with conn.transaction():
                conn.execute(path.read_text(encoding="utf-8"))
            version = number
        return version


def require_schema(conn, expected: int = EXPECTED_SCHEMA_VERSION) -> None:
    found = current_version(conn)
    if found != expected:
        raise SchemaMismatch(found, expected)
