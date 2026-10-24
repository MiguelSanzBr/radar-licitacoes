#!/usr/bin/env bash
# make profile GC=serial|g1|zgc|all: a mesma carga sob cada coletor de lixo (PR 6.2, docs/benchmarks/jvm/gc.md).
#   1. sobe o compose (com docker-compose.bench.yml) se nao estiver no ar e carrega a massa uma vez: o dia real
#      do seed mais PROFILE_ROWS eventos sinteticos rotulados, publicados no topico e projetados pelo proprio app;
#   2. reinicia so a aplicacao com o GC pedido e -Xlog:gc* gravando em /tmp/gc.log dentro do container;
#   3. Gatling S1 (aquecimento e medicao) e depois o replay do topico inteiro (`make replay`);
#   4. coleta latencias e rps (Gatling), pausas de GC (scripts/gc-pauses.sh), CPU e memoria do container;
#   5. grava docs/benchmarks/jvm/gc-<gc>.json (gc-<gc>-<memoria>.json se PROFILE_APP_MEM nao for 640m).
# A pilha fica no ar entre uma execucao e a seguinte (a massa leva minutos para carregar): derrube com CLEAN=1 make down,
# ou use PROFILE_DOWN=1. Pre-requisitos: imagem radar-licitacoes:local (make image), JDK 25, Docker, jq, uns 4,5 GB de RAM livres.
# Variaveis (todas opcionais):
#   PROFILE_ROWS=200000       eventos sinteticos (somam-se ao dia real do seed)
#   PROFILE_USERS=50  PROFILE_THINK_MS=1000  PROFILE_WARMUP_S=60  PROFILE_MEASURED_S=180
#   PROFILE_APP_MEM=640m      mem_limit da aplicacao
#   PROFILE_OUT=docs/benchmarks/jvm   PROFILE_MIN_FREE_MB=4608   PROFILE_DOWN=0
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

declare -A flag=([serial]=-XX:+UseSerialGC [g1]=-XX:+UseG1GC [zgc]=-XX:+UseZGC)
request="${1:-${GC:-}}"
[ -n "$request" ] || { echo "uso: make profile GC=serial|g1|zgc|all" >&2; exit 2; }
[ "$request" != all ] || request="serial g1 zgc"
for gc in $request; do
  [ -n "${flag[$gc]:-}" ] || { echo "profile: GC desconhecido: $gc (serial, g1, zgc ou all)" >&2; exit 2; }
done

rows="${PROFILE_ROWS:-200000}"
users="${PROFILE_USERS:-50}"
think_ms="${PROFILE_THINK_MS:-1000}"
warmup_s="${PROFILE_WARMUP_S:-60}"
measured_s="${PROFILE_MEASURED_S:-180}"
min_free_mb="${PROFILE_MIN_FREE_MB:-4608}"
abort_free_mb=1024
app_mem="${PROFILE_APP_MEM:-640m}"
out="${PROFILE_OUT:-docs/benchmarks/jvm}"
suffix=""
[ "$app_mem" = 640m ] || suffix="-$app_mem"
work=perf/target/profile
app=http://localhost:8080
topic=radar.procurement.v1
export RADAR_TAG="${RADAR_TAG:-local}" BENCH_APP_MEM="$app_mem"
COMPOSE="docker compose -f infra/compose/docker-compose.yml -f infra/compose/docker-compose.bench.yml"

log() { echo "profile: $*" >&2; }
free_mb() { awk '/MemAvailable/ { print int($2 / 1024) }' /proc/meminfo; }
now() { date -u +%Y-%m-%dT%H:%M:%S.%3N; }
sql() { $COMPOSE exec -T postgres psql -U radar -d radar -X -qAt -v ON_ERROR_STOP=1 "$@"; }
container() { $COMPOSE ps -q "$1"; }

