#!/usr/bin/env bash
# Tabela antes/depois dos indices a partir dos planos gravados por run.sh. Uso: scripts/explain/summarize.sh DIRETORIO
set -euo pipefail

dir="${1:?uso: $0 DIRETORIO}"

time_of() { sed -n 's/^ *Execution Time: \([0-9.]*\) ms.*/\1/p' "$1" | head -1; }
# acertos e leituras de paginas do no raiz (o total do plano)
buffers_of() { sed -n 's/^ *Buffers: shared \(.*\)$/\1/p' "$1" | head -1 | sed 's/hit=/acertos=/; s/read=/leituras=/'; }
# primeiro no que le a tabela, com o indice quando ha
node_of() {
  sed -nE 's/^ *(->)? *((Parallel )?(Seq Scan|Index Scan|Index Only Scan|Bitmap Heap Scan)( Backward)?( using [a-z_]+)?( on [a-z_.]+)?).*/\2/p' "$1" |
    head -1 | sed -E 's/ on [a-z_.]+//'
}

echo "| Consulta | Antes (ms) | Depois (ms) | Buffers antes | Buffers depois | Nó antes | Nó depois |"
echo "| --- | ---: | ---: | --- | --- | --- | --- |"
for before in "$dir"/s*.before.txt; do
  name="$(basename "$before" .before.txt)"
  after="$dir/$name.after.txt"
  echo "| \`$name\` | $(time_of "$before") | $(time_of "$after") | $(buffers_of "$before") | $(buffers_of "$after") | $(node_of "$before") | $(node_of "$after") |"
done
