#!/usr/bin/env bash
set -euo pipefail
if [[ ! -f .env ]]; then echo 'Configure .env with the existing local PostgreSQL connection.' >&2; exit 1; fi
# Same local dotenv format as make run. Never echo credentials or enable shell tracing.
source .env
export UPLOAD_TEST_DB_URL="${DB_URL:-jdbc:postgresql://localhost:${POSTGRES_PORT:-5432}/fiapx_video}"
export UPLOAD_TEST_DB_USERNAME="${DB_USERNAME:-fiapx_video}"
export UPLOAD_TEST_DB_PASSWORD="${DB_PASSWORD:?Define DB_PASSWORD in .env}"
# The Java harness creates unique schemas and removes only those schemas after the run.
echo 'Testing against the existing local PostgreSQL using isolated schemas; no AWS writes.'
bash ./mvnw -B -ntp -Ppostgres-integration verify
