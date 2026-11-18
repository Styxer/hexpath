#!/usr/bin/env bash
# Start the whole stack locally: PostGIS, a published release of the committed OSM extract, the service.
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f .env ] || cp .env.example .env
set -a; . ./.env; set +a
docker compose up -d --wait
[ -d .venv ] || python3 -m venv .venv
.venv/bin/pip install -q -e geo-pipeline
.venv/bin/python -m geopipeline migrate
.venv/bin/python -m geopipeline run --source osm
mkdir -p .hexpath
(cd geo-service && ./mvnw -q spring-boot:run > ../.hexpath/service.log 2>&1 &)
echo -n "waiting for the service"
for _ in $(seq 1 120); do
  curl -sf http://localhost:8080/actuator/health/readiness >/dev/null && { echo " ready: http://localhost:8080/"; exit 0; }
  echo -n "."; sleep 2
done
echo; echo "service did not become ready; see .hexpath/service.log"; exit 1
