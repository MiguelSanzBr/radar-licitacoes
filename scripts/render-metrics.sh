#!/usr/bin/env bash
# Metricas do benchmark: junta os resultados de uma execucao em docs/benchmarks/latest.json e mantem o
# bloco de numeros do README (entre <!-- bench:start --> e <!-- bench:end -->) igual ao que o latest.json gera.
#   render-metrics.sh collect DIR    le DIR/run.json e DIR/gatling/*.stats.json e grava latest.json
#   render-metrics.sh readme         reescreve o bloco do README a partir do latest.json
#   render-metrics.sh self-test      roda collect, readme e check sobre scripts/test/fixtures/bench, sem tocar no repositorio
#   render-metrics.sh check          falha se o bloco do README diverge do que o latest.json geraria (make docs-check)
#   render-metrics.sh slo-md JSON    imprime o relatorio de uma execucao de make slo (docs/benchmarks/slo/AAAA-MM-DD.md)
# O segundo bloco do README (<!-- slo:start --> ... <!-- slo:end -->) vem do JSON mais recente de docs/benchmarks/slo (make slo).
# collect mantem as linhas do latest.json anterior que a execucao nova nao mediu (por nome), com a data e o commit de
# onde vieram (campo "from"): rodar so S1, S2 e S4 nao apaga o S3 e o S6 publicados antes.
# O commit gravado e o HEAD da execucao (run.json). Citacao no README e nos docs usa o commit da main: depois do squash do PR,
# BENCH_COMMIT=<hash na main> render-metrics.sh collect DIR grava esse hash no lugar do HEAD do ramo, que a main nao tem.
# Sem latest.json, o bloco e o aviso de que nenhum numero foi publicado.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
latest="${RENDER_LATEST:-$root/docs/benchmarks/latest.json}"
readme="${RENDER_README:-$root/README.md}"
slo_dir="${RENDER_SLO_DIR:-$root/docs/benchmarks/slo}"
start='<!-- bench:start -->'
end='<!-- bench:end -->'
slo_start='<!-- slo:start -->'
slo_end='<!-- slo:end -->'

