"""Загрузка весов финальной модели «Пантограф» вне ноутбука и проверка контракта.

    python ml/load_models.py

Что проверяется:
  1. все 9 бустеров LightGBM из ml/artifacts/ (горизонты 3/14/28 дней × seed 42/43/44) загружаются;
  2. признаки каждой модели совпадают с контрактом ml_contract.json (порядок и названия);
  3. артефакт прогноза forecast_hourly.csv — полная сетка 14 640 строк той же версии модели.

Как устроен прогноз (подробно — ml/README.md):
  ML = профиль × clip(среднее 9 моделей, 0.3, 2.0) → привязка уровня к профилю → смесь с профилем по горизонту
  → поправки (календарь, погода, режимы маршрутов, сбои) → сезонный рост уровня × SEASON_GROWTH.
Признаки считаются в точке прогноза функцией origin_frame() ноутбука ml/pantograph_best_colab.ipynb.
"""
import csv
import json
import sys
from pathlib import Path

import lightgbm as lgb

ART = Path(__file__).resolve().parent / "artifacts"


def load_models(art=ART):
    contract = json.loads((art / "ml_contract.json").read_text(encoding="utf-8"))
    models = [lgb.Booster(model_file=str(art / name)) for name in contract["models"]]
    return contract, models


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")   # консоль Windows (cp1251)
    contract, models = load_models()
    ok = True
    print(f"версия модели: {contract['model_version']} | моделей: {len(models)} | признаков: {len(contract['features'])}")
    for name, m in zip(contract["models"], models):
        same = m.feature_name() == contract["features"]
        ok &= same
        print(f"  {'OK ' if same else 'ERR'} {name}: деревьев {m.num_trees()}, признаки {'совпадают' if same else 'НЕ совпадают'} с контрактом")
    with open(ART / "forecast_hourly.csv", encoding="utf-8") as f:
        rows = list(csv.DictReader(f, delimiter=";"))
    versions = {r["model_version"] for r in rows}
    grid_ok = len(rows) == 14640 and len({(r["route"], r["date"], r["hour"]) for r in rows}) == 14640
    ok &= grid_ok
    print(f"  {'OK ' if grid_ok else 'ERR'} forecast_hourly.csv: {len(rows)} строк, версия {', '.join(sorted(versions))}")
    if "season_growth" in contract:
        print(f"  сезонный рост уровня: × {contract['season_growth']['value']}")
    print("ГОТОВО" if ok else "ЕСТЬ ОШИБКИ")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
