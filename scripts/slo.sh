#!/usr/bin/env bash
# make slo: mede os SLOs da secao 7.4 do plano com trafego misto sintetico (ADR-0025, docs/benchmarks/slo/).
#   1. sobe o compose (docker-compose.slo.yml sobre o docker-compose.yml), carrega o dia real e completa com massa sintetica rotulada;
#   2. cria um assinante verificado com uma busca salva que casa com parte do trafego (os alertas saem pelo Mailpit);
#   3. roda o Gatling SloMix (S1, S2, S5 e S6 em taxa baixa e constante) pelo tempo pedido; no mesmo tempo o PNCP simulado
#      (WireMock) devolve, a cada consulta de atualizacoes, 40 editais novos e 10 que mudam, e a coleta incremental os le a cada 30 s;
#   4. le as metricas no Prometheus do otel-lgtm (pelo proxy do Grafana) e o banco, e grava docs/benchmarks/slo/AAAA-MM-DD.{json,md}.
# Pre-requisitos: imagem radar-licitacoes:local (make image), JDK 25 e MAVEN_ARGS (mesmo repositorio do mvnw), Docker, jq,
# uns 4,5 GB de RAM livres e o volume do compose vazio (CLEAN=1 make down). Variaveis (todas opcionais):
#   SLO_DURATION=15m          tempo medido (15m, 1h, 900s ou so segundos); o plano pede 1h
#   SLO_ROWS=200000           linhas da view de busca, reais mais sinteticas
#   SLO_WARMUP_S=60           aquecimento do Gatling, descartado
#   SLO_THINK_MS=1000         pausa de cada usuario entre requisicoes
#   SLO_USERS_S1/S2/S5/S6     usuarios de cada cenario (padrao 5 5 10 2)
#   SLO_INTERVAL=30s          intervalo da coleta incremental (o padrao da aplicacao e 5m)
#   SLO_MIN_FREE_MB=4608      RAM livre exigida para comecar
#   SLO_DATE=AAAA-MM-DD       nome dos arquivos de saida (padrao: hoje)
#   SLO_KEEP=1                nao derruba a pilha ao fim
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

date="${SLO_DATE:-$(date +%F)}"
out="docs/benchmarks/slo"
rows_target="${SLO_ROWS:-200000}"
warmup_s="${SLO_WARMUP_S:-60}"
think_ms="${SLO_THINK_MS:-1000}"
interval="${SLO_INTERVAL:-30s}"
min_free_mb="${SLO_MIN_FREE_MB:-4608}"
abort_free_mb=1024
app="http://localhost:8080"
fixture="http://localhost:8089"
prom="http://localhost:3000/api/datasources/proxy/uid/prometheus/api/v1/query"
export RADAR_TAG="${RADAR_TAG:-local}"
export SLO_INTERVAL="$interval"
COMPOSE="docker compose -f infra/compose/docker-compose.yml -f infra/compose/docker-compose.slo.yml"

log() { echo "slo: $*" >&2; }
free_mb() { awk '/MemAvailable/ { print int($2 / 1024) }' /proc/meminfo; }
sql() { $COMPOSE exec -T postgres psql -U radar -d radar -X -qAt -v ON_ERROR_STOP=1 "$@"; }

seconds() { # 15m, 1h, 90s ou 90 -> segundos
  case "$1" in
    *h) echo $((${1%h} * 3600)) ;;
    *m) echo $((${1%m} * 60)) ;;
    *s) echo "${1%s}" ;;
    *[!0-9]* | "") log "SLO_DURATION invalida: $1"; exit 2 ;;
    *) echo "$1" ;;
  esac
}
measured_s="$(seconds "${SLO_DURATION:-15m}")"
interval_s="$(seconds "$interval")"

