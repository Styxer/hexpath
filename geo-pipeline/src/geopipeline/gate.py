"""The release gate: ERROR findings or reconcile breaches block, unless overridden with a reason."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime

from .reconcile import ReconcileResult
from .validate import Finding


@dataclass(frozen=True)
class Override:
    user: str
    reason: str
    at: datetime

    def __post_init__(self):
        if not (self.reason or "").strip():
            raise ValueError("an override needs a reason (--reason)")


@dataclass(frozen=True)
class GateDecision:
    passed: bool
    overridden: bool
    reasons: list[str]


def decide(findings: list[Finding], rec: ReconcileResult, override: Override | None) -> GateDecision:
    reasons = [f"{f.rule}: {f.count}" for f in findings if f.severity == "ERROR"] + list(rec.breaches)
    return GateDecision(
        passed=not reasons or override is not None,
        overridden=bool(reasons) and override is not None,
        reasons=reasons,
    )