collect() {
  local dir="${1:?uso: $0 collect DIRETORIO}"
  # DIR/gatling/sN.stats.json sai de scripts/gatling-stats.sh; o aquecimento tem o sufixo "(warmup)" no nome e fica de fora; a taxa e requisicoes por segundo da fase medida, nao da simulacao inteira (que inclui o aquecimento)
  local old="[]"
  [ -f "$latest" ] && old="$(jq -c '[.requests[] | . + {from: (.from // {date: $d, commit: $c})}]' --arg d "$(jq -r .date "$latest")" --arg c "$(jq -r .commit "$latest")" "$latest")"
  jq -n --argjson old "$old" --arg commit "${BENCH_COMMIT:-}" --slurpfile run "$dir/run.json" --slurpfile stats <(jq -c '.[]' "$dir"/gatling/*.stats.json) '
    $run[0] as $r
    | {date: $r.date, commit: (if $commit == "" then $r.commit else $commit end), host: $r.host, postgres: $r.postgres, rows: $r.rows,
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

# a tabela dos SLOs de uma execucao de make slo (o mesmo texto no README e no relatorio)
slo_table() {
  jq -r '
    def comma: tostring | sub("\\."; ",");
    "| SLO | Alvo | Medido | Atende |",
    "| --- | --- | ---: | --- |",
    (.slos[] | "| \(.name) | \(.target) | \(if .value == null then "não medido" else "\(.value | comma) \(.unit)" end) | \(if .met == true then "sim" elif .met == false then "não" elif .limit == null then "sem alvo" else "não medido" end) |")' "$1"
}

slo_head() {
  jq -r '
    def thousands: tostring | [match("\\d{1,3}(?=(\\d{3})*$)"; "g").string] | join(".");
    "Medido em \(.date) (commit `\(.commit[0:7])`), \(.host), \(.postgres), \(.rows.total | thousands) linhas de busca: \(.rows.real | thousands) reais do PNCP e \(.rows.synthetic | thousands) sintéticas, mais \(.ingestion.simulatedProcurements | thousands) editais do PNCP simulado coletados durante a medição. Rótulo de todo número abaixo: **sintético** (massa e tráfego de ingestão). Janela medida de \(.window.measuredSeconds / 60 | floor) min, depois de \(.window.warmupSeconds) s de aquecimento descartado; coleta incremental a cada \(.ingestion.pollIntervalSeconds) s (o padrão da aplicação é 5 min); carga mista S1, S2, S5 e S6 com \(.load.users.s1), \(.load.users.s2), \(.load.users.s5) e \(.load.users.s6) usuários."' "$1"
}

slo_md() {
  local json="${1:?uso: $0 slo-md JSON}"
  # shellcheck disable=SC2016 # as crases sao texto do README
  echo '# SLOs medidos com `make slo`'
  echo
  slo_head "$json"
  echo
  slo_table "$json"
  echo
  jq -r '"- Alertas entregues ao Mailpit na execução inteira: \(.ingestion.alertsSent). Execução completa (subida, carga, medição): \(.window.runSeconds) s."' "$json"
  jq -r '.slos[] | select(.note != "") | "- \(.name): \(.note)."' "$json"
  cat <<'NOTES'

Como ler:

- Alvos do plano (seção 7.4) e do ADR-0025; nenhum alvo foi alterado para caber na medição.
- O Gatling mede do cliente: inclui a fila do cliente e a rede local, que o servidor não vê.
- Os alertas de burn rate (`infra/prometheus/rules/slo.yml`) não são avaliados aqui: o Prometheus do `otel-lgtm` não carrega as regras (ADR-0025).
- O PNCP simulado (WireMock) devolve, a cada consulta de atualizações, 40 editais novos e 10 que mudam de valor (`infra/compose/pncp-fixture/slo/atualizacao.json`). O assinante verificado e a busca salva entram direto no banco, porque nenhum cliente do realm de desenvolvimento emite token de fornecedor.
- Uma execução na máquina do desenvolvedor, com outras cargas possíveis no host: dá a ordem de grandeza, não é uma série.
NOTES
}

slo_block() {
  local json
  json="$(find "$slo_dir" -maxdepth 1 -name '*.json' 2>/dev/null | sort | tail -1)"
  if [ -z "$json" ]; then
    # shellcheck disable=SC2016 # as crases sao Markdown, nao expansao
    echo 'Nenhum SLO medido ainda. Todo número desta seção vem de `make slo`, com data, hardware e rótulo sintético.'
    return
  fi
  slo_head "$json"
  echo
  slo_table "$json"
  echo
  echo "- Leitura completa e limites: \`docs/benchmarks/slo/$(basename "$json" .json).md\`."
}

replace_block() { # $1 = inicio, $2 = fim, $3 = texto; le o README do stdin
  # o texto vai pelo ambiente: awk -v interpretaria as barras invertidas
  TEXT="$3" awk -v start="$1" -v end="$2" '
    $0 == start { print; print ENVIRON["TEXT"]; skipping = 1; next }
    $0 == end { skipping = 0 }
    !skipping { print }'
}

rewrite() {
  replace_block "$start" "$end" "$(block)" <"$readme" | replace_block "$slo_start" "$slo_end" "$(slo_block)"
}

self_test() {
  local tmp
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN
  printf 'antes\n%s\nvelho\n%s\nmeio\n%s\nvelho\n%s\ndepois\n' "$start" "$end" "$slo_start" "$slo_end" >"$tmp/README.md"
  export RENDER_LATEST="$tmp/latest.json" RENDER_README="$tmp/README.md" RENDER_SLO_DIR="$root/scripts/test/fixtures/slo"
  "$0" collect "$root/scripts/test/fixtures/bench" >/dev/null
  "$0" readme
  grep -q '^| S1 text search | 50 | 9.000 | 3 | 20 | 150 | 450 | 50 |$' "$tmp/README.md" || { echo "self-test: linha do S1 ausente" >&2; return 1; }
  grep -q 'S2 não atende' "$tmp/README.md" || { echo "self-test: veredito do S2 errado" >&2; return 1; }
  grep -q 'S5 detail (warmup)' "$tmp/README.md" && { echo "self-test: o aquecimento entrou" >&2; return 1; }
  grep -q '^velho$' "$tmp/README.md" && { echo "self-test: o texto antigo ficou" >&2; return 1; }
  grep -q '^| Busca S1: p95 | p95 até 200 ms | 250 ms | não |$' "$tmp/README.md" || { echo "self-test: linha do SLO S1 errada" >&2; return 1; }
  grep -q '^| Frescor interno | p95 até 30 s | 12,5 s | sim |$' "$tmp/README.md" || { echo "self-test: linha do frescor errada" >&2; return 1; }
  grep -q '^| Disponibilidade com caos | 99,5% em 1 h | não medido | não medido |$' "$tmp/README.md" || { echo "self-test: o SLO sem medida deve dizer não medido" >&2; return 1; }
  grep -q '| 3 ms | sem alvo |$' "$tmp/README.md" || { echo "self-test: o informativo deve dizer sem alvo" >&2; return 1; }
  grep -q '^- Alertas entregues ao Mailpit na execução inteira: 7' <("$0" slo-md "$RENDER_SLO_DIR/2026-01-02.json") || { echo "self-test: o relatorio do SLO esta errado" >&2; return 1; }
  "$0" check >/dev/null
  # uma execucao nova so com o S1 mantem o S2 e o S5 da anterior, com a data e o commit de origem
  mkdir -p "$tmp/novo/gatling"
  jq '.date = "2026-02-03" | .commit = "fedcba9876"' "$root/scripts/test/fixtures/bench/run.json" >"$tmp/novo/run.json"
  cp "$root/scripts/test/fixtures/bench/gatling/s1.stats.json" "$tmp/novo/gatling/"
  "$0" collect "$tmp/novo" >/dev/null
  "$0" readme
  # shellcheck disable=SC2016 # as crases fazem parte do texto procurado
  grep -q '^| S2 filtered search (medido em 2026-01-02, commit `abcdef0`) |' "$tmp/README.md" || { echo "self-test: o S2 anterior nao foi mantido" >&2; return 1; }
  grep -q '^| S1 text search | 50 |' "$tmp/README.md" || { echo "self-test: o S1 novo ficou com origem" >&2; return 1; }
  "$0" check >/dev/null
  BENCH_COMMIT=0123456789abcdef "$0" collect "$tmp/novo" >/dev/null
  [ "$(jq -r .commit "$RENDER_LATEST")" = 0123456789abcdef ] || { echo "self-test: BENCH_COMMIT nao foi gravado" >&2; return 1; }
  echo x >>"$tmp/README.md"
  sed -i "s/^$start\$/$start\nmexido/" "$tmp/README.md"
  if "$0" check >/dev/null 2>&1; then echo "self-test: check nao viu a divergencia" >&2; return 1; fi
  echo "render-metrics: self-test ok"
}

case "${1:-}" in
  self-test) self_test ;;
  collect) collect "${2:-}" ;;
  slo-md) slo_md "${2:-}" ;;
  readme) rewrite >"$readme.tmp" && mv "$readme.tmp" "$readme" ;;
  check)
    if ! diff -u "$readme" <(rewrite); then
      echo "render-metrics: o bloco de numeros do README diverge de $latest; rode scripts/render-metrics.sh readme" >&2
      exit 1
    fi
    echo "render-metrics: README em dia com o latest.json" ;;
  *) echo "uso: $0 collect DIR | readme | check | slo-md JSON | self-test" >&2; exit 2 ;;
esac
