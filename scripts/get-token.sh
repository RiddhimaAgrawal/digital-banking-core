#!/usr/bin/env bash
# Usage: scripts/get-token.sh alice        -> prints an access token for user alice (password: "password")
set -euo pipefail
USER_NAME="${1:?usage: get-token.sh <alice|bob|tina|carl|admin1>}"
KC="${KEYCLOAK_URL:-http://localhost:8180}"
curl -sS -X POST "$KC/realms/bank/protocol/openid-connect/token" \
  -d grant_type=password -d client_id=bank-api \
  -d username="$USER_NAME" -d password=password \
  | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d["access_token"])'
