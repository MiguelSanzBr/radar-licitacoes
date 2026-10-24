#!/usr/bin/env bash
# make bench: o benchmark da busca com mais de 1 milhao de linhas (PR 2.7, docs/benchmarks/search/).
#   1. grava o ambiente (env.md); 2. sobe o compose, carrega o dia real e completa com massa sintetica
#   rotulada; 3. EXPLAIN dos planos sem e com os indices; 4. Gatling S1 a S6; 5. metricas e README.
# Pre-requisitos: imagem radar-licitacoes:local (make image), JDK 25, Docker, jq, uns 6 GB de RAM livres
# e o volume do compose vazio (CLEAN=1 make down). Variaveis (todas opcionais):
#   BENCH_ROWS=1200000          total de linhas da view de busca, reais mais sinteticas
#   BENCH_SCENARIOS="s1 ... s6" quais cenarios rodar (padrao todos)
#   BENCH_USERS_S1 .. _S6       usuarios de um cenario; o padrao e o numero do plano (50 50 10 20 100 10)
#   BENCH_THINK_MS=1000  BENCH_WARMUP_S=60  BENCH_MEASURED_S=180
#   BENCH_REUSE=1               nao recarrega: usa a pilha e a massa que ja estao no ar
#   BENCH_KEEP=1                nao derruba a pilha ao fim
#   BENCH_RANK_CANDIDATES=2000  radar.search.rank-candidates da aplicacao (2147483647 mede a busca sem o teto)
#   BENCH_JAVA_OPTS=            opcoes extras da JVM da aplicacao (ex.: gravacao JFR, docs/benchmarks/jvm/)
#   BENCH_MIN_FREE_MB=6144      RAM livre exigida para comecar
#   BENCH_DATE=NOME             nome da pasta de saida em docs/benchmarks/search (padrao: a data de hoje, AAAA-MM-DD)
#   BENCH_POOL_SIZES="10 20 40" modo make bench-pool (PR 2.8, docs/benchmarks/db/hikari.md): carrega a massa uma vez e, para cada
#                               tamanho do pool de busca, reinicia so a aplicacao e roda os cenarios (padrao s1 s2 s5, sem EXPLAIN),
#                               gravando em docs/benchmarks/db/hikari/pool-N: Gatling, metricas do Hikari, pg_stat_activity e
#                               pg_stat_statements. Nao toca em docs/benchmarks/latest.json nem no README.
#   BENCH_POOL_DIR=PASTA        pasta de saida do modo acima (padrao docs/benchmarks/db/hikari); com BENCH_REUSE=1 e BENCH_THINK_MS=0
#                               a rodada de saturacao reaproveita a massa ja carregada
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

date="${BENCH_DATE:-$(date +%F)}"
dir="docs/benchmarks/search/$date"
pool_sizes="${BENCH_POOL_SIZES:-}"
scenarios="${BENCH_SCENARIOS:-s1 s2 s3 s4 s5 s6}"
if [ -n "$pool_sizes" ]; then
  dir="${BENCH_POOL_DIR:-docs/benchmarks/db/hikari}"
  scenarios="${BENCH_SCENARIOS:-s1 s2 s5}"
  BENCH_EXPLAIN="${BENCH_EXPLAIN:-0}"
fi
rows_target="${BENCH_ROWS:-1200000}"
think_ms="${BENCH_THINK_MS:-1000}"
warmup_s="${BENCH_WARMUP_S:-60}"
measured_s="${BENCH_MEASURED_S:-180}"
min_free_mb="${BENCH_MIN_FREE_MB:-6144}"
abort_free_mb=1024
app="http://localhost:8080"
export RADAR_TAG="${RADAR_TAG:-local}"
COMPOSE="docker compose -f infra/compose/docker-compose.yml -f infra/compose/docker-compose.bench.yml"
export COMPOSE

