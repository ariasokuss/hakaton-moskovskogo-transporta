#!/usr/bin/env bash
# Проверка запуска с нуля: без старого Docker volume, с пересборкой образов — как у жюри на чистом клоне.
# УДАЛЯЕТ том БД этого проекта (docker compose down -v). Лог — perf/clean-start.txt.
#
#   bash perf/clean_start.sh
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=perf/clean-start.txt
{
  echo "Дата: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "Коммит: $(git rev-parse HEAD)$(git diff --quiet HEAD -- . ':!perf' || echo ' (+ незакоммиченные изменения)')"
  echo "Команды: docker compose down -v && docker compose up -d --build"
  echo
  docker compose down -v 2>&1 | tail -3
  start=$SECONDS
  docker compose up -d --build 2>&1 | grep -E "Built|Created|Started|Healthy|rror" || true
  until curl -sf http://localhost:8080/actuator/health | grep -q '"status":"UP"'; do
    [ $((SECONDS - start)) -gt 600 ] && { echo "FAIL: health не UP за 10 минут"; exit 1; }
    sleep 2
  done
  echo; echo "Готов за $((SECONDS - start)) с (сборка образов — из кэша Docker, если он есть; миграции; загрузка данных)"
  echo; echo "== docker compose ps"; docker compose ps --format 'table {{.Name}}\t{{.Image}}\t{{.Status}}'
  echo; echo "== миграции и загрузка (лог бэкенда)"
  docker logs tram-backend 2>&1 | grep -E "Successfully applied|факт загружен|Импортирован|Внешний источник|Годовой прогон|ERROR" | sed -E 's/^.*(INFO|WARN|ERROR) [0-9]+ --- \[[^]]*\] \[[^]]*\] //'
  echo; echo "== GET /actuator/health"; curl -s http://localhost:8080/actuator/health; echo
  echo; echo "PASS"
} 2>&1 | tee "$OUT"