watchdog_pid=""
cleanup() {
  [ -z "$watchdog_pid" ] || kill "$watchdog_pid" 2>/dev/null || true
  if [ "${SLO_KEEP:-0}" != 1 ]; then
    log "derrubando a pilha (CLEAN=1 make down)"
    CLEAN=1 make down >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT
trap 'exit 143' TERM INT

# --- pre-requisitos -------------------------------------------------------------------------------
command -v jq >/dev/null || { log "falta o jq"; exit 1; }
docker image inspect "radar-licitacoes:$RADAR_TAG" >/dev/null 2>&1 || { log "falta a imagem radar-licitacoes:$RADAR_TAG (rode make image)"; exit 1; }
if pgrep -f 'surefire|failsafe' >/dev/null; then log "ha outra execucao de testes do Maven; espere ela acabar"; exit 1; fi
if [ -n "$(docker ps -q --filter name=radar-)" ]; then log "ha containers radar-* no ar; rode CLEAN=1 make down e espere"; exit 1; fi
[ "$(free_mb)" -ge "$min_free_mb" ] || { log "so $(free_mb) MB de RAM livres, o SLO pede $min_free_mb MB"; exit 1; }
if docker volume inspect radar_pgdata >/dev/null 2>&1; then
  log "o volume radar_pgdata ja existe: rode CLEAN=1 make down (a medicao parte de um banco vazio)"
  exit 1
fi

# vigia: com menos de 1 GB livres a execucao para, antes que o host comece a trocar para o disco
(
  while sleep 5; do
    if [ "$(free_mb)" -lt "$abort_free_mb" ]; then
      echo "slo: so $(free_mb) MB de RAM livres, abortando" >&2
      pkill -f io.gatling.app.Gatling || true
      kill -TERM $$
      exit
    fi
  done
) &
watchdog_pid=$!

mkdir -p "$out" perf/target/slo
run_started="$(date +%s)"

# --- 1. pilha e massa --------------------------------------------------------------------------------
log "subindo a pilha"
scripts/compose-init.sh
$COMPOSE up -d --wait
log "carregando o dia real (seed)"
scripts/seed.sh "${SEED_DAY:-2026-10-21}" >/dev/null
last=-1
for _ in $(seq 1 90); do
  sleep 4
  now="$(sql -c 'SELECT count(*) FROM search.procurement_search_view')"
  [ "$now" -gt 0 ] && [ "$now" = "$last" ] && break
  last="$now"
done
real="$(sql -c "SELECT count(*) FROM search.procurement_search_view WHERE source = 'pncp'")"
[ "$real" -gt 0 ] || { log "o seed nao carregou nenhuma linha real"; exit 1; }
log "$real linhas reais; gerando $((rows_target - real)) sinteticas"
make synth SYNTH_COUNT=$((rows_target - real)) >perf/target/slo/synth.log
# a carga sintetica nao passa pelo projetor: estatisticas e orgaos saem da view, como no make bench
sql -c "TRUNCATE search.procurement_stats_view;
  INSERT INTO search.procurement_stats_view (uf, modality_id, month, value_band, count, estimated_sum)
  SELECT coalesce(uf, 'XX'), modality_id, date_trunc('month', published_at AT TIME ZONE 'America/Sao_Paulo')::date,
         value_band, count(*), coalesce(sum(estimated_value), 0)
  FROM search.procurement_search_view GROUP BY 1, 2, 3, 4;
  TRUNCATE search.organ;
  INSERT INTO search.organ (organ_name, procurement_count)
  SELECT organ_name, count(*) FROM search.procurement_search_view GROUP BY 1"
for table in procurement_search_view procurement_detail_view procurement_stats_view organ; do
  sql -c "VACUUM (ANALYZE) search.$table"
done
synthetic="$(sql -c "SELECT count(*) FROM search.procurement_search_view WHERE source = 'synthetic'")"

# --- 2. assinante verificado e busca salva ------------------------------------------------------------
# O token de fornecedor nao sai de nenhum cliente do realm (nenhum tem direct grant), entao a linha entra direto no banco:
# o assinante ja verificado e a busca INSTANT pelo termo que 1 de cada 5 editais novos do PNCP simulado traz.
sql -c "WITH s AS (INSERT INTO alerts.subscriber (id, email, consent_at, verified_at, created_at)
            VALUES (gen_random_uuid(), 'slo@example.com', now(), now(), now()) RETURNING id)
        INSERT INTO alerts.saved_search (id, subscriber_id, name, query_text, frequency, created_at)
        SELECT gen_random_uuid(), id, 'make slo', 'slocarga', 'INSTANT', now() FROM s"

