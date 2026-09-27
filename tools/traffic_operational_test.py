"""Проверка трафика как оперативной поправки (протокол — docs/traffic-operational-test.md).

    python tools/traffic_operational_test.py

Только стандартная библиотека. Печатает таблицу и итоговую строку для документа.
"""
import csv
import statistics
from collections import defaultdict
from datetime import date, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
EXT = ROOT / "ml/external_data"
EVAL_FROM, EVAL_TO = date(2025, 3, 1), date(2025, 10, 31)
MIN_BUCKET = 30
BUCKETS = [("0–4", 0, 4), ("5", 5, 5), ("6–7", 6, 7), ("8–10", 8, 10)]


def bucket(s):
    return next(name for name, lo, hi in BUCKETS if lo <= s <= hi)


def wape_score(pairs):
    y = sum(a for a, _ in pairs)
    return max(0.0, 1 - sum(abs(a - p) for a, p in pairs) / y) if y else float("nan")


# посадки: (route, date, hour) -> boardings
fact = {}
for r in csv.DictReader(open(ROOT / "data/load/load_hourly.csv", encoding="utf-8"), delimiter=";"):
    fact[(int(r["route"]), date.fromisoformat(r["date"]), int(r["hour"]))] = int(r["boardings"])
routes = sorted({k[0] for k in fact})

# баллы ЦОДД, только факт; несколько постов в час — последний по post_id
scores = {}
for f in ["DtOperativno_traffic_scores_2025.csv", "traffic_scores_2025.csv"]:
    for r in sorted(csv.DictReader(open(EXT / f, encoding="utf-8"), delimiter=";"), key=lambda r: int(r["post_id"])):
        if r["kind"] == "fact" and r["hour"]:
            scores[(date.fromisoformat(r["date"]), int(r["hour"]))] = int(r["score"])


def baseline(route, d, h):
    """Медиана того же часа по 8 последним одноимённым дням недели строго до даты."""
    vals = [fact.get((route, d - timedelta(weeks=w), h)) for w in range(1, 9)]
    vals = [v for v in vals if v is not None]
    return statistics.median(vals) if len(vals) >= 4 else None


# все маршрут-часы с баллом: (date, hour, route, fact, base, bucket)
cells = []
for (d, h), s in sorted(scores.items()):
    for rt in routes:
        y, b = fact.get((rt, d, h)), baseline(rt, d, h)
        if y is not None and b is not None and b > 0:
            cells.append((d, h, rt, y, b, bucket(s)))

control, with_traffic, by_bucket = [], [], defaultdict(lambda: ([], []))
for d in sorted({c[0] for c in cells}):
    if not (EVAL_FROM <= d <= EVAL_TO):
        continue
    past = [c for c in cells if c[0] < d]                       # строго до даты
    k_all = sum(c[3] for c in past) / sum(c[4] for c in past) if past else 1.0
    k_b = {}
    for name, _, _ in BUCKETS:
        pb = [c for c in past if c[5] == name]
        k_b[name] = sum(c[3] for c in pb) / sum(c[4] for c in pb) if len(pb) >= MIN_BUCKET else k_all
    for c in (c for c in cells if c[0] == d):
        y, b, bk = c[3], c[4], c[5]
        control.append((y, b * k_all))
        with_traffic.append((y, b * k_b[bk]))
        by_bucket[bk][0].append((y, b * k_all))
        by_bucket[bk][1].append((y, b * k_b[bk]))

s0, s1 = wape_score(control), wape_score(with_traffic)
n_hours = len({(c[0], c[1]) for c in cells if EVAL_FROM <= c[0] <= EVAL_TO})
print(f"Оценка: {EVAL_FROM}..{EVAL_TO}, часов с баллом {n_hours}, маршрут-часов {len(control)}")
print(f"{'корзина':>8} {'n':>6} {'контроль':>9} {'с трафиком':>11} {'Δ, п.п.':>8}")
for name, _, _ in BUCKETS:
    c0, c1 = by_bucket[name]
    if c0:
        print(f"{name:>8} {len(c0):>6} {wape_score(c0):>9.4f} {wape_score(c1):>11.4f} {100 * (wape_score(c1) - wape_score(c0)):>+8.2f}")
print(f"{'ВСЕ':>8} {len(control):>6} {s0:>9.4f} {s1:>11.4f} {100 * (s1 - s0):>+8.2f}")
# K по корзинам на всей истории до 01.11 — то, что применяет сервис в ноябре–декабре
hist = [c for c in cells if c[0] <= EVAL_TO]
k_all = sum(c[3] for c in hist) / sum(c[4] for c in hist)
print("\nK на 31.10 (для сервиса, ноя–дек):", {name: round(sum(c[3] for c in hist if c[5] == name) / sum(c[4] for c in hist if c[5] == name) / k_all, 4)
                                            for name, _, _ in BUCKETS if sum(1 for c in hist if c[5] == name) >= MIN_BUCKET},
      "(относительно K_all)")
print(f"\nИТОГ: Δ = {100 * (s1 - s0):+.2f} п.п. ({s0:.4f} → {s1:.4f}) →",
      "ПОДТВЕРЖДЁН для оперативного применения" if s1 > s0 else "НЕ подтверждён")
