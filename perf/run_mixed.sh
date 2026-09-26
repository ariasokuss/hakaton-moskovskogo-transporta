#!/usr/bin/env bash
# Ступенчатый нагрузочный тест смешанным сценарием (perf/targets-mixed.txt, см. gen_targets.py).
#
# На каждой ступени фиксированная частота запросов 30 с; параллельно снимается загрузка CPU и RAM
# контейнера бэкенда (docker stats: 100% = одно ядро, лимит контейнера — 2 vCPU = 200%).
# Результат — perf/mixed-steps.txt. Запуск из корня репозитория после `docker compose up -d`:
#
#   bash perf/run_mixed.sh [сеть docker] [ступени RPS...]
set -euo pipefail
cd "$(dirname "$0")/.."
NET="${1:-$(docker inspect -f '{{range $k,$v := .NetworkSettings.Networks}}{{$k}}{{end}}' tram-backend)}"
shift || true
STEPS="${*:-200 400 600 800}"
PERF_DIR="$(pwd -W 2>/dev/null || pwd)/perf"
OUT=perf/mixed-steps.txt

attack() {   # $1 — RPS, $2 — длительность
  MSYS_NO_PATHCONV=1 docker run --rm --network "$NET" -v "$PERF_DIR:/perf" peterevans/vegeta sh -c \
    "vegeta attack -targets=/perf/targets-mixed.txt -rate=$1 -duration=$2 -timeout=5s -workers=64 | vegeta report"
}

echo "Прогрев JIT: 200 RPS × 15 с"; attack 200 15s > /dev/null

: > "$OUT"
for rps in $STEPS; do
  echo "=== ступень $rps RPS, 30 с ===" | tee -a "$OUT"
  ( end=$((SECONDS + 28)); while [ $SECONDS -lt $end ]; do
      docker stats --no-stream --format '{{.CPUPerc}};{{.MemUsage}}' tram-backend; done ) > perf/.stats.tmp &
  attack "$rps" 30s | tee -a "$OUT"
  wait
  awk -F';' '{gsub("%","",$1); c=$1+0; s+=c; n++; if (c>m) m=c; mem=$2} END {printf "CPU бэкенда: среднее %.0f%%, пик %.0f%% (из 200%% = 2 vCPU); RAM: %s\n", s/n, m, mem}' \
    perf/.stats.tmp | tee -a "$OUT"
done
rm -f perf/.stats.tmp