# --- 3. trafego ---------------------------------------------------------------------------------------
sql -c "COPY (SELECT replace(numero_controle_pncp, '/', '-') FROM search.procurement_detail_view ORDER BY random() LIMIT 20000) TO STDOUT" >perf/target/slo/ids.csv
log "PNCP simulado: atualizacoes novas a cada consulta (coleta incremental a cada $interval)"
curl -fsS -X POST -H 'Content-Type: application/json' --data @infra/compose/pncp-fixture/slo/atualizacao.json "$fixture/__admin/mappings/import" >/dev/null
for _ in $(seq 1 $((interval_s * 3 + 30))); do
  [ "$(sql -c "SELECT count(*) FROM ingest.procurement WHERE numero_controle_pncp LIKE '11222333000181-%'")" -gt 0 ] && break
  sleep 1
done
[ "$(sql -c "SELECT count(*) FROM ingest.procurement WHERE numero_controle_pncp LIKE '11222333000181-%'")" -gt 0 ] ||
  { log "a coleta incremental nao trouxe nenhum edital do PNCP simulado"; exit 1; }

log "Gatling SloMix: ${warmup_s}s de aquecimento e ${measured_s}s medidos"
./mvnw -q -Pperf -pl perf test-compile gatling:test \
  "-Dgatling.simulationClass=br.dev.miguelsanzbr.radar.perf.SloMix" \
  "-Dbench.users.s1=${SLO_USERS_S1:-5}" "-Dbench.users.s2=${SLO_USERS_S2:-5}" \
  "-Dbench.users.s5=${SLO_USERS_S5:-10}" "-Dbench.users.s6=${SLO_USERS_S6:-2}" \
  "-Dbench.think-ms=$think_ms" "-Dbench.warmup-seconds=$warmup_s" "-Dbench.measured-seconds=$measured_s" \
  "-Dbench.base-url=$app" "-Dbench.ids-file=$root/perf/target/slo/ids.csv" >perf/target/slo/gatling.log 2>&1 ||
  { tail -30 perf/target/slo/gatling.log >&2; log "Gatling falhou"; exit 1; }
# shellcheck disable=SC2012 # os nomes sao do Gatling: letras, digitos e hifen
results="$(ls -dt perf/target/gatling/slomix-* | head -1)"
scripts/gatling-stats.sh "$results/index.html" >perf/target/slo/gatling.stats.json

# janela medida: o nome da pasta do Gatling traz o inicio da simulacao em UTC (aaaaMMddHHmmssSSS); a fase medida comeca depois do aquecimento
stamp="${results##*slomix-}"
sim_start="$(date -u -d "${stamp:0:4}-${stamp:4:2}-${stamp:6:2} ${stamp:8:2}:${stamp:10:2}:${stamp:12:2}" +%s 2>/dev/null)" ||
  { log "nao li o inicio da simulacao em $results"; exit 1; }
window_start=$((sim_start + warmup_s))
window_end=$((window_start + measured_s))
# o ultimo push de metricas (15 s) tem de chegar ao Prometheus antes da leitura
while [ "$(date +%s)" -lt $((window_end + 40)) ]; do sleep 2; done

# --- 4. extracao ----------------------------------------------------------------------------------------
promq() { # valor escalar da consulta no instante do fim da janela, vazio se nao ha dado (ou NaN)
  local v
  v="$(curl -fsS -u admin:admin --data-urlencode "query=$1" --data-urlencode "time=$window_end" "$prom" |
    jq -r '.data.result[0].value[1] // empty')"
  case "$v" in NaN | +Inf | -Inf) ;; *) echo "$v" ;; esac
}
w="${measured_s}s"
quantile() { promq "histogram_quantile($1, sum by (le) (increase($2_bucket${3:-}[$w])))"; }

