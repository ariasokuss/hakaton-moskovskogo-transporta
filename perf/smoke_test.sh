#!/usr/bin/env bash
# Smoke-тест поднятого решения: все точки входа API и фронтенд.
# Для каждой — ожидаемый HTTP-код, непустой ответ, обязательные поля JSON, для выгрузок — наличие данных.
#
#   bash perf/smoke_test.sh [http://localhost:8080] [http://localhost:3000]
#
# Нужны только bash и curl (Linux, macOS, Git Bash на Windows). Код возврата 0 — всё PASS.
set -uo pipefail
API="${1:-http://localhost:8080}"
WEB="${2:-http://localhost:3000}"
DATE=2025-11-08
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
pass=0; fail=0

# check <имя> <ожидаемый код> <url> [поля JSON или маркеры, которые должны быть в ответе]
check() {
  local name="$1" want="$2" url="$3"; shift 3
  local body="$TMP/body" code
  code=$(curl -s -o "$body" -w '%{http_code}' --max-time 30 "$url")
  local size; size=$(wc -c < "$body" | tr -d ' ')
  local miss=""
  for f in "$@"; do grep -q -- "$f" "$body" || miss="$miss $f"; done
  if [ "$code" = "$want" ] && [ "$size" -gt 0 ] && [ -z "$miss" ]; then
    printf 'PASS  %-34s %s  %8s B\n' "$name" "$code" "$size"; pass=$((pass + 1))
  else
    printf 'FAIL  %-34s код %s (ожидался %s), %s B, нет:%s\n' "$name" "$code" "$want" "$size" "${miss:- —}"; fail=$((fail + 1))
  fi
}

# rows <имя> <url> <минимум строк данных> — выгрузка CSV действительно содержит данные
rows() {
  local name="$1" url="$2" min="$3" n
  n=$(( $(curl -s --max-time 60 "$url" | wc -l) - 1 ))
  if [ "$n" -ge "$min" ]; then printf 'PASS  %-34s %s строк данных (≥ %s)\n' "$name" "$n" "$min"; pass=$((pass + 1))
  else printf 'FAIL  %-34s %s строк данных (ожидалось ≥ %s)\n' "$name" "$n" "$min"; fail=$((fail + 1)); fi
}

echo "API: $API · фронтенд: $WEB · $(date -u +%Y-%m-%dT%H:%M:%SZ)"
check "GET /"                         200 "$API/"                        '"endpoints"'
check "GET /actuator/health"          200 "$API/actuator/health"         '"status":"UP"' '"forecastData"' '"shortTermRun"'
check "GET /api/meta"                 200 "$API/api/meta"                '"defaultDate"' '"shortTerm"' '"year"'
check "GET /api/routes"               200 "$API/api/routes"              '"shortName"' '"color"' '"serviceHourStart"'
check "GET /api/stops"                200 "$API/api/stops"               '"stop"' '"share"' '"lat"'
check "GET /api/geometry"             200 "$API/api/geometry"            '"FeatureCollection"' '"MultiLineString"' '"OpenStreetMap'
check "GET /api/dashboard"            200 "$API/api/dashboard?date=$DATE" '"series"' '"attention"' '"network"' '"external"' '"regimes"'
check "GET /api/forecast day"         200 "$API/api/forecast?route=17&horizon=day&date=$DATE"    '"points"' '"forecastTotal"' 'T08:00'
check "GET /api/forecast month"       200 "$API/api/forecast?route=17&horizon=month&date=$DATE"  '"points"' '"2025-11-30"'
check "GET /api/forecast year"        200 "$API/api/forecast?route=17&horizon=year&date=$DATE"   '"points"' '"2026-10"'
check "GET /api/forecast interval"    200 "$API/api/forecast?from=2025-11-03&to=2025-11-09&granularity=day" '"points"' '"2025-11-09"'
# остановка: первая из справочника, вместе с её маршрутом; имя кодируется побайтно (кириллица, пробелы)
urlencode() { local LC_ALL=C s="$1" out="" c i; for ((i = 0; i < ${#s}; i++)); do c="${s:i:1}"
  case "$c" in [a-zA-Z0-9.~_-]) out+="$c" ;; *) out+=$(printf '%%%02X' "'$c") ;; esac; done; printf '%s' "$out"; }
FIRST=$(curl -s "$API/api/stops" | grep -o '"routeId":[0-9]*,"stop":"[^"]*"' | head -1)
STOP_ROUTE=$(echo "$FIRST" | grep -o '"routeId":[0-9]*' | cut -d: -f2)
STOP=$(echo "$FIRST" | cut -d'"' -f6)
check "GET /api/forecast stop"        200 "$API/api/forecast?route=$STOP_ROUTE&stop=$(urlencode "$STOP")&horizon=day&date=$DATE" '"points"' ' · '
check "GET /api/attention"            200 "$API/api/attention?date=$DATE&kWeather=1.2"           '"direction"' '"deviationPct"'
check "GET /api/coefficients"         200 "$API/api/coefficients"        '"model_version"'
check "GET /api/external"             200 "$API/api/external?date=$DATE" '"sources"' '"url"' '"context"'
check "GET /api/export csv"           200 "$API/api/export?format=csv&from=2025-11-01&to=2025-11-30&granularity=day" 'маршрут;период;прогноз'
check "GET /api/report pdf (день)"       200 "$API/api/report?horizon=day&date=$DATE"   '%PDF-'
check "GET /api/report pdf (месяц, 17)"  200 "$API/api/report?horizon=month&date=$DATE&route=17" '%PDF-'
check "GET /api/external/traffic"       200 "$API/api/external/traffic?date=2025-11-18" '"posts"' '"byRoute"' '"spots"'
check "GET /api/export xlsx"          200 "$API/api/export?format=xlsx&from=2025-11-01&to=2025-11-30&granularity=day" 'PK'
rows  "export csv: данные"                "$API/api/export?format=csv&from=2025-11-01&to=2025-11-30&granularity=day" 270
rows  "export submission: 14 640 строк"   "$API/api/export?format=submission&from=2025-11-01&to=2025-12-31" 14640
# ошибки — RFC 9457 с русским текстом, без стектрейса
check "ошибка: неизвестный горизонт"  400 "$API/api/forecast?horizon=week" '"title"' '"detail"' 'горизонт'
check "ошибка: дата вне прогноза"     404 "$API/api/dashboard?date=2027-01-01" '"title"' 'прогноза нет'
# фронтенд и прокси API через nginx
check "фронтенд /"                    200 "$WEB/"                        '<div id="root">'
check "фронтенд → /api/meta (прокси)" 200 "$WEB/api/meta"                '"defaultDate"'

echo; echo "ИТОГ: PASS $pass, FAIL $fail"
[ "$fail" -eq 0 ]
