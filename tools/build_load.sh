#!/usr/bin/env bash
# Потоковая агрегация сырых валидаций (train.csv + test.csv, ~10 ГБ) до маршрут × дата × час.
#
# Считает две величины за один проход:
#   boardings — validation_result = 1 (цель; сверяется с labels/ организаторов);
#   load      — коды из LOAD_CODES (по умолчанию 1 и 90: успешные + пересадки).
#               Пересадка — реальный пассажир в салоне, нагрузка на вагон.
#
# Время — tran_date_time (input_date_time содержит битые даты), маршрут — ngpt_route.
# Маршруты вне целевого набора отбрасываются.
#
# Использование: tools/build_load.sh <каталог датасета> > data/load/load_hourly.csv
set -euo pipefail
DIR="${1:-ИИ-прогноз пассажиропотока/dataset}"
LOAD_CODES="${LOAD_CODES:-1,90}"
ROUTES="1,7,11,12,17,25,26,28,50"

echo "route;date;hour;boardings;load"
for f in "$DIR/train.csv" "$DIR/test.csv"; do tail -n +2 "$f"; done | LC_ALL=C awk -F';' -v codes="$LOAD_CODES" -v routes="$ROUTES" '
BEGIN { n = split(codes, c, ","); for (i = 1; i <= n; i++) L[c[i]] = 1
        m = split(routes, r, ","); for (i = 1; i <= m; i++) R[r[i]] = 1 }
{
  split($12, rt, " "); route = rt[1]
  if (!(route in R)) next
  key = route ";" substr($3, 1, 10) ";" (substr($3, 12, 2) + 0)
  if ($7 == "1") B[key]++
  if ($7 in L) { LD[key]++; K[key] = 1 }
}
END { for (k in K) print k ";" (B[k] + 0) ";" LD[k] }' | sort -t';' -k1,1n -k2,2 -k3,3n
