#!/usr/bin/env bash
# Custo de relevancia do teto de candidatos do ranking (ADR-0011, "Perfil e otimizacao"): para cada termo de
# perf/src/test/resources/terms.csv (os 200 termos do S1, tirados de objetos e orgaos reais), compara o top 20
# exato (rank de todas as linhas que casam) com o top 20 sobre os N candidatos mais recentes.
# Uso: scripts/relevance.sh > docs/benchmarks/search/DATA/relevance.md   (precisa do compose no ar, com a massa do bench)
#   RELEVANCE_CAPS="500 2000 5000"   valores de N medidos
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
compose="${COMPOSE:-docker compose -f $root/infra/compose/docker-compose.yml}"
caps="${RELEVANCE_CAPS:-500 2000 5000}"

{
  echo "SET statement_timeout = 0;"
  echo "CREATE TEMP TABLE terms (term text);"
  echo "\\copy terms FROM STDIN CSV HEADER"
  cat "$root/perf/src/test/resources/terms.csv"
  echo '\.'
  cat <<SQL
CREATE TEMP TABLE result (term text, matches bigint, cap int, overlap int, exact_sum float8, bounded_sum float8);
DO \$do\$
DECLARE
  t record; q tsquery; n int; matches bigint; exact_ids text[]; exact_sum float8; bounded_ids text[]; bounded_sum float8;
BEGIN
  FOR t IN SELECT term FROM terms LOOP
    q := websearch_to_tsquery('search.portuguese_unaccent', t.term);
    CONTINUE WHEN q::text = '';
    SELECT count(*) INTO matches FROM search.procurement_search_view WHERE search_vector @@ q;
    CONTINUE WHEN matches = 0;
    SELECT array_agg(id), sum(rank) INTO exact_ids, exact_sum FROM (
      SELECT numero_controle_pncp AS id, ts_rank_cd(search_vector, q)::float8 AS rank
      FROM search.procurement_search_view WHERE search_vector @@ q ORDER BY 2 DESC, 1 DESC LIMIT 20) e;
    FOREACH n IN ARRAY ARRAY[${caps// /,}] LOOP
      SELECT array_agg(id), sum(rank) INTO bounded_ids, bounded_sum FROM (
        SELECT id, rank FROM (
          SELECT numero_controle_pncp AS id, ts_rank_cd(search_vector, q)::float8 AS rank
          FROM search.procurement_search_view WHERE search_vector @@ q
          ORDER BY published_at DESC, numero_controle_pncp DESC LIMIT n) c
        ORDER BY rank DESC, id DESC LIMIT 20) b;
      INSERT INTO result VALUES (t.term, matches, n,
        (SELECT count(*) FROM unnest(bounded_ids) x WHERE x = ANY (exact_ids)), exact_sum, bounded_sum);
    END LOOP;
  END LOOP;
END \$do\$;
\pset footer off
SELECT cap AS "N",
       count(*) AS "termos",
       count(*) FILTER (WHERE matches > cap) AS "termos com teto atuando",
       round(avg(overlap) FILTER (WHERE matches > cap), 1) AS "acertos medios no top 20 (so os cortados)",
       round(avg(bounded_sum / exact_sum) FILTER (WHERE matches > cap)::numeric, 3) AS "razao media da soma dos ranks (so os cortados)",
       round(100.0 * count(*) FILTER (WHERE matches <= cap) / count(*), 1) AS "% de termos sem corte"
FROM result GROUP BY cap ORDER BY cap;
SELECT 'termos com mais de 100 mil linhas' AS "faixa", count(DISTINCT term) AS "termos" FROM result WHERE matches > 100000
UNION ALL SELECT 'termos com mais de 2 mil linhas', count(DISTINCT term) FROM result WHERE matches > 2000;
SQL
} | $compose exec -T postgres psql -U radar -d radar -X -q -v ON_ERROR_STOP=1
