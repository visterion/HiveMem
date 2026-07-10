#!/bin/bash
set -euo pipefail

# Compose-based local deployment: builds the image from the repo Dockerfile
# (multi-stage — UI build + Maven build happen inside Docker, no host toolchain
# needed) and recreates the stack via docker compose. Dependencies (db,
# embeddings, seaweedfs) come up first thanks to their healthchecks.
#
# Production deploys pull a pinned release tag from GHCR instead — see
# documentation/operations.md.

MODE="${1:-java}"
if [ "$MODE" != "java" ]; then
    echo "Only the Java runtime is supported on this branch." >&2
    exit 1
fi

cd "$(dirname "$0")"

# Image name must match the one referenced by docker-compose.yml.
IMAGE="ghcr.io/visterion/hivemem:main"

# Secrets have no committed defaults; docker compose reads them from .env or
# the environment. Fail early with a clear message instead of mid-compose.
for var in HIVEMEM_DB_PASSWORD SEAWEEDFS_S3_ACCESS_KEY SEAWEEDFS_S3_SECRET_KEY; do
    if [ -z "${!var:-}" ] && ! grep -q "^${var}=" .env 2>/dev/null; then
        echo "$var must be set (environment or .env file)." >&2
        exit 1
    fi
done

if [ ! -f seaweedfs/s3.json ]; then
    echo "seaweedfs/s3.json is missing — create it from seaweedfs/s3.json.template" >&2
    echo "(credentials must match SEAWEEDFS_S3_ACCESS_KEY / SEAWEEDFS_S3_SECRET_KEY)." >&2
    exit 1
fi

echo "Building image $IMAGE ..."
docker build -t "$IMAGE" .

echo "Recreating services via docker compose..."
docker compose up -d

# Wait for health (unauthenticated endpoint; expects HTTP 200)
echo "Waiting for startup..."
for i in $(seq 1 60); do
    if curl -sf -o /dev/null http://localhost:8421/login; then
        echo "HiveMem ready on port 8421."
        exit 0
    fi
    sleep 2
done

echo "WARNING: Health check timed out. Check logs: docker logs hivemem" >&2
exit 1
