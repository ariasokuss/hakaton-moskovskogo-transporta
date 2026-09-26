#!/usr/bin/env bash
# Подключение артефактов ML-модели к сервису «Пантограф».
#
# Источник — архив service_artifacts.zip, который ноутбук
# mostrans_handoff/mostrans/baseline_colab.ipynb сохраняет в MyDrive/mostrans/submissions/
# и скачивает в конце прогона (или папка submissions/artifacts/ с теми же файлами).
#
# Кладёт в data/forecast/ (смонтирована в контейнер бэкенда read-only):
#   forecast_hourly.csv        — почасовой прогноз ноя–дек без округления (route;date;hour;pred;model_version)
#   forecast_year_monthly.csv  — горизонт «год» помесячно (month;route;boardings;low;high;source;model_version)
#   coefficients.json          — коэффициенты поправок, измеренные эффекты, ссылки на источники
#   ml_contract.json           — контракт ML-модели: признаки, seed, версии библиотек
#   submission_latest.csv      — сабмит в формате организаторов (запасной вариант импорта)
# и в ml/artifacts/ — веса 9 моделей LightGBM (lgbm_h*_s*.txt) и копию ml_contract.json.
#
# После копирования: docker compose restart backend — новый прогноз импортируется новым прогоном,
# годовой прогон пересчитается под помесячный прогноз ML.
#
# Использование: tools/sync_ml_artifacts.sh <service_artifacts.zip | папка с артефактами>
set -euo pipefail
SRC="${1:?укажите service_artifacts.zip или папку с артефактами}"
DST="$(cd "$(dirname "$0")/.." && pwd)/data/forecast"
FILES="forecast_hourly.csv forecast_year_monthly.csv coefficients.json ml_contract.json submission_latest.csv"
mkdir -p "$DST"

if [ -f "$SRC" ]; then
  TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
  unzip -oq "$SRC" -d "$TMP"
  SRC="$TMP"
fi

for f in $FILES; do
  if [ -f "$SRC/$f" ]; then
    cp "$SRC/$f" "$DST/$f"; echo "✔ $f"
  else
    echo "— $f нет в источнике (пропущен)"
  fi
done
# Веса моделей LightGBM — обязательный артефакт сдачи (п. 1): в ml/artifacts/, рядом с контрактом.
MODELS="$(cd "$(dirname "$0")/.." && pwd)/ml/artifacts"
mkdir -p "$MODELS"
n=0
for m in "$SRC"/lgbm_*.txt; do
  [ -f "$m" ] || continue
  cp "$m" "$MODELS/"; n=$((n + 1))
done
[ -f "$SRC/ml_contract.json" ] && cp "$SRC/ml_contract.json" "$MODELS/"
if [ "$n" -gt 0 ]; then echo "✔ моделей LightGBM: $n → ml/artifacts/"; else echo "— весов lgbm_*.txt в источнике нет (ml/artifacts/ не обновлён)"; fi

[ -f "$DST/forecast_hourly.csv" ] || { echo "✖ нет forecast_hourly.csv — сервис возьмёт submission_latest.csv"; exit 0; }
echo "Версия модели: $(sed -n 2p "$DST/forecast_hourly.csv" | awk -F';' '{print $NF}')"
echo "Дальше: docker compose restart backend"
