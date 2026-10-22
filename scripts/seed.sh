#!/usr/bin/env bash
# Carrega no compose em execucao o dia gravado da fixture do PNCP (modalidade 6), pelo backfill do
# operador: token por client credentials (cliente radar-ops-cli do realm de desenvolvimento).
# Uso: scripts/seed.sh AAAA-MM-DD
set -euo pipefail

day="${1:?uso: $0 AAAA-MM-DD}"
issuer="${RADAR_OIDC_ISSUER:-http://localhost:8180/realms/radar}"
app="${RADAR_APP_URL:-http://localhost:8080}"

token="$(curl -fsS -d grant_type=client_credentials -d client_id=radar-ops-cli \
  -d "client_secret=${RADAR_OPS_CLIENT_SECRET:-radar-ops-dev-secret}" \
  "$issuer/protocol/openid-connect/token" | jq -r .access_token)"

curl -fsS -X POST -H "Authorization: Bearer $token" \
  "$app/ops/backfill?from=$day&to=$day&modality=6&force=true"
echo
echo "seed: backfill de $day iniciado; acompanhe com make logs SERVICE=app"
