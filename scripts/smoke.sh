#!/usr/bin/env bash
# Smoke do app no ar: readiness e uma leitura publica (/actuator/info, exposto pela ADR-0022). Cada chamada tem prazo de 5 s.
# As rotas de /api/v1 do contrato ainda nao respondem no main (404), por isso nao servem de smoke.
# RADAR_APP_URL troca o endereco; CURL troca o cliente (os testes usam um simulado).
set -euo pipefail

app="${RADAR_APP_URL:-http://localhost:8080}"
CURL=${CURL:-curl}

for path in /actuator/health/readiness /actuator/info; do
  if ! "$CURL" -fsS --max-time 5 -o /dev/null "$app$path"; then
    echo "smoke: $path falhou em $app" >&2
    exit 1
  fi
done
echo "smoke: ok em $app"
