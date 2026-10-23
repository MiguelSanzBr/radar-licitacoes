#!/usr/bin/env bash
# Metricas do benchmark: junta os resultados de uma execucao em docs/benchmarks/latest.json e mantem o
# bloco de numeros do README (entre <!-- bench:start --> e <!-- bench:end -->) igual ao que o latest.json gera.
#   render-metrics.sh collect DIR    le DIR/run.json e DIR/gatling/*.stats.json e grava latest.json
#   render-metrics.sh readme         reescreve o bloco do README a partir do latest.json
#   render-metrics.sh self-test      roda collect, readme e check sobre scripts/test/fixtures/bench, sem tocar no repositorio
#   render-metrics.sh check          falha se o bloco do README diverge do que o latest.json geraria (make docs-check)
# collect mantem as linhas do latest.json anterior que a execucao nova nao mediu (por nome), com a data e o commit de
# onde vieram (campo "from"): rodar so S1, S2 e S4 nao apaga o S3 e o S6 publicados antes.
# Sem latest.json, o bloco e o aviso de que nenhum numero foi publicado.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
latest="${RENDER_LATEST:-$root/docs/benchmarks/latest.json}"
readme="${RENDER_README:-$root/README.md}"
start='<!-- bench:start -->'
end='<!-- bench:end -->'

