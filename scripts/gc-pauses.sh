#!/usr/bin/env bash
# Pausas de GC de um log `-Xlog:gc*:...:utctime,level,tags` (Serial, G1 e ZGC), como JSON em ms:
# {count, p50Ms, p99Ms, maxMs, totalMs}. Conta as linhas "Pause ... Nms": o Serial e o G1 as escrevem na tag gc,
# o ZGC na tag gc,phases (uma por fase com parada do mundo). Fases concorrentes nao entram.
# Uso: scripts/gc-pauses.sh gc.log [DE ATE], com DE e ATE como AAAA-MM-DDTHH:MM:SS.mmm em UTC (janela fechada).
set -euo pipefail

log="${1:?uso: $0 gc.log [DE ATE]}"
from="${2:-0000}"
to="${3:-9999}"

awk -v from="$from" -v to="$to" '
  match($0, /\] GC\([0-9]+\) ([yYoO]: )?Pause .* [0-9]+\.[0-9]+ms$/) {
    ts = substr($0, 2, 23)
    if (ts < from || ts > to) next
    ms = $NF; sub(/ms$/, "", ms); print ms
  }' "$log" | sort -n | awk '
  { v[NR] = $1; total += $1 }
  END {
    if (NR == 0) { print "{\"count\":0,\"p50Ms\":0,\"p99Ms\":0,\"maxMs\":0,\"totalMs\":0}"; exit }
    p50 = int((NR * 50 + 99) / 100); p99 = int((NR * 99 + 99) / 100)
    printf "{\"count\":%d,\"p50Ms\":%s,\"p99Ms\":%s,\"maxMs\":%s,\"totalMs\":%.3f}\n", NR, v[p50], v[p99], v[NR], total
  }'
