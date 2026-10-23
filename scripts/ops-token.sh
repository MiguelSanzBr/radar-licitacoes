#!/usr/bin/env bash
# Imprime um token de operador (client credentials do cliente radar-ops-cli do realm de desenvolvimento).
# Uso: TOKEN=$(scripts/ops-token.sh). Variaveis: RADAR_OIDC_ISSUER, RADAR_OPS_CLIENT_SECRET.
set -euo pipefail

issuer="${RADAR_OIDC_ISSUER:-http://localhost:8180/realms/radar}"
curl -fsS -d grant_type=client_credentials -d client_id=radar-ops-cli \
  -d "client_secret=${RADAR_OPS_CLIENT_SECRET:-radar-ops-dev-secret}" \
  "$issuer/protocol/openid-connect/token" | jq -r .access_token