log() { echo "bench: $*" >&2; }
free_mb() { awk '/MemAvailable/ { print int($2 / 1024) }' /proc/meminfo; }
sql() { $COMPOSE exec -T postgres psql -U radar -d radar -X -qAt -v ON_ERROR_STOP=1 "$@"; }
has() { [[ " $scenarios " == *" $1 "* ]]; }

watchdog_pid=""
sampler_pid=""
cleanup() {
  [ -z "$watchdog_pid" ] || kill "$watchdog_pid" 2>/dev/null || true
  [ -z "$sampler_pid" ] || kill "$sampler_pid" 2>/dev/null || true
  if [ "${BENCH_KEEP:-0}" != 1 ]; then
    log "derrubando a pilha (make down)"
    make down >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT
trap 'exit 143' TERM INT

# --- pre-requisitos -------------------------------------------------------------------------------
docker image inspect "radar-licitacoes:$RADAR_TAG" >/dev/null 2>&1 || { log "falta a imagem radar-licitacoes:$RADAR_TAG (rode make image)"; exit 1; }
if pgrep -f 'surefire|failsafe' >/dev/null; then log "ha outra execucao de testes do Maven; espere ela acabar"; exit 1; fi
[ "$(free_mb)" -ge "$min_free_mb" ] || { log "so $(free_mb) MB de RAM livres, o benchmark pede $min_free_mb MB"; exit 1; }
if [ "${BENCH_REUSE:-0}" != 1 ] && docker volume inspect radar_pgdata >/dev/null 2>&1; then
  log "o volume radar_pgdata ja existe: rode CLEAN=1 make down (o benchmark parte de um banco vazio) ou use BENCH_REUSE=1"
  exit 1
fi

# vigia: com menos de 1 GB livres a execucao para, antes que o host comece a trocar para o disco
(
  while sleep 5; do
    if [ "$(free_mb)" -lt "$abort_free_mb" ]; then
      echo "bench: so $(free_mb) MB de RAM livres, abortando" >&2
      pkill -f io.gatling.app.Gatling || true
      kill -TERM $$
      exit
    fi
  done
) &
watchdog_pid=$!

base="$dir"
mkdir -p "$dir/explain" "$dir/gatling" perf/target/bench

# --- 1. pilha e ambiente ----------------------------------------------------------------------------
if [ "${BENCH_REUSE:-0}" != 1 ]; then
  log "subindo a pilha"
  scripts/compose-init.sh
  $COMPOSE up -d --wait
fi

host="$(sed -n 's/^Model name: *//p' <(lscpu)), $(nproc) threads, $(awk '/MemTotal/ { printf "%.1f", $2 / 1048576 }' /proc/meminfo | tr . ,) GiB de RAM"
pg_version="Postgres $(sql -c 'SHOW server_version' | cut -d' ' -f1)"
{
  echo "# Ambiente do benchmark de busca"
  echo
  echo "- Data: $(date +%F)"
  echo "- Commit: \`$(git rev-parse HEAD)\`$(git diff --quiet HEAD -- . ':!docs' ':!README.md' || echo ' (com alterações não commitadas fora de docs)')"
  echo "- $pg_version, imagem \`$($COMPOSE config --images postgres)\`"
  echo "- Docker: $(docker version --format '{{.Server.Version}}'), compose $(docker compose version --short)"
  echo "- Kernel: $(uname -sr)"
  echo
  echo "## CPU (\`lscpu\`)"
  echo
  echo '```'
  lscpu | grep -E '^(Architecture|Model name|CPU\(s\)|Thread|Core|Socket|CPU max MHz|L2|L3)'
  echo '```'
  echo
  echo "## Memória (\`free -m\`) e disco (\`df -h\`)"
  echo
  echo '```'
  free -m
  df -h . | sed -n '1p;2p'
  echo '```'
  echo
  echo "## Limites dos containers (\`docker-compose.yml\` mais \`docker-compose.bench.yml\`)"
  echo
  echo '```'
  mapfile -t containers < <($COMPOSE ps -q)
  docker inspect --format '{{.Name}} {{.HostConfig.Memory}} bytes' "${containers[@]}" | sort
  echo '```'
  echo
  echo "## Configuração efetiva do Postgres (\`pg_settings\`, só o que não é o padrão, mais os parâmetros que pesam no plano)"
  echo
  echo '```'
  sql -F ' = ' -c "SELECT name, current_setting(name) || '  [' || source || ']' FROM pg_settings WHERE source NOT IN ('default', 'override') OR name IN ('work_mem', 'random_page_cost', 'max_parallel_workers_per_gather', 'jit', 'default_statistics_target') ORDER BY name"
  echo '```'
} >"$dir/env.md"

# --- 2. massa -------------------------------------------------------------------------------------
if [ "${BENCH_REUSE:-0}" != 1 ]; then
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
  make synth SYNTH_COUNT=$((rows_target - real)) | tee "$dir/synth.log"
  log "reconstruindo as estatisticas e rodando VACUUM ANALYZE"
  # a carga sintetica nao passa pelo projetor: as estatisticas saem da view, como na migration V15
  sql -c "TRUNCATE search.procurement_stats_view;
    INSERT INTO search.procurement_stats_view (uf, modality_id, month, value_band, count, estimated_sum)
    SELECT coalesce(uf, 'XX'), modality_id, date_trunc('month', published_at AT TIME ZONE 'America/Sao_Paulo')::date,
           value_band, count(*), coalesce(sum(estimated_value), 0)
    FROM search.procurement_search_view GROUP BY 1, 2, 3, 4"
  # idem para a tabela de orgaos (V16): uma linha por nome, com a contagem de licitacoes
  sql -c "TRUNCATE search.organ;
    INSERT INTO search.organ (organ_name, procurement_count)
    SELECT organ_name, count(*) FROM search.procurement_search_view GROUP BY 1"
  for table in procurement_search_view procurement_detail_view procurement_stats_view organ; do
    sql -c "VACUUM (ANALYZE) search.$table"
  done
fi
real="$(sql -c "SELECT count(*) FROM search.procurement_search_view WHERE source = 'pncp'")"
synthetic="$(sql -c "SELECT count(*) FROM search.procurement_search_view WHERE source = 'synthetic'")"
{
  echo
  echo "## Dados"
  echo
  echo "- Linhas na view de busca: $((real + synthetic)) ($real reais do PNCP, $synthetic sintéticas): inclui dados sintéticos ($synthetic linhas)"
  echo "- Linhas no detalhe: $(sql -c 'SELECT count(*) FROM search.procurement_detail_view'); no stats: $(sql -c 'SELECT count(*) FROM search.procurement_stats_view')"
  echo "- Tamanho: tabela de busca $(sql -c "SELECT pg_size_pretty(pg_table_size('search.procurement_search_view'))"), índices $(sql -c "SELECT pg_size_pretty(pg_indexes_size('search.procurement_search_view'))"), detalhe $(sql -c "SELECT pg_size_pretty(pg_total_relation_size('search.procurement_detail_view'))")"
} >>"$dir/env.md"

# --- 3. EXPLAIN sem e com indices -------------------------------------------------------------------
if [ "${BENCH_EXPLAIN:-1}" = 1 ]; then
  log "EXPLAIN sem os indices secundarios"
  scripts/explain/run.sh before "$dir/explain"
  log "EXPLAIN com os indices"
  scripts/explain/run.sh after "$dir/explain"
  scripts/explain/summarize.sh "$dir/explain" >"$dir/explain/summary.md"
fi

# --- 4. Gatling ---------------------------------------------------------------------------------------
declare -A class=([s1]=S1TextSearch [s2]=S2FilteredSearch [s3]=S3Pagination [s4]=S4OrganTypo [s5]=S5Detail [s6]=S6Stats)
declare -A planned=([s1]=50 [s2]=50 [s3]=10 [s4]=20 [s5]=100 [s6]=10)

cache_counts() { # imprime "hits misses" lidos do contador da propria aplicacao
  curl -fsS -H "Authorization: Bearer $(scripts/ops-token.sh)" "$app/actuator/prometheus" |
    awk '/^search_detail_cache_requests_total/ { v = $NF; if ($0 ~ /result="hit"/) h += v; else if ($0 ~ /result="miss"/) m += v }
         END { printf "%d %d\n", h, m }'
}

pool_metrics() { # imprime "epoch max active pending usage_n usage_sum acquire_n acquire_sum" do pool de busca, lidos do Prometheus da aplicacao
  curl -fsS -H "Authorization: Bearer $(scripts/ops-token.sh)" "$app/actuator/prometheus" |
    awk -v now="$(date +%s)" '/^hikaricp_connections_[a-z_]+\{/ && /pool="search"/ { name = $1; sub(/\{.*/, "", name); v[name] = $NF }
      END { printf "%s %d %d %d %d %.6f %d %.6f\n", now, v["hikaricp_connections_max"], v["hikaricp_connections_active"], v["hikaricp_connections_pending"],
            v["hikaricp_connections_usage_seconds_count"], v["hikaricp_connections_usage_seconds_sum"],
            v["hikaricp_connections_acquire_seconds_count"], v["hikaricp_connections_acquire_seconds_sum"] }'
}

sample_pool() { # $1 = arquivo: a cada 5 s, pool_metrics mais "conexoes do papel da busca no Postgres, quantas ativas"
  while sleep 5; do
    echo "$(pool_metrics 2>/dev/null || true) $(sql -c "SELECT count(*) || ' ' || count(*) FILTER (WHERE state = 'active') FROM pg_stat_activity WHERE datname = 'radar' AND usename = 'radar_read_writer'" 2>/dev/null | tr -d '\n')" >>"$1"
  done
}

pool_summary() { # $1 = amostras, $2 = epoch do fim do aquecimento; JSON da fase medida (media e maximo por amostra, tempos medios por uso e por espera em ms)
  awk -v from="$2" 'NF == 10 && $1 >= from {
      n++; max = $2; act += $3; if ($3 > amax) amax = $3; pend += $4; if ($4 > pmax) pmax = $4
      if (n == 1) { u0 = $5; us0 = $6; a0 = $7; as0 = $8 } u1 = $5; us1 = $6; a1 = $7; as1 = $8
      pgc += $9; if ($9 > pgmax) pgmax = $9; pga += $10; if ($10 > pgamax) pgamax = $10 }
    END { if (n == 0) { print "{}"; exit }
      printf "{\"samples\": %d, \"poolMax\": %d, \"activeAvg\": %.1f, \"activeMax\": %d, \"pendingAvg\": %.2f, \"pendingMax\": %d, ", n, max, act / n, amax, pend / n, pmax
      printf "\"usageMeanMs\": %.2f, \"acquireMeanMs\": %.2f, ", (u1 > u0 ? (us1 - us0) / (u1 - u0) * 1000 : 0), (a1 > a0 ? (as1 - as0) / (a1 - a0) * 1000 : 0)
      printf "\"pgConnectionsAvg\": %.1f, \"pgConnectionsMax\": %d, \"pgActiveAvg\": %.1f, \"pgActiveMax\": %d}\n", pgc / n, pgmax, pga / n, pgamax }' "$1" | jq .
}

