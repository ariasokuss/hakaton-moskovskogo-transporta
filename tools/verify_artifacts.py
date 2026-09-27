"""Сверка артефакта ML-модели с тем, что хранит и отдаёт сервис.

    python tools/verify_artifacts.py [--base http://localhost:8080]

Проверяет (сервис должен быть запущен: docker compose up -d):
  1. data/forecast/forecast_hourly.csv: 14 640 строк сетки сабмита, нет отрицательных прогнозов;
  2. БД сервиса (core.forecast_hourly, прогон ml-<версия>): те же ключи route/date/hour и те же дробные значения,
     max |Δ| < 1e-9 (маршрут 5 в БД не хранится — его нет в справочнике, в сабмите он нулевой);
  3. выгрузка GET /api/export?format=submission: те же 14 640 ключей, значения = half-up(pred) из ML-файла
     и совпадают с data/forecast/submission_latest.csv (тот, что загружен на платформу).
Только стандартная библиотека Python 3.9+. Код возврата 0 — всё сошлось.
"""
import argparse
import csv
import io
import math
import subprocess
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TOL = 1e-9


def read_csv(text):
    return list(csv.DictReader(io.StringIO(text.lstrip("﻿")), delimiter=";"))


def half_up(v):
    return int(math.floor(v + 0.5))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8080")
    ap.add_argument("--db-container", default="tram-postgres")
    a = ap.parse_args()
    fails = []

    def check(ok, msg):
        print(("PASS  " if ok else "FAIL  ") + msg)
        if not ok:
            fails.append(msg)

    ml = read_csv((ROOT / "data/forecast/forecast_hourly.csv").read_text(encoding="utf-8"))
    version = ml[0]["model_version"]
    pred = {(int(r["route"]), r["date"], int(r["hour"])): float(r["pred"]) for r in ml}
    print(f"ML-артефакт: data/forecast/forecast_hourly.csv, model_version={version}")
    check(len(ml) == 14640 and len(pred) == 14640, f"ML-файл: {len(ml)} строк, {len(pred)} уникальных ключей (ожидается 14 640)")
    check(min(pred.values()) >= 0, f"ML-файл: минимальный прогноз {min(pred.values()):.6f} ≥ 0")

    # 2. БД: дробные значения прогона без округления (колонка double precision, миграция V7)
    sql = ("SELECT h.route_id, h.forecast_date, h.hour, h.prediction FROM core.forecast_hourly h "
           "JOIN core.forecast_run r USING (run_id) "
           f"WHERE r.model_version = 'ml-{version}' ORDER BY 1, 2, 3")
    out = subprocess.run(["docker", "exec", a.db_container, "psql", "-U", "tram", "-d", "tram", "-At", "-F", ";",
                          "-c", "SET extra_float_digits = 3; " + sql],
                         capture_output=True, text=True, encoding="utf-8")
    if out.returncode != 0:
        check(False, f"БД: запрос не выполнен: {out.stderr.strip()}")
    else:
        db = {}
        for line in out.stdout.splitlines():
            if line.count(";") != 3:
                continue
            r, d, h, v = line.split(";")
            db[(int(r), d, int(h))] = float(v)
        ml_no5 = {k: v for k, v in pred.items() if k[0] != 5}
        check(len(db) > 0 and db.keys() == ml_no5.keys(),
              f"БД: прогон ml-{version}, {len(db)} ключей = ключи ML-файла без маршрута 5 ({len(ml_no5)})")
        if db.keys() == ml_no5.keys():
            diff = max(abs(db[k] - ml_no5[k]) for k in db)
            check(diff < TOL, f"БД: max |прогноз БД − прогноз ML| = {diff:.3e} (порог {TOL:g})")
        check(all(v == 0 for k, v in pred.items() if k[0] == 5), "маршрут 5 в ML-файле нулевой (в БД не хранится)")

    # 3. Выгрузка сервиса в формате сабмита
    url = f"{a.base}/api/export?format=submission&from=2025-11-01&to=2025-12-31"
    try:
        svc = read_csv(urllib.request.urlopen(url, timeout=30).read().decode("utf-8"))
    except Exception as e:
        check(False, f"выгрузка {url}: {e}")
        svc = []
    if svc:
        s = {(int(r["route"]), r["date"], int(r["hour"])): int(r["prediction"]) for r in svc}
        check(len(svc) == 14640 and s.keys() == pred.keys(), f"выгрузка сервиса: {len(svc)} строк, ключи = ключи ML-файла")
        check(min(s.values()) >= 0, "выгрузка сервиса: нет отрицательных прогнозов")
        bad = sum(1 for k in s if s[k] != half_up(pred[k]))
        check(bad == 0, f"выгрузка сервиса = half-up(pred ML): расхождений {bad}")
        sub_path = ROOT / "data/forecast/submission_latest.csv"
        if sub_path.exists():
            sub = {(int(r["route"]), r["date"], int(r["hour"])): int(float(r["prediction"]))
                   for r in read_csv(sub_path.read_text(encoding="utf-8"))}
            bad = sum(1 for k in s if s[k] != sub.get(k))
            check(sub.keys() == s.keys() and bad == 0,
                  f"выгрузка сервиса = submission_latest.csv (сабмит на платформе): расхождений {bad}")

    print("\nИТОГ:", "PASS" if not fails else f"FAIL ({len(fails)})")
    return 0 if not fails else 1


if __name__ == "__main__":
    sys.exit(main())