collect() {
  local dir="${1:?uso: $0 collect DIRETORIO}"
  # DIR/gatling/sN.stats.json sai de scripts/gatling-stats.sh; o aquecimento tem o sufixo "(warmup)" no nome e fica de fora; a taxa e requisicoes por segundo da fase medida, nao da simulacao inteira (que inclui o aquecimento)
  local old="[]"
  [ -f "$latest" ] && old="$(jq -c '[.requests[] | . + {from: (.from // {date: $d, commit: $c})}]' --arg d "$(jq -r .date "$latest")" --arg c "$(jq -r .commit "$latest")" "$latest")"
  jq -n --argjson old "$old" --slurpfile run "$dir/run.json" --slurpfile stats <(jq -c '.[]' "$dir"/gatling/*.stats.json) '
    $run[0] as $r
    | {date: $r.date, commit: $r.commit, host: $r.host, postgres: $r.postgres, rows: $r.rows,
       thinkMs: $r.thinkMs, warmupSeconds: $r.warmupSeconds, measuredSeconds: $r.measuredSeconds,
       directory: $r.directory,
       requests: ([ $stats[] | select(.name | endswith(" (warmup)") | not)
                   | (.name | split(" ")[0]) as $s | ($r.load[$s | ascii_downcase]) as $l
                   | {scenario: $s, name: .name, users: $l.usedUsers, plannedUsers: $l.plannedUsers,
                      requests: .requests, errors: .errors, p50: .p50, p95: .p95, p99: .p99,
                      rps: ((.requests / $r.measuredSeconds * 10 | round) / 10)} ] as $new
                 | $new + [ $old[] | select(.name as $n | $new | map(.name) | index($n) | not) ] | sort_by(.scenario)),
       detailCache: $r.detailCache}' >"$latest"
  echo "render-metrics: $latest"
}

# o bloco como texto, vindo so do latest.json
block() {
  if [ ! -f "$latest" ]; then
    # shellcheck disable=SC2016 # as crases sao Markdown, nao expansao
    echo 'Nenhum número publicado ainda. Todo número desta seção vem de `make bench` ou `make slo`, com data, hardware e rótulo real/sintético.'
    return
  fi
  jq -r '
    def thousands: tostring | [match("\\d{1,3}(?=(\\d{3})*$)"; "g").string] | join(".");
    def comma: tostring | sub("\\."; ",");
    def verdict($r; $p95; $p99): if $r == null then "não medido" elif $r.p95 <= $p95 and $r.p99 <= $p99 then "atende" else "não atende" end;
    def find($n): [.requests[] | select(.name == $n)][0];
    "Medido em \(.date) (commit `\(.commit[0:7])`), \(.host), \(.postgres), \(.rows.total | thousands) linhas de busca: \(.rows.real | thousands) reais do PNCP (dia versionado em `infra/compose/seed`) e \(.rows.synthetic | thousands) sintéticas. Rótulo de todo número abaixo: **inclui dados sintéticos (\(.rows.synthetic | thousands) linhas)**. Uma execução por cenário, \(.warmupSeconds / 60 | comma) min de aquecimento descartado e \(.measuredSeconds / 60 | comma) min medidos; cada usuário espera \(.thinkMs / 1000 | comma) s entre requisições.",
    "",
    "| Cenário | Usuários | Requisições | Erros | p50 (ms) | p95 (ms) | p99 (ms) | req/s |",
    "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
    (.requests[] | "| \(.name)\(if .from then " (medido em \(.from.date), commit `\(.from.commit[0:7])`)" else "" end) | \(.users) | \(.requests | thousands) | \(.errors) | \(.p50) | \(.p95) | \(.p99) | \(.rps | comma) |"),
    "",
    (. as $m | ($m.detailCache) as $c
      | (if $c.hits + $c.misses == 0 then "- Cache do detalhe (S5): não medido."
         else "- Cache do detalhe (S5): \($c.hits | thousands) acertos e \($c.misses | thousands) faltas, taxa de acerto de \(($c.hitRatio * 1000 | round / 10) | comma)%." end),
        (find("S1 text search") as $s1 | find("S2 filtered search") as $s2 | find("S5 detail") as $s5
          | "- SLO de busca (S1 e S2, p95 até 200 ms e p99 até 500 ms): S1 \(verdict($s1; 200; 500)), S2 \(verdict($s2; 200; 500)).",
            "- SLO de detalhe (S5, p95 até 50 ms): \(verdict($s5; 50; 1e9)).")),
    "- Planos, hardware e leitura completa: `\(.directory)/README.md`."' "$latest"
}

rewrite() {
  local text
  text="$(block)"
  # o texto vai pelo ambiente: awk -v interpretaria as barras invertidas
  TEXT="$text" awk -v start="$start" -v end="$end" '
    $0 == start { print; print ENVIRON["TEXT"]; skipping = 1; next }
    $0 == end { skipping = 0 }
    !skipping { print }' "$readme"
}

self_test() {
  local tmp
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN
  printf 'antes\n%s\nvelho\n%s\ndepois\n' "$start" "$end" >"$tmp/README.md"
  export RENDER_LATEST="$tmp/latest.json" RENDER_README="$tmp/README.md"
  "$0" collect "$root/scripts/test/fixtures/bench" >/dev/null
  "$0" readme
  grep -q '^| S1 text search | 50 | 9.000 | 3 | 20 | 150 | 450 | 50 |$' "$tmp/README.md" || { echo "self-test: linha do S1 ausente" >&2; return 1; }
  grep -q 'S2 não atende' "$tmp/README.md" || { echo "self-test: veredito do S2 errado" >&2; return 1; }
  grep -q 'S5 detail (warmup)' "$tmp/README.md" && { echo "self-test: o aquecimento entrou" >&2; return 1; }
  grep -q '^velho$' "$tmp/README.md" && { echo "self-test: o texto antigo ficou" >&2; return 1; }
  "$0" check >/dev/null
  # uma execucao nova so com o S1 mantem o S2 e o S5 da anterior, com a data e o commit de origem
  mkdir -p "$tmp/novo/gatling"
  jq '.date = "2026-02-03" | .commit = "fedcba9876"' "$root/scripts/test/fixtures/bench/run.json" >"$tmp/novo/run.json"
  cp "$root/scripts/test/fixtures/bench/gatling/s1.stats.json" "$tmp/novo/gatling/"
  "$0" collect "$tmp/novo" >/dev/null
  "$0" readme
  grep -q '^| S2 filtered search (medido em 2026-01-02, commit `abcdef0`) |' "$tmp/README.md" || { echo "self-test: o S2 anterior nao foi mantido" >&2; return 1; }
  grep -q '^| S1 text search | 50 |' "$tmp/README.md" || { echo "self-test: o S1 novo ficou com origem" >&2; return 1; }
  "$0" check >/dev/null
  echo x >>"$tmp/README.md"
  sed -i "s/^$start\$/$start\nmexido/" "$tmp/README.md"
  if "$0" check >/dev/null 2>&1; then echo "self-test: check nao viu a divergencia" >&2; return 1; fi
  echo "render-metrics: self-test ok"
}

case "${1:-}" in
  self-test) self_test ;;
  collect) collect "${2:-}" ;;
  readme) rewrite >"$readme.tmp" && mv "$readme.tmp" "$readme" ;;
  check)
    if ! diff -u "$readme" <(rewrite); then
      echo "render-metrics: o bloco de numeros do README diverge de $latest; rode scripts/render-metrics.sh readme" >&2
      exit 1
    fi
    echo "render-metrics: README em dia com o latest.json" ;;
  *) echo "uso: $0 collect DIR | readme | check | self-test" >&2; exit 2 ;;
esac
