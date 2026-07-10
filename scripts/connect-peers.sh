#!/usr/bin/env bash
# connect-peers.sh — connect two HiveMem instances bidirectionally
#
# Usage:
#   HIVEMEM_PEER_A_TOKEN=<admin-token-a> \
#   HIVEMEM_PEER_B_TOKEN=<admin-token-b> \
#   ./connect-peers.sh --a-url https://node-a.example.com --b-url https://node-b.example.com
#
# Tokens are read from the environment (HIVEMEM_PEER_A_TOKEN / HIVEMEM_PEER_B_TOKEN)
# or, if unset, prompted on stdin — never passed as argv, where they would leak
# into `ps` output and shell history.
#
# What it does:
#   1. Fetch instance UUID of A and B
#   2. Create a writer token on B for A to use (outbound from A)
#   3. Create a writer token on A for B to use (outbound from B)
#   4. Register B as peer on A
#   5. Register A as peer on B
#
# On partial failure the script prints exactly which resources were already
# created so they can be removed before retrying.

set -euo pipefail

# ── argument parsing ─────────────────────────────────────────────────────────
A_URL="" B_URL=""
while [[ $# -gt 0 ]]; do
  case $1 in
    --a-url) A_URL="$2"; shift 2 ;;
    --b-url) B_URL="$2"; shift 2 ;;
    --a-token|--b-token)
      echo "ERROR: $1 is no longer accepted on the command line." >&2
      echo "Set HIVEMEM_PEER_A_TOKEN / HIVEMEM_PEER_B_TOKEN instead." >&2
      exit 1 ;;
    *) echo "Unknown argument: $1"; exit 1 ;;
  esac
done

if [[ -z "$A_URL" || -z "$B_URL" ]]; then
  echo "Usage: HIVEMEM_PEER_A_TOKEN=... HIVEMEM_PEER_B_TOKEN=... $0 --a-url <url> --b-url <url>"
  exit 1
fi

A_TOKEN="${HIVEMEM_PEER_A_TOKEN:-}"
B_TOKEN="${HIVEMEM_PEER_B_TOKEN:-}"
if [[ -z "$A_TOKEN" ]]; then
  read -rs -p "Admin token for A ($A_URL): " A_TOKEN; echo
fi
if [[ -z "$B_TOKEN" ]]; then
  read -rs -p "Admin token for B ($B_URL): " B_TOKEN; echo
fi
if [[ -z "$A_TOKEN" || -z "$B_TOKEN" ]]; then
  echo "ERROR: both admin tokens are required." >&2
  exit 1
fi

A_URL="${A_URL%/}"
B_URL="${B_URL%/}"

# ── helpers ───────────────────────────────────────────────────────────────────
api() {
  local method="$1" url="$2" token="$3"
  shift 3
  curl -fsSL -X "$method" \
    -H "Authorization: Bearer $token" \
    -H "Content-Type: application/json" \
    "$@" "$url"
}

jq_required() {
  if ! command -v jq &>/dev/null; then
    echo "ERROR: jq is required but not installed." >&2
    exit 1
  fi
}

jq_required

# ── partial-failure cleanup guidance ──────────────────────────────────────────
# Track what has been created so an aborted run tells the operator exactly
# what to remove (via the admin UI or the /admin/tokens + /admin/peers APIs)
# before re-running — the script is not idempotent across partial runs.
CREATED=()
on_error() {
  echo "" >&2
  echo "ERROR: peering aborted before completion." >&2
  if [[ ${#CREATED[@]} -eq 0 ]]; then
    echo "No resources were created; safe to re-run as-is." >&2
  else
    echo "The following resources were already created and should be removed" >&2
    echo "(admin UI or admin API on the respective instance) before retrying:" >&2
    local item
    for item in "${CREATED[@]}"; do
      echo "  - $item" >&2
    done
  fi
}
trap on_error ERR

# ── 1. fetch instance UUIDs ───────────────────────────────────────────────────
echo "→ Fetching instance UUID from A ($A_URL)..."
A_UUID=$(api GET "$A_URL/admin/identity" "$A_TOKEN" | jq -r '.instance_uuid')
echo "  A UUID: $A_UUID"

echo "→ Fetching instance UUID from B ($B_URL)..."
B_UUID=$(api GET "$B_URL/admin/identity" "$B_TOKEN" | jq -r '.instance_uuid')
echo "  B UUID: $B_UUID"

if [[ "$A_UUID" == "$B_UUID" ]]; then
  echo "ERROR: A and B have the same instance UUID — are they the same instance?" >&2
  exit 1
fi

# ── 2. create token on B for A ────────────────────────────────────────────────
PEER_TOKEN_NAME_A="peer-${A_UUID:0:8}"
echo "→ Creating token '$PEER_TOKEN_NAME_A' on B for A..."
TOKEN_FOR_A=$(api POST "$B_URL/admin/tokens" "$B_TOKEN" \
  -d "{\"name\":\"$PEER_TOKEN_NAME_A\",\"role\":\"writer\"}" \
  | jq -r '.token')
CREATED+=("token '$PEER_TOKEN_NAME_A' on B ($B_URL/admin/tokens)")

# ── 3. create token on A for B ────────────────────────────────────────────────
PEER_TOKEN_NAME_B="peer-${B_UUID:0:8}"
echo "→ Creating token '$PEER_TOKEN_NAME_B' on A for B..."
TOKEN_FOR_B=$(api POST "$A_URL/admin/tokens" "$A_TOKEN" \
  -d "{\"name\":\"$PEER_TOKEN_NAME_B\",\"role\":\"writer\"}" \
  | jq -r '.token')
CREATED+=("token '$PEER_TOKEN_NAME_B' on A ($A_URL/admin/tokens)")

# ── 4. register B as peer on A ────────────────────────────────────────────────
echo "→ Registering B as peer on A..."
api POST "$A_URL/admin/peers" "$A_TOKEN" \
  -d "{\"peerUuid\":\"$B_UUID\",\"peerUrl\":\"$B_URL\",\"outboundToken\":\"$TOKEN_FOR_A\"}" \
  | jq .
CREATED+=("peer entry for B ($B_UUID) on A ($A_URL/admin/peers)")

# ── 5. register A as peer on B ────────────────────────────────────────────────
echo "→ Registering A as peer on B..."
api POST "$B_URL/admin/peers" "$B_TOKEN" \
  -d "{\"peerUuid\":\"$A_UUID\",\"peerUrl\":\"$A_URL\",\"outboundToken\":\"$TOKEN_FOR_B\"}" \
  | jq .

trap - ERR
echo ""
echo "✓ Done. A ($A_UUID) ↔ B ($B_UUID) are now connected."
echo "  Pull interval: 60s (configurable via hivemem.sync.pull-interval-ms)"
