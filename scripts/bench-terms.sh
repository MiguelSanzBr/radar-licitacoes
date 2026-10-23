#!/usr/bin/env bash
# Regenera os insumos dos cenarios Gatling a partir do dia real versionado em infra/compose/seed:
#   terms.csv        200 termos (de objetos e de nomes de orgao), espalhados por toda a faixa de frequencia
#   organ-typos.csv  100 nomes de orgao com duas letras trocadas (S4, pg_trgm)
#   filters.csv      UFs do seed (S2); a modalidade do seed e so a 6
# Deterministico: a mesma entrada gera os mesmos arquivos. Uso: scripts/bench-terms.sh
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
out="$root/perf/src/test/resources"
mkdir -p "$out"
seed() { zcat "$root"/infra/compose/seed/*.jsonl.gz; }

# palavras de 5+ letras que aparecem em 3+ linhas, da mais para a menos frequente; pega n espalhadas
spread() { # $1 = filtro jq que da o texto de uma linha, $2 = quantas
  seed | jq -rs --argjson n "$2" '
    [.[].data[] | ('"$1"') | ascii_downcase | [scan("\\p{L}{5,}") | select(test("^\\p{Ll}+$"))] | unique]
    | [.[][]] | group_by(.) | map({w: .[0], df: length}) | map(select(.df >= 3))
    | sort_by(-.df, .w) as $all
    | [range(0; $n) | $all[(. * ($all | length) / $n | floor)].w] | unique[]'
}

{
  echo term
  # a uniao pode passar de 200 por pouco: fica uma amostra de exatamente 200 espalhada pela lista ordenada
  { spread '.objetoCompra' 165; spread '.orgaoEntidade.razaoSocial, .unidadeOrgao.nomeUnidade' 60; } | sort -u \
    | awk '{ w[NR] = $0 } END { for (i = 0; i < 200; i++) print w[int(i * NR / 200) + 1] }'
} > "$out/terms.csv"

{
  echo typo
  seed | jq -rs '
    [.[].data[] | .orgaoEntidade.razaoSocial | ascii_downcase | gsub("^\\s+|\\s+$"; "") | select(length > 12 and test("^\\p{L}"))] | unique
    | [range(0; 100) as $i | .[$i * length / 100 | floor]]
    | map(. as $name | [scan("\\p{L}{6,}")][0] // empty
          | . as $w | ($w | length / 2 | floor) as $k
          | $name | sub($w; $w[0:$k] + $w[$k + 1:$k + 2] + $w[$k:$k + 1] + $w[$k + 2:]))
    | .[]'
} > "$out/organ-typos.csv"

{
  echo uf,modality
  seed | jq -rs '[.[].data[] | .unidadeOrgao.ufSigla] | unique[] | . + ",6"'
} > "$out/filters.csv"

wc -l "$out"/terms.csv "$out"/organ-typos.csv "$out"/filters.csv