: >perf/target/slo/slos.jsonl
slo() { # id, nome, alvo (texto), limite (numero ou vazio), unidade, valor (ou vazio), fonte, nota
  jq -nc --arg id "$1" --arg name "$2" --arg target "$3" --arg limit "$4" --arg unit "$5" --arg value "$6" --arg source "$7" --arg note "$8" '
    ($value | if . == "" then null else tonumber end) as $v | ($limit | if . == "" then null else tonumber end) as $l
    | {id: $id, name: $name, target: $target, limit: $l, unit: $unit, value: $v, source: $source, note: $note,
       met: (if $v == null or $l == null then null else $v <= $l end)}' >>perf/target/slo/slos.jsonl
}
gat() { # nome da requisicao, campo -> ms
  jq -r --arg n "$1" --arg f "$2" '[.[] | select(.name == $n)][0][$f] // empty' perf/target/slo/gatling.stats.json
}
r0() { awk -v x="$1" 'BEGIN { if (x != "") printf "%.0f", x }'; }
r2() { awk -v x="$1" 'BEGIN { if (x == "") exit; printf "%.2f", x }'; }

lag_n="$(r0 "$(promq "sum(increase(projection_lag_seconds_count[$w]))")")"
slo freshness_internal_p95 "Frescor interno: coleta até a projeção" "p95 até 30 s" 30 s "$(r2 "$(quantile 0.95 projection_lag_seconds)")" "projection_lag_seconds (histograma, janela medida)" "p99 $(r2 "$(quantile 0.99 projection_lag_seconds)") s, ${lag_n:-0} eventos projetados"

e2e="$(sql -c "SELECT round(percentile_cont(0.95) WITHIN GROUP (ORDER BY extract(epoch FROM projected_at - pncp_updated_at))::numeric, 2) || ' ' || count(*)
  FROM search.procurement_search_view WHERE source = 'pncp' AND numero_controle_pncp LIKE '11222333000181-%'
  AND projected_at BETWEEN to_timestamp($window_start) AND to_timestamp($window_end)")"
slo freshness_e2e_p95 "Frescor ponta a ponta: dataAtualizacaoGlobal até visível na busca" "p95 até o intervalo de polling mais 1 min ($((interval_s + 60)) s com polling de ${interval_s} s)" $((interval_s + 60)) s "$(r2 "${e2e%% *}")" "banco: projected_at menos pncp_updated_at dos editais do PNCP simulado" "${e2e##* } editais; o PNCP simulado carimba dataAtualizacaoGlobal entre 1 e 29 s antes da consulta (mudança em momento qualquer do intervalo)"

alert_n="$(r0 "$(promq "sum(increase(alert_delivery_latency_seconds_count[$w]))")")"
slo alert_delivery_p95 "Alerta: match até o SMTP aceitar" "p95 até 60 s" 60 s "$(r2 "$(quantile 0.95 alert_delivery_latency_seconds)")" "alert_delivery_latency_seconds (histograma, janela medida)" "p99 $(r2 "$(quantile 0.99 alert_delivery_latency_seconds)") s, ${alert_n:-0} e-mails entregues ao Mailpit"

for s in "S1 text search" "S2 filtered search"; do
  id="$(tr '[:upper:] ' '[:lower:]_' <<<"${s%% *}")"
  slo "${id}_p95" "Busca $s: p95" "p95 até 200 ms" 200 ms "$(gat "$s" p95)" "Gatling, fase medida" "$(gat "$s" requests) requisições, $(gat "$s" errors) erros"
  slo "${id}_p99" "Busca $s: p99" "p99 até 500 ms" 500 ms "$(gat "$s" p99)" "Gatling, fase medida" "p50 $(gat "$s" p50) ms"
done
slo s5_p95 "Detalhe S5 com cache: p95" "p95 até 50 ms" 50 ms "$(gat "S5 detail" p95)" "Gatling, fase medida" "$(gat "S5 detail" requests) requisições, $(gat "S5 detail" errors) erros"
slo outbox_p99 "Outbox: idade da mensagem mais antiga, p99" "p99 até 5 s" 5 s "$(r2 "$(promq "quantile_over_time(0.99, max(outbox_oldest_age_seconds)[$w:15s])")")" "outbox_oldest_age_seconds (medidor, amostra de 15 s da janela medida)" "máximo $(r2 "$(promq "max_over_time(max(outbox_oldest_age_seconds)[$w:15s])")") s"
slo availability_chaos "Disponibilidade da API durante caos com Toxiproxy" "99,5% em 1 h" 99.5 % "" "não medido: o compose não tem Toxiproxy; o caos está em docs/benchmarks/chaos/" ""

