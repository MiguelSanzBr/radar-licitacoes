#!/usr/bin/env bash
# Smoke do app no ar: readiness, /actuator/info (exposto pela ADR-0022) e uma leitura publica da API (a busca de 1 edital,
# que passa pelo banco de leitura). Cada chamada tem prazo de 5 s.
# RADAR_APP_URL troca o endereco; CURL troca o cliente (os testes usam um simulado).
set -euo pipefail

app="${RADAR_APP_URL:-http://localhost:8080}"
CURL=${CURL:-curl}

for path in /actuator/health/readiness /actuator/info '/api/v1/procurements?limit=1'; do
  if ! "$CURL" -fsS --max-time 5 -o /dev/null "$app$path"; then
    echo "smoke: $path falhou em $app" >&2
    exit 1
  fi
done
echo "smoke: ok em $app"
