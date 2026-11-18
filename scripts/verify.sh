#!/usr/bin/env bash
# Run every test stage and print one PASS/FAIL line per stage. Needs Docker, a JDK 21+ and Python 3.10+.
cd "$(dirname "$0")/.."
results=(); failed=0
stage() { local name=$1; shift
  if "$@" > ".hexpath/verify-$name.log" 2>&1; then results+=("PASS  $name"); else results+=("FAIL  $name  (.hexpath/verify-$name.log)"); failed=1; fi; }
mkdir -p .hexpath
[ -d .venv ] || python3 -m venv .venv
.venv/bin/pip install -q -e "geo-pipeline[test]" -r e2e/requirements.txt
stage python-unit bash -c "cd geo-pipeline && ../.venv/bin/pytest -q -m 'not db'"
stage python-db   bash -c "cd geo-pipeline && ../.venv/bin/pytest -q -m db"
stage java        bash -c "cd geo-service && ./mvnw -q verify"
stage demo-stack  ./scripts/demo.sh
stage e2e         bash -c "set -a; . ./.env; set +a; .venv/bin/pytest -q e2e"
printf '%s\n' "${results[@]}"
exit $failed