# informativos, sem alvo no plano
total5xx="$(r0 "$(promq "sum(increase(http_server_requests_seconds_count{uri=\"/api/v1/procurements\",status=~\"5..\"}[$w])) or vector(0)")")"
total="$(r0 "$(promq "sum(increase(http_server_requests_seconds_count{uri=\"/api/v1/procurements\"}[$w]))")")"
slo availability_nominal "Disponibilidade da busca sem caos (informativo)" "sem alvo próprio (o alvo do plano vale com caos)" "" % "$(awk -v a="${total5xx:-0}" -v t="${total:-0}" 'BEGIN { if (t > 0) printf "%.3f", 100 * (1 - a / t) }')" "http_server_requests_seconds_count, janela medida" "${total:-0} requisições, ${total5xx:-0} com 5xx"
slo search_server_p95 "Busca vista pelo servidor: p95 (informativo)" "sem alvo próprio" "" ms "$(awk -v x="$(quantile 0.95 http_server_requests_seconds '{uri="/api/v1/procurements"}')" 'BEGIN { if (x != "") printf "%.1f", x * 1000 }')" "http_server_requests_seconds (histograma, janela medida)" "sem a rede e a fila do cliente"
slo s6_p95 "Estatísticas S6: p95 (informativo)" "sem alvo no plano" "" ms "$(gat "S6 stats by uf" p95)" "Gatling, fase medida" ""

discovered="$(sql -c "SELECT count(*) FROM ingest.procurement WHERE numero_controle_pncp LIKE '11222333000181-%'")"
sent="$(sql -c "SELECT count(*) FROM alerts.alert_delivery WHERE sent_at IS NOT NULL")"
pg_version="Postgres $(sql -c 'SHOW server_version' | cut -d' ' -f1)"
host="$(sed -n 's/^Model name: *//p' <(lscpu)), $(nproc) threads, $(awk '/MemTotal/ { printf "%.1f", $2 / 1048576 }' /proc/meminfo | tr . ,) GiB de RAM"
real="$(sql -c "SELECT count(*) FROM search.procurement_search_view WHERE source = 'pncp' AND numero_controle_pncp NOT LIKE '11222333000181-%'")"

jq -n --arg date "$date" --arg commit "$(git rev-parse HEAD)" --arg host "$host" --arg pg "$pg_version" \
  --arg image "$(docker image inspect --format '{{.Id}}' "radar-licitacoes:$RADAR_TAG" | cut -c8-19)" \
  --argjson real "$real" --argjson synthetic "$synthetic" --argjson simulated "$discovered" \
  --argjson start "$window_start" --argjson end "$window_end" --argjson measured "$measured_s" --argjson warmup "$warmup_s" \
  --argjson interval "$interval_s" --argjson think "$think_ms" --argjson sent "$sent" --argjson runSeconds "$(($(date +%s) - run_started))" \
  --argjson users "{\"s1\": ${SLO_USERS_S1:-5}, \"s2\": ${SLO_USERS_S2:-5}, \"s5\": ${SLO_USERS_S5:-10}, \"s6\": ${SLO_USERS_S6:-2}}" \
  --slurpfile slos perf/target/slo/slos.jsonl --slurpfile gatling perf/target/slo/gatling.stats.json '
  {date: $date, commit: $commit, image: $image, host: $host, postgres: $pg,
   label: "sintético: massa e tráfego de ingestão são sintéticos (rótulo obrigatório em todo número)",
   rows: {total: ($real + $synthetic), real: $real, synthetic: $synthetic},
   ingestion: {simulatedProcurements: $simulated, pollIntervalSeconds: $interval, perTick: "40 novos e 10 alterados", alertsSent: $sent},
   window: {start: $start, end: $end, measuredSeconds: $measured, warmupSeconds: $warmup, runSeconds: $runSeconds},
   load: {users: $users, thinkMs: $think},
   slos: $slos,
   gatling: [$gatling[0][] | select(.name | endswith(" (warmup)") | not)]}' >"$out/$date.json"

scripts/render-metrics.sh slo-md "$out/$date.json" >"$out/$date.md"
log "pronto: $out/$date.json e $out/$date.md"
