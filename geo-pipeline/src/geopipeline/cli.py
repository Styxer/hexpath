"""Command line: python -m geopipeline <command>.

Exit codes: 0 ok (published, staged or no-op) · 2 usage or input error · 3 release rejected
· 4 another run holds the lock · 5 database schema version mismatch.
"""
from __future__ import annotations

import argparse
import getpass
import logging
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

EXIT_OK, EXIT_USAGE, EXIT_REJECTED, EXIT_BUSY, EXIT_SCHEMA = 0, 2, 3, 4, 5


def _dsn(name: str) -> str:
    value = os.environ.get(name)
    if not value:
        raise UsageError(f"set {name} (see .env.example)")
    return value


class UsageError(RuntimeError):
    pass


def _parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="geopipeline", description="hexpath release pipeline")
    sub = p.add_subparsers(dest="command", required=True)
    sub.add_parser("migrate", help="apply db/migrations as geo_owner (HEXPATH_OWNER_DSN)")
    f = sub.add_parser("fetch-osm", help="download the coverage area from Overpass")
    f.add_argument("--out", type=Path, required=True)
    r = sub.add_parser("run", help="extract, validate, reconcile, gate, stage and publish")
    r.add_argument("--source", default="osm", help="'osm' (committed extract) or 'fixture:mini'")
    r.add_argument("--zones", type=Path, help="regulatory zones GeoJSON")
    r.add_argument("--override", action="store_true", help="publish despite blocking findings (needs --reason)")
    r.add_argument("--reason", help="why the override is needed; recorded in the release report")
    r.add_argument("--no-publish", action="store_true", help="stage only")
    pub = sub.add_parser("publish", help="activate a STAGED release")
    pub.add_argument("release_id", type=int)
    sub.add_parser("rollback", help="return to the previous release")
    sub.add_parser("gc", help="drop partitions of old releases (keeps ACTIVE + 2 RETIRED)")
    sub.add_parser("relay", help="forward pending outbox events (logging relay)")
    e = sub.add_parser("export-seed", help="write the Java test seed from the mini fixture")
    e.add_argument("--out", type=Path, default=Path("shared/seed/seed.sql"))
    rep = sub.add_parser("report", help="print a release report")
    rep.add_argument("release_id", type=int)
    return p


def _run(args) -> int:
    from .gate import Override
    from .pipeline import load_dataset, run_dataset
    from .sources import default_zones

    override = None
    if args.override:
        override = Override(getpass.getuser(), args.reason or "", datetime.now(timezone.utc))
    ds = load_dataset(args.source, args.zones or default_zones(args.source))  # input errors first
    result = run_dataset(ds, _dsn("HEXPATH_WRITER_DSN"), override, publish=not args.no_publish)
    verb = "unchanged input, nothing to do:" if result.noop else "release"
    print(f"{verb} {result.release_id} {result.status} — report: {result.report_path}")
    return EXIT_REJECTED if result.status == "REJECTED" else EXIT_OK


def main(argv=None) -> int:
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    parser = _parser()
    args = parser.parse_args(argv)
    if args.command == "run" and args.override and not (args.reason or "").strip():
        parser.error("--override needs --reason")

    from .db.lock import PipelineBusy
    from .db.migrate import SchemaMismatch
    from .db.store import NothingToRollBack
    from .sources import InputError

    try:
        if args.command == "run":
            return _run(args)
        if args.command == "migrate":
            from .db.migrate import migrate
            print(f"schema version {migrate(_dsn('HEXPATH_OWNER_DSN'))}")
        elif args.command == "fetch-osm":
            from .osm.fetch import fetch_overpass
            from .sources import COVERAGE
            print(fetch_overpass(COVERAGE, args.out))
        elif args.command == "export-seed":
            from .seed import export_seed
            print(export_seed(args.out))
        else:
            import psycopg

            from .db.store import ReleaseStore
            with psycopg.connect(_dsn("HEXPATH_WRITER_DSN"), autocommit=True) as conn:
                store = ReleaseStore(conn)
                if args.command == "publish":
                    store.publish(args.release_id)
                    print(f"release {args.release_id} ACTIVE")
                elif args.command == "rollback":
                    print(f"rolled back; release {store.rollback()} ACTIVE")
                elif args.command == "gc":
                    print(f"dropped partitions of releases: {store.gc() or 'none'}")
                elif args.command == "relay":
                    from .events import LoggingRelay, relay_pending
                    print(f"relayed {relay_pending(conn, LoggingRelay())} events")
                elif args.command == "report":
                    from .report import to_markdown
                    row = conn.execute("select status, report from dataset_release where id = %s",
                                       (args.release_id,)).fetchone()
                    if row is None:
                        raise UsageError(f"no release {args.release_id}")
                    print(to_markdown(args.release_id, row[0], row[1]))
        return EXIT_OK
    except (UsageError, InputError, NothingToRollBack, ValueError) as e:
        print(f"error: {e}", file=sys.stderr)
        return EXIT_USAGE
    except PipelineBusy as e:
        print(f"busy: {e}", file=sys.stderr)
        return EXIT_BUSY
    except SchemaMismatch as e:
        print(f"error: {e}", file=sys.stderr)
        return EXIT_SCHEMA