run_gatling() { # $1 = id (s1..s6)
  local id="$1" users_var="BENCH_USERS_${1^^}" users started
  users="${!users_var:-${planned[$id]}}"
  log "Gatling ${id^^}: $users usuarios, ${warmup_s}s de aquecimento e ${measured_s}s medidos"
  started="$(date +%s)"
  if [ -n "$pool_sizes" ]; then
    mkdir -p "$dir/pool"
    : >"$dir/pool/$id.samples"
    sample_pool "$dir/pool/$id.samples" &
    sampler_pid=$!
  fi
  ./mvnw -q -Pperf -pl perf test-compile gatling:test \
    "-Dgatling.simulationClass=br.dev.miguelsanzbr.radar.perf.${class[$id]}" \
    "-Dbench.users.$id=$users" "-Dbench.think-ms=$think_ms" \
    "-Dbench.warmup-seconds=$warmup_s" "-Dbench.measured-seconds=$measured_s" \
    "-Dbench.base-url=$app" "-Dbench.ids-file=$root/perf/target/bench/ids.csv" >"perf/target/bench/$id.log" 2>&1 ||
    { tail -30 "perf/target/bench/$id.log" >&2; log "Gatling ${id^^} falhou"; exit 1; }
  if [ -n "$sampler_pid" ]; then
    kill "$sampler_pid" 2>/dev/null || true
    sampler_pid=""
    pool_summary "$dir/pool/$id.samples" $((started + warmup_s)) >"$dir/pool/$id.json"
  fi
  local results
  # shellcheck disable=SC2012 # os nomes sao do Gatling: letras, digitos e hifen
  results="$(ls -dt perf/target/gatling/"$(tr '[:upper:]' '[:lower:]' <<<"${class[$id]}")"-* | head -1)"
  scripts/gatling-stats.sh "$results/index.html" >"$dir/gatling/$id.stats.json"
  jq -n --argjson planned "${planned[$id]}" --argjson used "$users" '{plannedUsers: $planned, usedUsers: $used}' >"$dir/gatling/$id.load.json"
  local errors total
  errors="$(jq '[.[] | select(.name | endswith(" (warmup)") | not) | .errors] | add' "$dir/gatling/$id.stats.json")"
  total="$(jq '[.[] | select(.name | endswith(" (warmup)") | not) | .requests] | add' "$dir/gatling/$id.stats.json")"
  log "${id^^}: $total requisicoes, $errors com erro"
  if [ "$errors" -gt $((total / 20)) ]; then
    log "AVISO: mais de 5% de erros em ${id^^}; o host saturou ou a consulta estourou o statement_timeout: reduza BENCH_USERS_${id^^} e rode de novo so este cenario"
  fi
}

