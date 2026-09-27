"""Сборщик текущего балла пробок Яндекса по Москве (0–10) -> yandex_traffic_live.csv.

Источник: https://export.yandex.ru/bar/reginfo.xml?region=213  (публичный XML, без ключа)
Используется ТОЛЬКО как поправочный фактор / онлайн-признак сервиса, не как target.
Истории за 2025 год у эндпоинта нет, поэтому история берётся из @DtOperativno (parse_deptrans_tg.py);
этот сборщик накапливает ряд вперёд и кормит поправку K_traffic в сервисе.

Запуск:
    python external_data/yandex_traffic_collector.py once            # один замер
    python external_data/yandex_traffic_collector.py loop --every 600 # замер каждые 10 минут (Ctrl+C — стоп)
В Docker — отдельный sidecar или cron: */10 * * * * python yandex_traffic_collector.py once
"""
import argparse
import csv
import time
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path

URL = "https://export.yandex.ru/bar/reginfo.xml?region=213"
OUT = Path(__file__).resolve().parent / "yandex_traffic_live.csv"
FIELDS = ["collected_utc", "yandex_timestamp", "yandex_time_msk", "level", "icon", "hint", "tend"]


def fetch():
    req = urllib.request.Request(URL, headers={"User-Agent": "Mozilla/5.0"})
    root = ET.fromstring(urllib.request.urlopen(req, timeout=20).read())
    r = root.find("./traffic/region")
    if r is None:
        raise RuntimeError("в ответе нет блока traffic/region")
    return {
        "collected_utc": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "yandex_timestamp": r.findtext("timestamp"),
        "yandex_time_msk": r.findtext("time"),
        "level": r.findtext("level"),
        "icon": r.findtext("icon"),
        "hint": r.findtext("hint[@lang='ru']"),
        "tend": r.findtext("tend"),
    }


def save(row):
    new = not OUT.exists()
    with OUT.open("a", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, FIELDS, delimiter=";")
        if new:
            w.writeheader()
        w.writerow(row)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["once", "loop"])
    ap.add_argument("--every", type=int, default=600, help="период опроса, сек")
    a = ap.parse_args()
    while True:
        try:
            row = fetch()
            save(row)
            print(row["collected_utc"], "level", row["level"], row["hint"], flush=True)
        except Exception as e:  # сеть/формат — пишем и продолжаем
            print("error:", e, flush=True)
        if a.cmd == "once":
            break
        time.sleep(a.every)
