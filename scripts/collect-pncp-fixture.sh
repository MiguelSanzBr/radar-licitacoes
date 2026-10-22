#!/usr/bin/env bash
# Grava um dia de dados publicos do PNCP (modalidade 6) como fixture do compose: uma pagina da
# resposta por linha, em JSONL comprimido. Respeita a ADR-0017: no maximo 1 requisicao por
# segundo e o User-Agent do projeto. Uso: scripts/collect-pncp-fixture.sh AAAA-MM-DD [modalidade]
set -euo pipefail

day="${1:?uso: $0 AAAA-MM-DD [modalidade]}"
modality="${2:-6}"
compact="${day//-/}"
out="$(cd "$(dirname "$0")/.." && pwd)/infra/compose/seed/pncp-${day}-mod${modality}.jsonl.gz"
agent='radar-licitacoes/0.1 (+https://github.com/miguelsanzbr/radar-licitacoes; contato: miguelsanzb.p@gmail.com)'
tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT

page=1
while :; do
  url="https://pncp.gov.br/api/consulta/v1/contratacoes/publicacao?dataInicial=${compact}&dataFinal=${compact}&codigoModalidadeContratacao=${modality}&pagina=${page}&tamanhoPagina=50"
  # 429 e 5xx: espera 15 s e tenta de novo (no maximo 5 vezes por pagina)
  for attempt in 1 2 3 4 5; do
    body="$(curl -fsS -A "$agent" -H 'Accept: application/json' "$url")" && break
    [ "$attempt" -lt 5 ] || exit 1
    sleep 15
  done
  jq -c . <<<"$body" >>"$tmp"
  [ "$(jq '.paginasRestantes' <<<"$body")" -gt 0 ] || break
  page=$((page + 1))
  sleep 1
done
gzip -9 -n -c "$tmp" >"$out"
echo "$page paginas gravadas em $out ($(stat -c %s "$out") bytes)"
