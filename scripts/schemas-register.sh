#!/usr/bin/env bash
# Prepara o Apicurio para os eventos em Avro (ADR-0024): um grupo por topico, cada um com a regra de
# compatibilidade BACKWARD. Os artefatos (um por tipo de evento) o produtor registra no primeiro uso,
# dentro do grupo do seu topico, e o registry recusa a versao que quebra a regra. Idempotente.
# Uso: scripts/schemas-register.sh [URL_DO_REGISTRY]  (RADAR_REGISTRY_URL e RADAR_TOPICS trocam os padroes)
set -euo pipefail

registry="${1:-${RADAR_REGISTRY_URL:-http://localhost:8081/apis/registry/v3}}"
topics="${RADAR_TOPICS:-radar.procurement.v1 radar.alert.v1}"
rule='{"ruleType":"COMPATIBILITY","config":"BACKWARD"}'

# O codigo HTTP de uma chamada JSON; o corpo nao interessa.
status() { curl -sS -o /dev/null -w '%{http_code}' --max-time 10 -H 'Content-Type: application/json' "$@"; }

for topic in $topics; do
  code=$(status -X POST "$registry/groups" -d "{\"groupId\":\"$topic\"}")
  if [[ $code != 200 && $code != 409 ]]; then
    echo "schemas-register: grupo $topic: HTTP $code" >&2
    exit 1
  fi
  code=$(status -X POST "$registry/groups/$topic/rules" -d "$rule")
  if [[ $code == 409 ]]; then
    code=$(status -X PUT "$registry/groups/$topic/rules/COMPATIBILITY" -d "$rule")
  fi
  if [[ $code != 200 && $code != 204 ]]; then
    echo "schemas-register: regra do grupo $topic: HTTP $code" >&2
    exit 1
  fi
  echo "schemas-register: $topic com BACKWARD"
done