run_scenarios() {
  if has s1; then sql -c 'SELECT pg_stat_statements_reset()' >/dev/null; fi
  for id in $scenarios; do
    if [ "$id" = s5 ]; then
      sql -c "COPY (SELECT replace(numero_controle_pncp, '/', '-') FROM search.procurement_detail_view ORDER BY random() LIMIT 20000) TO STDOUT" >perf/target/bench/ids.csv
      read -r hits0 misses0 < <(cache_counts)
    fi
    run_gatling "$id"
    if [ "$id" = s5 ]; then
      read -r hits1 misses1 < <(cache_counts)
      jq -n --argjson h $((hits1 - hits0)) --argjson m $((misses1 - misses0)) \
        '{hits: $h, misses: $m, hitRatio: (if $h + $m == 0 then 0 else $h / ($h + $m) end)}' >"$dir/gatling/s5.cache.json"
    fi
  done
}

write_pgss() { # $1 = o que o reset e a leitura cobrem
  log "pg_stat_statements"
  {
    echo "# pg_stat_statements: as 10 consultas que mais gastaram tempo"
    echo
    echo "$1 Os EXPLAIN rodam antes do reset e não entram. Tempos em ms."
    echo
    echo "| Consulta | Chamadas | Total (ms) | Média (ms) | Máximo (ms) | Linhas |"
    echo "| --- | ---: | ---: | ---: | ---: | ---: |"
    sql -c "SELECT '| ' || left(regexp_replace(query, '[|[:space:]]+', ' ', 'g'), 110) || ' | ' || calls || ' | ' || round(total_exec_time::numeric, 0) || ' | ' || round(mean_exec_time::numeric, 2) || ' | ' || round(max_exec_time::numeric, 1) || ' | ' || rows || ' |'
      FROM pg_stat_statements WHERE dbid = (SELECT oid FROM pg_database WHERE datname = 'radar') ORDER BY total_exec_time DESC LIMIT 10"
  } >"$dir/pg_stat_statements.md"
}