watchdog_pid="" sampler_pid=""
cleanup() {
  [ -z "$watchdog_pid" ] || kill "$watchdog_pid" 2>/dev/null || true
  [ -z "$sampler_pid" ] || kill "$sampler_pid" 2>/dev/null || true
  if [ "${PROFILE_DOWN:-0}" = 1 ]; then log "derrubando a pilha"; CLEAN=1 make down >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT
trap 'exit 143' TERM INT

# --- pre-requisitos ----------------------------------------------------------------------------------
docker image inspect "radar-licitacoes:$RADAR_TAG" >/dev/null 2>&1 || { log "falta a imagem radar-licitacoes:$RADAR_TAG (make image)"; exit 1; }
if pgrep -f 'surefire|failsafe' >/dev/null; then log "ha outra execucao de testes do Maven; espere ela acabar"; exit 1; fi
[ "$(free_mb)" -ge "$min_free_mb" ] || [ -n "$(container app)" ] || { log "so $(free_mb) MB de RAM livres, o perfil pede $min_free_mb MB"; exit 1; }
(
  while sleep 5; do
    if [ "$(free_mb)" -lt "$abort_free_mb" ]; then
      echo "profile: so $(free_mb) MB de RAM livres, abortando" >&2
      pkill -f io.gatling.app.Gatling || true
      kill -TERM $$
      exit
    fi
  done
) &
watchdog_pid=$!
mkdir -p "$work" "$out"

# --- massa -------------------------------------------------------------------------------------------
lag() { # atraso somado dos grupos de consumo no topico (o consumidor da JVM do kafka cabe no limite com heap pequeno)
  docker exec -e KAFKA_HEAP_OPTS=-Xmx96m "$(container kafka)" /opt/kafka/bin/kafka-consumer-groups.sh \
    --bootstrap-server localhost:9092 --describe --all-groups 2>/dev/null |
    awk -v t="$topic" '$2 == t && $6 ~ /^[0-9]+$/ { s += $6 } END { print s + 0 }'
}

wait_lag_zero() { # $1 = limite em segundos
  local deadline=$((SECONDS + $1)) pending
  while pending="$(lag)"; [ "$pending" -gt 0 ]; do
    [ "$SECONDS" -lt "$deadline" ] || { log "o atraso dos consumidores nao zerou em $1 s ($pending eventos)"; exit 1; }
    log "atraso dos consumidores: $pending eventos"
    sleep 20
  done
}

load_dataset() {
  local synthetic
  synthetic="$(sql -c "SELECT count(*) FROM search.procurement_search_view WHERE source = 'synthetic'")"
  if [ "$synthetic" -ge "$rows" ]; then log "massa ja carregada ($synthetic linhas sinteticas)"; return; fi
  [ "$synthetic" -eq 0 ] || { log "massa pela metade ($synthetic de $rows linhas): rode CLEAN=1 make down e comece de novo"; exit 1; }
  log "carregando o dia real (seed)"
  scripts/seed.sh "${SEED_DAY:-2026-10-21}" >/dev/null
  local last=-1 now_count
  for _ in $(seq 1 90); do
    sleep 4
    now_count="$(sql -c 'SELECT count(*) FROM search.procurement_search_view')"
    [ "$now_count" -gt 0 ] && [ "$now_count" = "$last" ] && break
    last="$now_count"
  done
  log "gerando e publicando $rows eventos sinteticos em $topic"
  make synth SYNTH_MODE=events SYNTH_COUNT="$rows" SYNTH_OUT="$work/synth-events.jsonl.gz" >&2
  local image network
  image="$($COMPOSE config --images kafka)"
  network="$(docker inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{$k}}{{end}}' "$(container kafka)")"
  zcat "$work/synth-events.jsonl.gz" | jq -r '.aggregateId + "\t" + tostring' |
    docker run --rm -i --network "$network" -e KAFKA_HEAP_OPTS=-Xmx128m "$image" \
      /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka:9092 --topic "$topic" \
      --property parse.key=true --property "key.separator=$(printf '\t')" \
      --producer-property linger.ms=20 --producer-property compression.type=lz4
  log "esperando o projetor e o matcher consumirem os eventos"
  wait_lag_zero 3600
  synthetic="$(sql -c "SELECT count(*) FROM search.procurement_search_view WHERE source = 'synthetic'")"
  [ "$synthetic" -ge "$rows" ] || { log "so $synthetic de $rows linhas sinteticas na view"; exit 1; }
  for table in procurement_search_view procurement_detail_view procurement_stats_view organ; do
    sql -c "VACUUM (ANALYZE) search.$table"
  done
}

# --- medicao -----------------------------------------------------------------------------------------
cpu_usec() { docker exec "$1" awk '/^usage_usec/ { print $2 }' /sys/fs/cgroup/cpu.stat; }
rss_peak_mib() { docker exec "$1" awk '/^VmHWM/ { printf "%.0f", $2 / 1024 }' /proc/1/status; }
cgroup_peak_mib() { docker exec "$1" awk '{ printf "%.0f", $1 / 1048576 }' /sys/fs/cgroup/memory.peak; }

start_sampler() { # $1 = container, $2 = arquivo: uso de memoria do `docker stats` a cada ~2 s
  ( while docker stats --no-stream --format '{{.MemUsage}}' "$1" >>"$2" 2>/dev/null; do sleep 1; done ) &
  sampler_pid=$!
}
stop_sampler() { kill "$sampler_pid" 2>/dev/null || true; sampler_pid=""; }
stats_peak_mib() {
  sed 's| /.*||' "$1" | awk '
    { v = $1 + 0; if ($1 ~ /GiB$/) v *= 1024; else if ($1 ~ /KiB$/ || $1 ~ /kB$/) v /= 1024; else if ($1 ~ /[0-9]B$/ && $1 !~ /iB$/) v /= 1048576
      if (v > m) m = v } END { printf "%.0f", m }'
}

run_s1() { # $1 = arquivo de saida do relatorio; imprime o diretorio de resultados do Gatling
  ./mvnw -q -Pperf -pl perf test-compile gatling:test \
    "-Dgatling.simulationClass=br.dev.miguelsanzbr.radar.perf.S1TextSearch" \
    "-Dbench.users.s1=$users" "-Dbench.think-ms=$think_ms" \
    "-Dbench.warmup-seconds=$warmup_s" "-Dbench.measured-seconds=$measured_s" \
    "-Dbench.base-url=$app" >"$1" 2>&1 || { tail -30 "$1" >&2; log "Gatling S1 falhou"; exit 1; }
  # shellcheck disable=SC2012 # os nomes sao do Gatling: letras, digitos e hifen
  ls -dt perf/target/gatling/s1textsearch-* | head -1
}

measure() { # $1 = gc
  local gc="$1" cid t0 t1 cpu0 cpu1 cpu2 s1dir gclog
  gclog="$work/gc-$gc.log"
  export BENCH_GC_OPTS="${flag[$gc]}" BENCH_JAVA_OPTS="-Xlog:gc*:file=/tmp/gc.log:utctime,level,tags:filecount=0"
  log "[$gc] reiniciando a aplicacao com ${flag[$gc]} (mem_limit $app_mem)"
  $COMPOSE up -d --force-recreate --no-deps --wait app >&2
  cid="$(container app)"
  wait_lag_zero 600

  log "[$gc] Gatling S1: $users usuarios, ${warmup_s}s de aquecimento e ${measured_s}s medidos"
  : >"$work/mem-s1-$gc.txt"
  start_sampler "$cid" "$work/mem-s1-$gc.txt"
  cpu0="$(cpu_usec "$cid")"
  t0="$(now)"
  s1dir="$(run_s1 "$work/s1-$gc.log")"
  t1="$(now)"
  cpu1="$(cpu_usec "$cid")"
  stop_sampler
  scripts/gatling-stats.sh "$s1dir/index.html" >"$work/s1-$gc.stats.json"
  local rss_s1 stats_s1
  rss_s1="$(rss_peak_mib "$cid")"
  stats_s1="$(stats_peak_mib "$work/mem-s1-$gc.txt")"

  log "[$gc] replay do topico"
  : >"$work/mem-replay-$gc.txt"
  start_sampler "$cid" "$work/mem-replay-$gc.txt"
  local r0 r1 report
  r0="$(now)"
  report="$(make -s replay | tail -n 1)"
  r1="$(now)"
  cpu2="$(cpu_usec "$cid")"
  stop_sampler

  docker cp "$cid:/tmp/gc.log" "$gclog"
  local oom restarts running
  oom="$(docker inspect -f '{{.State.OOMKilled}}' "$cid")"
  restarts="$(docker inspect -f '{{.RestartCount}}' "$cid")"
  running="$(docker inspect -f '{{.State.Running}}' "$cid")"
  [ "$running" = true ] || { log "[$gc] a aplicacao parou durante a medicao (OOMKilled=$oom); log em $gclog"; exit 1; }

  local jvm init s1row
  jvm="$(docker exec "$cid" java -version 2>&1 | grep -m1 version)"
  init="$(sed -n 's/^.*\[gc,init *\] //p' "$gclog" | jq -R . | jq -s .)"
  scripts/gc-pauses.sh "$gclog" >"$work/pauses-all-$gc.json"
  scripts/gc-pauses.sh "$gclog" "$t0" "$t1" >"$work/pauses-s1-$gc.json"
  scripts/gc-pauses.sh "$gclog" "$r0" "$r1" >"$work/pauses-replay-$gc.json"
  s1row="$(jq '[.[] | select(.name | endswith(" (warmup)") | not)][0]' "$work/s1-$gc.stats.json")"
  local cores
  cores="$(nproc)"
  jq -n --arg gc "$gc" --arg flag "${flag[$gc]}" --arg mem "$app_mem" --arg date "$(date +%F)" \
    --arg commit "$(git rev-parse HEAD)" --arg jvm "$jvm" --argjson init "$init" \
    --argjson rows "$(sql -c "SELECT json_build_object('total', count(*), 'real', count(*) FILTER (WHERE source = 'pncp'), 'synthetic', count(*) FILTER (WHERE source = 'synthetic')) FROM search.procurement_search_view")" \
    --argjson users "$users" --argjson think "$think_ms" --argjson warm "$warmup_s" --argjson measured "$measured_s" \
    --argjson s1 "$s1row" --argjson report "$report" \
    --argjson cpuS1 "$((cpu1 - cpu0))" --argjson cpuReplay "$((cpu2 - cpu1))" \
    --argjson pausesS1 "$(cat "$work/pauses-s1-$gc.json")" --argjson pausesReplay "$(cat "$work/pauses-replay-$gc.json")" \
    --argjson pausesAll "$(cat "$work/pauses-all-$gc.json")" \
    --argjson rssS1 "$rss_s1" --argjson rssEnd "$(rss_peak_mib "$cid")" --argjson cgroupPeak "$(cgroup_peak_mib "$cid")" \
    --argjson statsS1 "$stats_s1" --argjson statsReplay "$(stats_peak_mib "$work/mem-replay-$gc.txt")" \
    --argjson oom "$oom" --argjson restarts "$restarts" --argjson cores "$cores" --arg t0 "$t0" --arg t1 "$t1" \
    '{gc: $gc, flag: $flag, appMemLimit: $mem, date: $date, commit: $commit, jvm: $jvm, gcInit: $init, rows: $rows,
      s1: {users: $users, thinkMs: $think, warmupSeconds: $warm, measuredSeconds: $measured,
           requests: $s1.requests, errors: $s1.errors, rps: $s1.rps, p50Ms: $s1.p50, p95Ms: $s1.p95, p99Ms: $s1.p99, maxMs: $s1.max,
           cpuSeconds: ($cpuS1 / 1e6 | . * 10 | round / 10), gcPauses: $pausesS1},
      replay: {events: $report.report.events, seconds: ($report.report.events / $report.report.eventsPerSecond | . * 10 | round / 10),
               eventsPerSecond: ($report.report.eventsPerSecond | . * 10 | round / 10),
               cpuSeconds: ($cpuReplay / 1e6 | . * 10 | round / 10), gcPauses: $pausesReplay},
      gcPausesWholeRun: $pausesAll,
      memory: {rssPeakMiBAfterS1: $rssS1, rssPeakMiBAfterReplay: $rssEnd, cgroupPeakMiB: $cgroupPeak,
               dockerStatsPeakMiBS1: $statsS1, dockerStatsPeakMiBReplay: $statsReplay},
      oomKilled: $oom, restarts: $restarts, hostThreads: $cores}' >"$out/gc-$gc$suffix.json"
  log "[$gc] gravado $out/gc-$gc$suffix.json"
}

# --- pilha e execucao --------------------------------------------------------------------------------
export BENCH_GC_OPTS="${flag[${request%% *}]}"
if [ -z "$(container app)" ]; then
  log "subindo a pilha"
  scripts/compose-init.sh
  $COMPOSE up -d --wait >&2
fi
load_dataset
for gc in $request; do
  measure "$gc"
done
log "pronto"
