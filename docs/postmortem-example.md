# Postmortem (worked example): a regulatory boundary shipped with the wrong extent

> This incident is a **drill**. It did not happen to real users. It is written the way a real
> postmortem would be, and the scenario is executed by `e2e/test_release_lifecycle.py` on every CI run.

## Summary

A release published the regulatory zone `zone-a` about nine times larger than it is (in area, +800%). For the duration,
`GET /v1/boundaries/containing` reported addresses outside the real zone as inside it, so a consuming
team's compliance check would have flagged listings that are not restricted. The release was rolled
back, and the pipeline now blocks this class of change.

Blameless format: the question is what let the change through, not who made it.

## Impact

- Endpoint affected: `/v1/boundaries/containing` with `type=REGULATORY` (and anything built on it).
- Wrong answers: points inside the enlarged area but outside the real `zone-a`, for example
  (32.0700, 34.7700).
- No other data was affected; places, addresses and roads were identical to the previous release.

## Timeline (drill)

| Step | What happened |
|---|---|
| T+0 | The pipeline ran with an edited zones file whose `zone-a` was far too large. |
| T+0 | The gate rejected it: `boundary_area_change_pct zone-a 800.0% > 20.0%` (exit code 3). |
| T+1 | The run was repeated with `--override --reason "…"`, as if the change were believed correct. |
| T+1 | Release activated; the service swapped to it within seconds (`X-Dataset-Release` changed). |
| T+5 | A consuming team reported unexpected "restricted" results, quoting `X-Dataset-Release`. |
| T+6 | `python -m geopipeline report <id>` showed the override and the 800% area change. |
| T+6 | `python -m geopipeline rollback`. The previous release was active again within seconds. |

## Root cause

The wrong geometry came from a manual edit of the zones file. The pipeline did catch it: the
`boundary_area_change_pct` reconcile rule flagged an 800% change. It went live only because an
override accepted the finding without a second look.

In the original design, reconcile compared only counts (added, removed, moved), so a boundary that
changed shape but kept its id would have passed silently. The area-change rule was added for exactly
this case.

## What went well

- The release header let the reporter and the on-call engineer talk about the same data immediately.
- Rollback is one command and one transaction, and needs no deploy.
- The previous release was still intact, because retention keeps the two newest retired releases.

## Action items

| Action | Status |
|---|---|
| Reconcile rule for boundary area change (> 20% blocks) | Done (`geopipeline/reconcile.py`) |
| Overrides require a written reason, stored with user and time | Done |
| The whole incident as an automated drill in CI | Done (`e2e/test_release_lifecycle.py`) |
| Data changes arrive as reviewed branches, not manual file edits | Done (`fetch-osm.yml`) |
| Second-person approval for overrides on `REGULATORY` boundaries | Open |
| Endpoint returning boundary geometry, so reviewers can see a diff on a map | Open (phase 2) |