if [ -z "$pool_sizes" ]; then
  run_scenarios
  if has s6; then write_pgss "Zerado antes do S1 e lido depois do S6;"; fi
else
  for size in $pool_sizes; do
    dir="$base/pool-$size"
    [ ! -d "$dir" ] || dir="$dir-rerun"
    mkdir -p "$dir/gatling"
    log "pool de busca com $size conexoes: reiniciando so a aplicacao e esvaziando o cache do detalhe"
    BENCH_SEARCH_POOL="$size" $COMPOSE up -d --no-deps --force-recreate --wait app
    $COMPOSE exec -T redis redis-cli FLUSHALL >/dev/null
    # o Hikari so abre o pool (e so registra as metricas) na primeira conexao: uma busca simples o inicia antes da conferencia
    curl -fsS "$app/api/v1/procurements?limit=1" >/dev/null
    read -r _ max _ < <(pool_metrics)
    [ "$max" = "$size" ] || { log "o pool de busca tem $max conexoes, nao $size"; exit 1; }
    run_scenarios
    write_pgss "Zerado antes do S1 e lido depois do último cenário, com o pool de busca em $size conexões."
  done
  dir="$base"
  jq -n --arg date "$(date +%F)" --arg commit "$(git rev-parse HEAD)" --arg host "$host" --arg pg "$pg_version" \
    --arg sizes "$pool_sizes" --arg scenarios "$scenarios" --argjson real "$real" --argjson synthetic "$synthetic" \
    --argjson think "$think_ms" --argjson warmup "$warmup_s" --argjson measured "$measured_s" \
    '{date: $date, commit: $commit, host: $host, postgres: $pg, poolSizes: ($sizes | split(" ")), scenarios: ($scenarios | split(" ")),
      rows: {total: ($real + $synthetic), real: $real, synthetic: $synthetic}, thinkMs: $think, warmupSeconds: $warmup, measuredSeconds: $measured}' >"$dir/run.json"
  log "pronto: $dir"
  exit 0
