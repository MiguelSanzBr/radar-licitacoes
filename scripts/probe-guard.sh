#!/usr/bin/env bash
# Recusa a sonda do PNCP sem PROBE_CONFIRM=1: ela ja tomou 429 a 0,5 req/s.
set -euo pipefail

if [ "${PROBE_CONFIRM:-}" != "1" ]; then
  {
    echo "probe: recusado. O PNCP respondeu 429 a 0,5 req/s (ADR-0005, ADR-0017)."
    echo "A sonda sobe a 0,2 req/s, o ritmo do app, mas roda por horas contra o servico real."
    echo "Confirme com: make probe PROBE_CONFIRM=1"
  } >&2
  exit 1
fi
