"""Run reports: the same JSON goes into dataset_release.report and data/reports/."""
from __future__ import annotations

import json
import os
from dataclasses import asdict
from pathlib import Path

from .sources import REPO


def reports_dir() -> Path:
    return Path(os.environ.get("HEXPATH_REPORTS_DIR", REPO / "data" / "reports"))


def build_report(ds, digest, findings, rec, decision, override) -> dict:
    return json.loads(json.dumps({
        "source": ds.source,
        "source_timestamp": ds.source_timestamp,
        "input_hash": digest,
        "counts": {"place": len(ds.places), "address": len(ds.addresses), "boundary": len(ds.boundaries),
                   "road_node": len(ds.nodes), "road_edge": len(ds.edges)},
        "dropped": ds.dropped,
        "findings": [asdict(f) for f in findings],
        "reconcile": asdict(rec),
        "gate": asdict(decision),
        "override": ({**asdict(override), "overridden_reasons": decision.reasons} if override else None),
    }, default=str))


def to_markdown(release_id: int, status: str, report: dict) -> str:
    lines = [f"# Release {release_id}: {status}", "",
             f"- Source: {report['source']} (data timestamp {report['source_timestamp']})",
             f"- Input hash: `{report['input_hash'][:16]}…`",
             f"- Counts: {report['counts']}", f"- Dropped in transform: {report['dropped'] or 'none'}", "",
             "## Validation", ""]
    lines += [f"- **{f['severity']}** `{f['rule']}` × {f['count']} (e.g. {', '.join(f['examples'])})"
              for f in report["findings"]] or ["- No findings."]
    rec = report["reconcile"]
    lines += ["", "## Reconcile", "",
              "- First release: no baseline to compare against." if rec["bootstrap"] else f"- Changes: {rec['changes']}"]
    lines += [f"- **Breach:** {b}" for b in rec["breaches"]]
    gate = report["gate"]
    lines += ["", "## Gate", "", f"- Passed: {gate['passed']}; overridden: {gate['overridden']}"]
    lines += [f"- Blocking: {r}" for r in gate["reasons"]]
    if report["override"]:
        o = report["override"]
        lines += [f"- Override by {o['user']} at {o['at']}: {o['reason']}"]
    return "\n".join(lines) + "\n"


def write_report(release_id: int, status: str, report: dict) -> Path:
    out = reports_dir()
    out.mkdir(parents=True, exist_ok=True)
    path = out / f"release-{release_id}.json"
    path.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    path.with_suffix(".md").write_text(to_markdown(release_id, status, report), encoding="utf-8")
    return path