fi

# --- 5. metricas ----------------------------------------------------------------------------------------
load='{}'
for file in "$dir"/gatling/s?.load.json; do
  [ -f "$file" ] || continue
  load="$(jq --arg id "$(basename "$file" .load.json)" --slurpfile l "$file" '.[$id] = $l[0]' <<<"$load")"
done
cache='{"hits": 0, "misses": 0, "hitRatio": 0}'
[ ! -f "$dir/gatling/s5.cache.json" ] || cache="$(cat "$dir/gatling/s5.cache.json")"
jq -n --arg date "$(date +%F)" --arg commit "$(git rev-parse HEAD)" --arg host "$host" --arg pg "$pg_version" \
  --arg directory "$dir" --argjson real "$real" --argjson synthetic "$synthetic" \
  --argjson think "$think_ms" --argjson warmup "$warmup_s" --argjson measured "$measured_s" \
  --argjson load "$load" --argjson cache "$cache" \
  '{date: $date, commit: $commit, host: $host, postgres: $pg, directory: $directory,
    rows: {total: ($real + $synthetic), real: $real, synthetic: $synthetic},
    thinkMs: $think, warmupSeconds: $warmup, measuredSeconds: $measured, load: $load, detailCache: $cache}' >"$dir/run.json"
scripts/render-metrics.sh collect "$dir"
scripts/render-metrics.sh readme
log "pronto: $dir"
