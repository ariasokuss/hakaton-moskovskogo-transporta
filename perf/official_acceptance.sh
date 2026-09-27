#!/usr/bin/env bash
# Приёмочный нагрузочный тест: одна заявляемая рабочая нагрузка, 60 с, смешанный сценарий (perf/targets-mixed.txt).
# Отчёт — perf/official-acceptance.txt: дата, коммит, ID образов, команда, число запросов, HTTP-коды,
# ошибки и таймауты, p50/p95/p99, CPU среднее/пик, RAM до и после, docker compose ps.
#
#   bash perf/official_acceptance.sh [RPS=2500]
set -euo pipefail
cd "$(dirname "$0")/.."
RPS="${1:-2500}"
NET="$(docker inspect -f '{{range $k,$v := .NetworkSettings.Networks}}{{$k}}{{end}}' tram-backend)"
PERF_DIR="$(pwd -W 2>/dev/null || pwd)/perf"
OUT=perf/official-acceptance.txt
CMD="vegeta attack -targets=/perf/targets-mixed.txt -rate=$RPS -duration=60s -timeout=5s -workers=64"
vegeta() { MSYS_NO_PATHCONV=1 docker run --rm --network "$NET" -v "$PERF_DIR:/perf" peterevans/vegeta sh -c "$1"; }
mem() { docker stats --no-stream --format '{{.MemUsage}}' tram-backend; }

echo "Прогрев JIT: 1000 RPS × 20 с (в отчёт не входит)"
vegeta "vegeta attack -targets=/perf/targets-mixed.txt -rate=1000 -duration=20s -timeout=5s | vegeta report" > /dev/null
sleep 5
MEM_BEFORE="$(mem)"
( end=$((SECONDS + 58)); while [ $SECONDS -lt $end ]; do docker stats --no-stream --format '{{.CPUPerc}}' tram-backend; done ) > perf/.cpu.tmp &
# сырые результаты (~1 ГБ) — внутри контейнера клиента, наружу только отчёты: запись на диск хоста тормозила клиент
vegeta "$CMD > /tmp/r.bin && vegeta report /tmp/r.bin > /perf/.acc.txt && vegeta report -type=json /tmp/r.bin > /perf/.acc.json"
wait
MEM_AFTER="$(mem)"
CPU="$(awk '{gsub("%",""); c=$1+0; s+=c; n++; if (c>m) m=c} END {printf "среднее %.0f%% (%.0f%% от 2 vCPU), пик %.0f%%, замеров %d", s/n, s/n/2, m, n}' perf/.cpu.tmp)"
CODES="$(grep -o '"status_codes":{[^}]*}' perf/.acc.json | sed 's/"status_codes"://')"
T0="$(grep -o '"0":[0-9]*' perf/.acc.json | cut -d: -f2 || true)"; T0="${T0:-0}"
{
  echo "ПРИЁМОЧНЫЙ НАГРУЗОЧНЫЙ ТЕСТ — «Пантограф», бэкенд"
  echo "Дата (UTC):      $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "Коммит:          $(git rev-parse HEAD)$(git diff --quiet HEAD -- . ':!perf' || echo ' (+ незакоммиченные изменения)')"
  echo "Образ backend:   $(docker inspect -f '{{.Image}}' tram-backend)"
  echo "Образ postgres:  $(docker inspect -f '{{.Image}}' tram-postgres)"
  echo "Лимиты backend:  $(docker inspect -f '{{.HostConfig.NanoCpus}}' tram-backend | awk '{print $1/1e9}') vCPU / $(docker inspect -f '{{.HostConfig.Memory}}' tram-backend | awk '{print $1/1024/1024/1024}') ГБ RAM, $(docker inspect -f '{{.HostConfig.Memory}} {{.HostConfig.MemorySwap}}' tram-backend | awk '{ if ($2 == $1) print "swap запрещён (memswap = RAM)"; else print "ВНИМАНИЕ: swap разрешён, memswap =", $2/1024/1024/1024, "ГБ" }')"
  echo "Прогон модели:   $(curl -s localhost:8080/actuator/health | grep -o '"shortTermRun":"[^"]*"')"
  echo "Нагрузка:        $RPS RPS фиксированной частоты, 60 с (открытая модель: частота не снижается, если сервис тормозит);"
  echo "                 клиент vegeta в той же docker-сети, от 64 соединений"
  echo "Сценарий:        perf/targets-mixed.txt — 3000 запросов, 2199 уникальных URL: 45% /api/dashboard, 25% /api/forecast"
  echo "                 (день/месяц/год), 15% /api/forecast?stop=, 10% /api/attention, 5% /api/export?format=csv"
  echo "Команда:         docker run --rm --network $NET -v \$PWD/perf:/perf peterevans/vegeta sh -c \"$CMD | vegeta report\""
  echo "Стенд:           Docker Desktop (WSL2) на ноутбуке, клиент нагрузки делит машину с сервисом"
  echo
  echo "== Результат vegeta"
  sed -n '1,/^$/p' perf/.acc.txt
  echo "== Сводка"
  echo "HTTP-коды:       $CODES   (код 0 — запрос не завершён: таймаут 5 с или обрыв)"
  echo "Не завершено:    $T0 (таймауты и обрывы)"
  echo "Ошибки:          $(sed -n '/^Error Set:/,$p' perf/.acc.txt | tail -n +2 | sort | uniq -c | head -5 | tr '
' ' ')"
  echo "CPU backend:     $CPU (docker stats, 100% = одно ядро)"
  echo "RAM backend:     до теста $MEM_BEFORE → после теста $MEM_AFTER"
  echo
  echo "== docker compose ps"
  docker compose ps --format 'table {{.Name}}\t{{.Status}}'
} | tee "$OUT"
rm -f perf/.acc.bin perf/.acc.json perf/.acc.txt perf/.cpu.tmp
