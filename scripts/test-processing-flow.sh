#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
worker="${PROCESSING_PROJECT_DIR:-../fiapx-processing-service}"
[[ -f .env && -f "$worker/.env" && -f "$worker/Dockerfile" ]] || { echo 'Configure both local checkouts and PostgreSQL .env files.' >&2; exit 1; }
source .env
export UPLOAD_TEST_DB_URL="${DB_URL:-jdbc:postgresql://localhost:${POSTGRES_PORT:-5432}/fiapx_video}"
export UPLOAD_TEST_DB_USERNAME="${DB_USERNAME:-fiapx_video}"
export UPLOAD_TEST_DB_PASSWORD="${DB_PASSWORD:?Define video DB_PASSWORD}"
# Worker credentials are isolated from the producer configuration; never print either file.
unset DB_URL DB_USERNAME DB_PASSWORD POSTGRES_PORT
source "$worker/.env"
export PROCESSING_TEST_DB_URL="jdbc:postgresql://localhost:5432/${DB_NAME:-fiapx_processing}"
export PROCESSING_TEST_DB_USERNAME="${DB_USERNAME:-fiapx_processing}"
export PROCESSING_TEST_DB_PASSWORD="${DB_PASSWORD:?Define processing DB_PASSWORD}"
export FLOW_DB_CONTAINER
FLOW_DB_CONTAINER=$(docker compose --project-directory "$worker" ps -q postgres)
[[ "$FLOW_DB_CONTAINER" =~ ^[0-9a-f]{12,64}$ ]] || { echo 'Start the processing PostgreSQL before this test.' >&2; exit 1; }
mkdir -p target
flow=$(mktemp -d "$PWD/target/local-flow-XXXXXXXX")
export FLOW_DIRECTORY="$flow"
if command -v cygpath >/dev/null 2>&1; then FLOW_DIRECTORY=$(cygpath -m "$flow"); fi
docker build --target media-test -t fiapx-processing-media-test:local "$worker"
MSYS_NO_PATHCONV=1 docker run --rm --init --network none --cpus=2 --memory=1g --mount "type=bind,source=$FLOW_DIRECTORY,target=/flow" \
  --entrypoint /usr/bin/ffmpeg fiapx-processing-media-test:local -v error -nostdin -f lavfi \
  -i testsrc2=size=64x48:rate=5:duration=2 -threads 2 -c:v mpeg4 /flow/source.mp4
echo 'Local HTTP/PostgreSQL/FFmpeg flow; AWS and identity simulated. Artifacts remain under target/local-flow-*.'
bash ./mvnw -B -ntp -Ppostgres-integration '-Dit.test=VideoProcessingFlowIT#roundTrip' verify
