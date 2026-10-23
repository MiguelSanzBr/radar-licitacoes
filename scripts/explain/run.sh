#!/usr/bin/env bash
# Roda cada scripts/explain/s*.sql com EXPLAIN (ANALYZE, BUFFERS) no Postgres do compose em execucao.
# Uso: scripts/explain/run.sh before|after DIRETORIO_DE_SAIDA
#   after   com os indices da migration V5, como a aplicacao roda;
#   before  numa transacao que apaga os indices secundarios da view de busca e termina em ROLLBACK
#           (o DDL do Postgres e transacional, entao nada muda para quem vier depois; a chave
#           primaria fica, porque a aplicacao nunca roda sem ela).
#   RANK_CANDIDATES=2000  teto de candidatos do ranking (radar.search.rank-candidates); 2147483647 mede a busca sem o teto.
# O statement_timeout da sessao sobe para 10 min: uma varredura de 1,2 mi linhas passa dos 3 s da aplicacao.
set -euo pipefail

phase="${1:?uso: $0 before|after DIRETORIO}"
out="${2:?uso: $0 before|after DIRETORIO}"
case "$phase" in before | after) ;; *) echo "fase invalida: $phase" >&2; exit 2 ;; esac

here="$(cd "$(dirname "$0")" && pwd)"
compose="${COMPOSE:-docker compose -f $here/../../infra/compose/docker-compose.yml}"
mkdir -p "$out"

# indices secundarios da view de busca (V5); nao entra aqui o psv_pkey
drop='DROP INDEX search.psv_fts_gin, search.psv_organ_trgm, search.psv_filters_keyset, search.psv_open_closing, search.psv_published_keyset;'

# cada plano roda duas vezes e fica a segunda: a primeira so aquece o cache de paginas
plan() { # $1 = arquivo .sql
  {
    echo "BEGIN; SET LOCAL statement_timeout = '10min';"
    [ "$phase" = after ] || echo "$drop"
    cat "$1"
    echo "ROLLBACK;"
  } | $compose exec -T postgres psql -U radar -d radar -X -q -v ON_ERROR_STOP=1 -v cap="${RANK_CANDIDATES:-2000}"
}

for file in "$here"/s*.sql; do
  name="$(basename "$file" .sql)"
  plan "$file" >/dev/null
  plan "$file" >"$out/$name.$phase.txt"
  echo "explain $phase $name: $(grep -m1 'Execution Time' "$out/$name.$phase.txt")"
done
