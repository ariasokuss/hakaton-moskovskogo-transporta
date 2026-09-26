"""Смешанный сценарий нагрузки для vegeta: разные даты, коэффициенты, горизонты, маршруты и остановки.

Один URL под нагрузкой — это 100% попаданий в кэш главного экрана. Здесь запросы разные,
поэтому в замере есть и холодный путь (сборка ответа), и тёплый (готовые байты).

    python perf/gen_targets.py http://backend:8080 stops.json > perf/targets-mixed.txt

stops.json — ответ GET /api/stops (для запросов прогноза по остановке).
"""
import json
import random
import sys
from datetime import date, timedelta

random.seed(42)
base = sys.argv[1].rstrip('/')
stops = json.load(open(sys.argv[2], encoding='utf-8')) if len(sys.argv) > 2 else []
routes = [1, 7, 11, 12, 17, 25, 26, 28, 50]
days = [date(2025, 11, 1) + timedelta(days=i) for i in range(61)]
ks = [1, 1, 1, 0.8, 0.9, 1.1, 1.2, 1.5]


def k():
    parts = [f"&{name}={random.choice(ks)}" for name in ("kWeather", "kEvent", "kManual") if random.random() < 0.5]
    return ''.join(p for p in parts if not p.endswith('=1'))


lines = []
for _ in range(3000):
    r = random.random()
    d = random.choice(days)
    if r < 0.45:     # главный экран — самый частый запрос
        lines.append(f"GET {base}/api/dashboard?date={d}{k()}")
    elif r < 0.70:   # прогноз по параметрам ТЗ: маршрут × горизонт
        h = random.choice(["day", "month", "year"])
        lines.append(f"GET {base}/api/forecast?route={random.choice(routes)}&horizon={h}&date={d}{k()}")
    elif r < 0.85 and stops:   # остановка
        s = random.choice(stops)
        from urllib.parse import quote
        lines.append(f"GET {base}/api/forecast?route={s['routeId']}&stop={quote(s['stop'])}&horizon=day&date={d}")
    elif r < 0.95:
        lines.append(f"GET {base}/api/attention?date={d}{k()}")
    else:            # выгрузка месяца в CSV
        lines.append(f"GET {base}/api/export?format=csv&from={d.replace(day=1)}&to={d.replace(day=28)}&granularity=day")
print('\n'.join(lines))
