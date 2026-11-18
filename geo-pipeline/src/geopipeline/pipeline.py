"""One pipeline run: extract, transform, validate, reconcile, gate, then stage and publish."""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import psycopg

from .db.lock import pipeline_lock
from .db.migrate import require_schema
from .db.store import ReleaseStore
from .gate import Override, decide
from .model import Dataset, input_hash
from .osm.transform import transform
from .reconcile import Thresholds, reconcile
from .report import build_report, reports_dir, write_report
from .sources import COVERAGE, load_json, osm_path
from .validate import validate


@dataclass(frozen=True)
class RunResult:
    release_id: int | None
    status: str
    report_path: Path
    noop: bool
    reasons: tuple[str, ...] = ()


def load_dataset(source: str, zones: Path) -> Dataset:
    """Read and transform the input. Raises InputError for missing or unreadable files."""
    return transform(load_json(osm_path(source)), load_json(zones), COVERAGE)


def run(source: str, zones: Path, writer_dsn: str, override: Override | None, publish: bool = True) -> RunResult:
    return run_dataset(load_dataset(source, zones), writer_dsn, override, publish)


def run_dataset(ds: Dataset, writer_dsn: str, override: Override | None, publish: bool = True) -> RunResult:
    digest = input_hash(ds)
    with psycopg.connect(writer_dsn, autocommit=True) as conn, pipeline_lock(conn):
        require_schema(conn)
        store = ReleaseStore(conn)
        existing = store.find_by_hash(digest)
        if existing is not None:
            status = conn.execute("select status from dataset_release where id = %s", (existing,)).fetchone()[0]
            return RunResult(existing, status, reports_dir() / f"release-{existing}.json", noop=True)

        findings = validate(ds)
        rec = reconcile(ds, store.load_baseline(), Thresholds())
        decision = decide(findings, rec, override)
        report = build_report(ds, digest, findings, rec, decision, override if decision.overridden else None)

        if not decision.passed:
            rid, status = store.record_rejected(ds, report), "REJECTED"
        else:
            rid = store.stage(ds, report, override if decision.overridden else None)
            status = "STAGED"
            if publish:
                store.publish(rid)
                status = "ACTIVE"
        return RunResult(rid, status, write_report(rid, status, report), noop=False,
                         reasons=tuple(decision.reasons))
