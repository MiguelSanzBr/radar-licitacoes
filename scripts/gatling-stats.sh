#!/usr/bin/env bash
# O Gatling 3.15 nao grava stats.json: as estatisticas por requisicao ficam so na tabela do index.html do relatorio.
# Este script le essa tabela e imprime um JSON por requisicao, na ordem das colunas do Gatling (total, ok, ko,
# % ko, req/s, minimo, p50, p75, p95, p99, maximo, media, desvio), com os percentis padrao do gatling.conf.
# Uso: scripts/gatling-stats.sh relatorio/index.html
set -euo pipefail

html="${1:?uso: $0 index.html}"
awk '
  /<table id="container_statistics_body"/ { body = 1 }
  !body { next }
  /class="ellipsed-name">/ { name = $0; sub(/.*class="ellipsed-name">/, "", name); sub(/<.*/, "", name); n = 0 }
  /<td class="value [a-z]+ col-[0-9]+">/ { v = $0; sub(/.*">/, "", v); sub(/<.*/, "", v); cell[++n] = v }
  /^<\/tr>/ { printf "%s", name; for (i = 1; i <= n; i++) printf "\t%s", cell[i]; printf "\n" }
' "$html" | jq -R -s '
  def n: try tonumber catch 0;
  [split("\n")[] | select(length > 0) | split("\t")
   | {name: .[0], requests: (.[1] | n), ok: (.[2] | n), errors: (.[3] | n),
      rps: (.[5] | n), min: (.[6] | n), p50: (.[7] | n), p75: (.[8] | n),
      p95: (.[9] | n), p99: (.[10] | n), max: (.[11] | n), mean: (.[12] | n)}]'
