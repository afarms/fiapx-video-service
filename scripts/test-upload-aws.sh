#!/usr/bin/env bash
set -euo pipefail
[[ "${UPLOAD_AWS_TEST_APPROVED:-}" == true ]] || { echo 'Set UPLOAD_AWS_TEST_APPROVED=true only after reviewing docs/upload.md.' >&2; exit 1; }
source .env
export UPLOAD_TEST_DB_URL="${DB_URL:-jdbc:postgresql://localhost:${POSTGRES_PORT:-5432}/fiapx_video}"
export UPLOAD_TEST_DB_USERNAME="${DB_USERNAME:-fiapx_video}"
export UPLOAD_TEST_DB_PASSWORD="${DB_PASSWORD:?Define DB_PASSWORD in .env}"
export UPLOAD_AWS_TEST_APPROVED=true
echo 'AWS integration: up to three small test objects and two SQS sends; no queue consumption.'
bash ./mvnw -B -ntp -Paws-integration verify
