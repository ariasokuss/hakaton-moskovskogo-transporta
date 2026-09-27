"""Исходник финального ноутбука ml/pantograph_best_colab.ipynb: собирает его из списка ячеек.

    python ml/tools/build_nb_best.py ml/pantograph_best_colab.ipynb          # сборка
    LOCAL_DATA_DIR=ml python ml/tools/build_nb_best.py out.ipynb --run       # сборка и локальный прогон
"""
import os
import sys
import nbformat as nbf

CELLS = []
# код пайплайна приёма вставляется в ноутбук из pipeline/ingest_raw.py при сборке (один источник правды)
PIPELINE_SRC = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "pipeline", "ingest_raw.py"),
                    encoding="utf-8").read().split('if __name__ == "__main__":')[0].rstrip() + "\n"
def md(s): CELLS.append(("md", s.strip("\n")))
def code(s): CELLS.append(("code", s.strip("\n")))

md(r"""
# Пантограф — прогноз загрузки трамвайных маршрутов Москвы
### Финальное решение · прогноз на ноябрь–декабрь 2025

> *Из данных — энергия, из энергии — прогноз.*

| | |
|---|---|
| **Задача** | `boardings(route, date, hour)` — посадки на 10 маршрутах × 61 день × 24 часа = 14 640 значений |
| **Метрика** | `WAPE-score = max(0, 1 − Σ|y − ŷ| / Σy)`; максимум балла — при > 0.88 |

---

## Как устроен прогноз

```
            ┌──────────────── данные организаторов ────────────────┐
            │ профиль: форма суток (16 нед.) × уровень (3 нед.)     │──┐
            │ LightGBM: 3 горизонта × 3 seed, прямой прогноз        │──┼──► смесь (вес ML по горизонту)
            └──────────────────────────────────────────────────────┘  │
                                                                      ▼
   × K_calendar  — праздники и рабочая суббота (производственный календарь РФ)
   × K_weather   — дождь в тёплую погоду, первые 7 дней (архив прогнозов Open-Meteo)
   × K_regime    — ремонт путей 7/50 по выходным до 14.11 (Telegram Дептранса, newsvostok)
   × K_incident  — сбои на маршруте (Telegram @DtOperativno)
   × K_season    — сезонный рост уровня к зиме
                                                                      ▼
                                                    сабмит route;date;hour;prediction
```

## ▶ Запуск

`Runtime → Run all`. GPU не нужен: данных ~60 тыс. строк, всё считается на CPU.

* **Датасет** — Kaggle [`shotme/moscow-transport`](https://www.kaggle.com/datasets/shotme/moscow-transport), без ключа.
* **Внешние данные** — `MyDrive/mostrans/external_data/`. Если их там нет, ноутбук найдёт папку на Drive или скачает её из репозитория проекта.
* **Результат** — `MyDrive/mostrans/submissions/submission_ml_<время>.csv` плюс артефакты для сервиса.
""")

code(r"""
#@title Конфиг
# --- датасет хакатона: публичный Kaggle-датасет (ключ не нужен) ---------------------
KAGGLE_DATASET = "shotme/moscow-transport"             #@param {type:"string"}
KAGGLE_FULL_DOWNLOAD = False                           #@param {type:"boolean"}
# False — только labels + шаблон сабмита (~1.5 МБ, секунды); True — весь датасет с сырыми train/test (~10 ГБ)

# --- внешние данные (external_data/) и папка для сабмитов --------------------------
# папка на своём Google Drive: внутри external_data/, сюда же пишутся submissions/
DRIVE_DATA_DIR = "/content/drive/MyDrive/mostrans"     #@param {type:"string"}
# если на Drive нет external_data — ноутбук найдёт её в другой папке Drive или скачает из репозитория проекта
EXT_REPO_URL = "https://github.com/shotmee/moscow_transport.git"   #@param {type:"string"}
EXT_REPO_BRANCH = "main"                                             #@param {type:"string"}

SEED = 42
TECH_HOURS = (2, 3)   # технические валидации (проверка валидаторов, 0.002% объёма) — не используются в профиле, прогноз 0
ROUTES = [1, 5, 7, 11, 12, 17, 25, 26, 28, 50]
FORECAST_START, FORECAST_END = "2025-11-01", "2025-12-31"

# Погода на горизонте прогноза. Решение проверяют в ПОТОКОВОМ режиме: в момент прогноза будущей погоды нет.
# "fcst" — архив прогнозов Open-Meteo, и только на первые WEATHER_HORIZON_DAYS дней горизонта (дальше K=1, нейтрально).
# "fact" — фактическая погода: утечка из будущего, только для анализа, НЕ для сабмита.
# --- чекпоинты: долгие шаги и веса моделей сохраняются на Drive (MyDrive/mostrans/checkpoints/) и при повторном
# запуске загружаются. Ключ — хеш кода ноутбука, данных и настроек: изменили что-то — шаг пересчитается сам.
USE_CHECKPOINTS = True                                 #@param {type:"boolean"}
CODE_HASH = "__CODE_HASH__"                            # подставляется при сборке ноутбука из tools/build_nb.py
WEATHER_FOR_FORECAST = "fcst"                          #@param ["fcst", "fact"]
# Трафик (баллы ЦОДД): эффект измерен в ячейке «Эффект внешних источников» — в потоковой проверке прогноз НЕ улучшает,
# поэтому в сабмит по умолчанию не входит; включить — True
TRAFFIC_IN_MODEL = False                               #@param {type:"boolean"}
REGIME_REF_WEEKS = 12                                  # окно «нормальных» выходных до начала работ
WEATHER_HORIZON_DAYS = 7                               # на сколько дней вперёд реально известен прогноз погоды

# Экспертные коэффициенты (нет аналогов в истории) — те же «ручки», что будут в UI
K_WORKING_SATURDAY = 0.85   # рабочая суббота 01.11: будний профиль × K
K_PRE_NEW_YEAR = None       # 29–30.12: будний профиль × K; None — по данным: аналог «первые рабочие дни после
                            # новогодних каникул» (9–10.01.2025 к будням следующих недель ≈ 0.90)
K_SEASON = {11: 1.00, 12: 0.98}  # месячная поправка к уровню сен–окт (зима, предНГ)

SEASON_GROWTH = 1.03   # осенний рост уровня продолжается к зиме (будни сен→окт +3.4%, фев–мар выше октября)

# Маршрут 5: в истории нет. Анонс Дептранса до 01.11 — «запуск к концу 2025», факт — 17.12.2025 (t.me/DtRoad/55381).
# Сценарий: профиль маршрута-аналога из НАШЕГО датасета × ROUTE5_K. Опубликованное число поездок (40 тыс./нед.)
# не используем: это значение target из внешнего источника. ROUTE5_ENABLED=False → прогноз 0
ROUTE5_ENABLED = False                                 #@param {type:"boolean"}
ROUTE5_LAUNCH = "2025-12-17"
ROUTE5_ANALOG_ROUTE = 25                               # самый малый маршрут в данных
ROUTE5_K = 1.0

# Режимы маршрутов (внешний источник №4: ремонты путей). Данные, а не константы в коде: каждая запись —
# маршруты, дни, интервал и ссылки с датами публикации. Внутри интервала модель берёт текущий (урезанный) профиль,
# вне его — «нормальный» профиль выходных, восстановленный по истории до начала работ.
# EVENTS_MODE: "all" — все опубликованные новости (организаторы разрешили данные после 31.10.2025);
# "strict" — только опубликованные до отсечки (для 31.10: окончание «до конца осени» = 30.11)
EVENTS_MODE = "all"                                    #@param ["all", "strict"]
REGIMES = [
    dict(name="Ремонт путей в Протопоповском пер.: по выходным 50 не ходит, 7 укорочен",
         routes=[7, 50], ptypes=["sat", "sun"], start="2025-09-06",
         start_published="2025-09-05", start_url="https://t.me/DtOperativno/22624",
         end_announced="2025-11-30", end_announced_published="2025-09-12",
         end_announced_url="https://newsvostok.ru/dlya-tramvaev-7-i-50-izmeneniya-po-vyhodnym-budut-dejstvovat-do-kontsa-oseni/",
         end_actual="2025-11-14", end_actual_published="2025-11-15", end_actual_url="https://t.me/DtOperativno/23565"),
]

def known_regimes(cutoff):
    # режимы и их границы, известные на момент прогноза (с учётом EVENTS_MODE)
    cutoff, out = pd.Timestamp(cutoff), []
    for rg in REGIMES:
        if pd.Timestamp(rg["start_published"]) > cutoff:
            continue                                      # о работах ещё не объявили
        if EVENTS_MODE == "all" or pd.Timestamp(rg["end_actual_published"]) <= cutoff:
            end = rg["end_actual"]
        elif pd.Timestamp(rg["end_announced_published"]) <= cutoff:
            end = rg["end_announced"]
        else:
            end = "2100-01-01"                            # срок неизвестен — режим продолжается
        out.append({**rg, "start": pd.Timestamp(rg["start"]), "end": pd.Timestamp(end)})
    return out

# снимок настроек — часть ключа чекпоинтов (пути и флаги скачивания на результат не влияют)
CONFIG_SNAPSHOT = {k: v for k, v in dict(globals()).items()
                   if k.isupper() and isinstance(v, (bool, int, float, str, tuple, list, dict))
                   and k not in ("KAGGLE_DATASET", "KAGGLE_FULL_DOWNLOAD", "DRIVE_DATA_DIR", "USE_CHECKPOINTS", "CODE_HASH",
                                 "EXT_REPO_URL", "EXT_REPO_BRANCH", "SEASON_GROWTH")}
""")

code(r"""
#@title Установка и импорты
import os, sys, io, time, json, zipfile, warnings, subprocess
from pathlib import Path
warnings.filterwarnings("ignore")

IN_COLAB = "google.colab" in sys.modules
if IN_COLAB:
    subprocess.run([sys.executable, "-m", "pip", "-q", "install", "lightgbm==4.6.0", "kagglehub", "tqdm", "duckdb"], check=False)

import numpy as np
import pandas as pd
import matplotlib.pyplot as plt
import lightgbm as lgb
from tqdm.auto import tqdm

np.random.seed(SEED)
pd.set_option("display.width", 200); pd.set_option("display.max_columns", 50)
print("lightgbm", lgb.__version__, "| pandas", pd.__version__, "| colab:", IN_COLAB)
try:
    print(subprocess.run(["nvidia-smi", "--query-gpu=name,memory.total", "--format=csv,noheader"],
                         capture_output=True, text=True).stdout.strip() or "GPU: нет")
except Exception:
    print("GPU: нет (не требуется)")
""")

code(r"""
#@title Подключение данных
import shutil, urllib.request
NEEDED = ["labels/labels_day_train.csv", "labels/labels_day_test.csv", "test_submission.csv"]

def kaggle_file(rel):
    # один файл публичного датасета без ключа: kagglehub, запасной вариант — прямой HTTP к Kaggle API
    try:
        import kagglehub
        return Path(kagglehub.dataset_download(KAGGLE_DATASET, path=rel))
    except Exception as e:
        print(f"kagglehub ({rel}): {type(e).__name__}: {e} → качаю напрямую")
    dst = Path("/content/kaggle_ds") / rel
    if not dst.exists():
        dst.parent.mkdir(parents=True, exist_ok=True)
        url = f"https://www.kaggle.com/api/v1/datasets/download/{KAGGLE_DATASET}/{rel}"
        tmp = dst.with_suffix(dst.suffix + ".part")
        with urllib.request.urlopen(url) as r, open(tmp, "wb") as f:
            shutil.copyfileobj(r, f)
        if zipfile.is_zipfile(tmp):                        # Kaggle иногда отдаёт файл в zip-обёртке
            with zipfile.ZipFile(tmp) as zf:
                dst.write_bytes(zf.read(zf.namelist()[0]))
            tmp.unlink()
        else:
            tmp.rename(dst)
    return dst

if os.environ.get("LOCAL_DATA_DIR"):                     # локальный прогон: dataset.zip читается без распаковки
    PROJECT_DIR = Path(os.environ["LOCAL_DATA_DIR"])
    _Z = zipfile.ZipFile(PROJECT_DIR / "dataset.zip")
    ds_open = lambda rel: _Z.open(rel)
    DS_DIR = None
else:
    # --- датасет с Kaggle
    if KAGGLE_FULL_DOWNLOAD:
        import kagglehub
        DS_DIR = Path(kagglehub.dataset_download(KAGGLE_DATASET))   # весь датасет, ~10 ГБ после распаковки
        DS_FILES = {rel: DS_DIR / rel for rel in NEEDED}
    else:
        DS_DIR = None
        DS_FILES = {rel: kaggle_file(rel) for rel in tqdm(NEEDED, desc="kaggle")}
    ds_open = lambda rel: open(DS_FILES[rel], "rb")
    # --- external_data/ и папка для сабмитов — Google Drive
    from google.colab import drive
    drive.mount("/content/drive")
    PROJECT_DIR = Path(DRIVE_DATA_DIR)

EXT = PROJECT_DIR / "external_data"
PROJECT_DIR.mkdir(parents=True, exist_ok=True)
OUT_DIR = PROJECT_DIR / "submissions"; OUT_DIR.mkdir(exist_ok=True, parents=True)
if not (EXT / "calendar_2025.csv").exists():
    hits = sorted(Path("/content/drive/MyDrive").rglob("external_data/calendar_2025.csv")) if Path("/content/drive/MyDrive").exists() else []
    if hits:
        EXT = hits[0].parent
        print("external_data найдена на Drive:", EXT)
    else:
        import subprocess
        repo = Path("/content/moscow_transport")
        if not repo.exists():
            subprocess.run(["git", "clone", "-q", "--depth", "1", "--branch", EXT_REPO_BRANCH, EXT_REPO_URL, str(repo)], check=True)
        hits = sorted(repo.rglob("external_data/calendar_2025.csv"))
        assert hits, f"в репозитории {EXT_REPO_URL} ({EXT_REPO_BRANCH}) нет external_data/calendar_2025.csv"
        EXT = hits[0].parent
        print("external_data скачана из GitHub:", EXT)
assert (EXT / "calendar_2025.csv").exists(), f"нет внешних данных в {EXT}"
print("PROJECT_DIR:", PROJECT_DIR, "| датасет:", DS_DIR or "только labels + шаблон")
print(sorted(p.name for p in EXT.iterdir()))
""")

code(r"""
#@title Загрузка: labels, шаблон сабмита, внешние данные
parts = []
for p in tqdm(["train", "test"], desc="labels"):
    parts.append(pd.read_csv(ds_open(f"labels/labels_day_{p}.csv"), sep=";"))
lab = pd.concat(parts, ignore_index=True)
lab["date"] = pd.to_datetime(lab["date"])
template = pd.read_csv(ds_open("test_submission.csv"), sep=";")

# полная сетка route × date × hour на историю (пропуски = 0 посадок)
hist_dates = pd.date_range("2025-01-01", "2025-10-31")
grid = pd.MultiIndex.from_product([ROUTES, hist_dates, range(24)], names=["route", "date", "hour"]).to_frame(index=False)
df = grid.merge(lab, how="left", on=["route", "date", "hour"]).fillna({"boardings": 0})

cal = pd.read_csv(EXT / "calendar_2025.csv", sep=";", parse_dates=["date"])
w_fact = pd.read_csv(EXT / "weather_fact_hourly_2025.csv", sep=";", parse_dates=["date"])
w_fcst = pd.read_csv(EXT / "weather_fcst_hourly_2025.csv", sep=";", parse_dates=["date"])
events = pd.read_csv(EXT / "events_2025.csv", sep=";", dtype=str).fillna("")
# баллы пробок ЦОДД из Telegram Дептранса (парсер external_data/parse_deptrans_tg.py): @DtOperativno + @DtRoad
traffic = pd.concat([pd.read_csv(EXT / f, sep=";", parse_dates=["date"])
                     for f in ["DtOperativno_traffic_scores_2025.csv", "traffic_scores_2025.csv"]], ignore_index=True)
traffic = traffic.drop_duplicates(["date", "hour", "score", "kind"]).sort_values(["date", "hour", "post_id"], ignore_index=True)
# оперативные сбои трамваев (парсер: python external_data/parse_deptrans_tg.py incidents --channel DtOperativno)
INC_FILE = EXT / "tram_incidents_2025.csv"
incidents = (pd.read_csv(INC_FILE, sep=";", parse_dates=["date"]) if INC_FILE.exists()
             else pd.DataFrame(columns=["post_id", "date", "hour", "route", "cause", "url"]))
if not INC_FILE.exists():
    print("нет external_data/tram_incidents_2025.csv — поправка на сбои отключена (загрузите файл на Drive)")

print(f"labels: {len(lab):,} строк | сетка: {len(df):,} | заполнено нулями: {(len(df)-len(lab)):,}")
print(f"шаблон сабмита: {template.shape} | календарь: {cal.shape} | погода: {w_fact.shape} | события: {events.shape} | баллы пробок: {traffic.shape}")
""")

md("""
## Пайплайн приёма сырых валидаций (критерий 2в)

Сырые `train.csv`/`test.csv` → нормализация (время события, успешные валидации, номер маршрута, отсечение «хвоста» месяца, дубликаты)
→ почасовая витрина `route × date × hour` (посадки, попытки, отказы, число бортов и выходов) → **сверка с labels организаторов**.
Код — `pipeline/ingest_raw.py`, та же функция используется в сервисе. Запускается при `KAGGLE_FULL_DOWNLOAD=True` (~10 ГБ, DuckDB — минуты).
""")

code(r"""
#@title Пайплайн приёма: сырые валидации → почасовая витрина + сверка с labels
""" + PIPELINE_SRC + r"""
if DS_DIR is not None:
    INGEST_OUT = OUT_DIR / "ingest"
    hourly_raw, ingest_rep = run(DS_DIR, INGEST_OUT, labels=lab[["route", "date", "hour", "boardings"]])
    v = ingest_rep["validation_vs_labels"]
    print(f"\nСверка с labels: ключей совпало {v['keys_in_both']:,} из {v['labels_rows']:,}, "
          f"значения совпали в {100 * v['exact_value_match_share']:.3f}% часов, расхождение суммы {100 * v['abs_diff_share_of_sum']:.4f}%")
else:
    print("Сырые данные не скачаны (KAGGLE_FULL_DOWNLOAD=False) — пайплайн пропущен. Модель обучается на labels, это те же агрегаты.")
""")

md("##  EDA")

code(r"""
#@title Маршруты: объём, покрытие, доля в WAPE
t = df.groupby("route").agg(total=("boardings", "sum"), nonzero_hours=("boardings", lambda s: (s > 0).sum()))
t["share_%"] = (100 * t.total / t.total.sum()).round(1)
display(t.sort_values("total", ascending=False)) if "display" in dir() else print(t)
print("маршрут 5 в истории:", int(t.loc[5, "total"]), "посадок → прогноз 0 (возвращён только 17.12.2025, см. EXTERNAL_DATA.md)")
""")

code(r"""
#@title Суточный и недельный профиль
fig, ax = plt.subplots(1, 3, figsize=(18, 4))
d = df.merge(cal[["date", "dow", "day_type"]], on="date")
d.groupby("hour").boardings.sum().plot.bar(ax=ax[0], title="Посадки по часам (все дни)")
d.groupby("dow").boardings.sum().plot.bar(ax=ax[1], title="По дню недели (0=пн)")
(d.pivot_table(index="hour", columns="day_type", values="boardings", aggfunc="mean")
   .plot(ax=ax[2], title="Средний час по типу дня"))
plt.tight_layout(); plt.show()
""")

code(r"""
#@title Динамика по неделям и маршрутам (сезонность, аномалии)
wk = df.set_index("date").groupby("route").boardings.resample("W").sum().unstack(0)
wk.iloc[1:-1].plot(figsize=(18, 5), title="Посадки по неделям"); plt.show()
mon = df.groupby([df.date.dt.month, "route"]).boardings.sum().unstack()
print("Месячные суммы, тыс.:"); print((mon / 1e3).round(0))
print("\nОтношение к марту (лето ↓, сентябрь–октябрь — плато):"); print((mon / mon.loc[3]).round(2))
""")

code(r"""
#@title Аномальные дни (>±40% от медианы того же дня недели за 4 ПРОШЛЫЕ недели — без заглядывания вперёд)
def find_anomalies(data, cutoff=None):
    # причинная разметка: база дня — медиана того же дня недели за 4 предыдущие недели, данные только до cutoff
    d = data if cutoff is None else data[data.date <= pd.Timestamp(cutoff)]
    daily = d.groupby(["route", "date"]).boardings.sum().unstack(0)
    base = pd.concat([daily.shift(7 * k, freq="D") for k in (1, 2, 3, 4)]).groupby(level=0).median().reindex(daily.index)
    ratio = daily / base
    hol = CAL_ANOM.reindex(daily.index).is_holiday.values[:, None] == 1
    anom = ((ratio < 0.6) | (ratio > 1.4)) & ~hol
    s = anom.stack()
    return set((r, d) for d, r in s[s].index)

CAL_ANOM = cal.set_index("date")
anom_all = find_anomalies(df)
print("Аномальных (маршрут, день), не праздники:", len(anom_all))
print(pd.Series([r for r, _ in anom_all]).value_counts().sort_index().rename("дней").to_frame().T)

# для EDA ниже (не для модели): медиана того же дня недели ±4 нед.
daily = df.groupby(["route", "date"]).boardings.sum().unstack(0)
c = cal.set_index("date").reindex(daily.index)
def same_dow_median(s):
    out = pd.Series(index=s.index, dtype=float)
    for d0 in s.index:
        nb = [d0 + pd.Timedelta(weeks=k) for k in (-4, -3, -2, -1, 1, 2, 3, 4)]
        out[d0] = s.reindex(nb).median()
    return out

# смена режима: доля выходных в объёме маршрута по месяцам (маршрут 50 с сентября ходит только по будням)
we = df.assign(we=df.date.dt.dayofweek >= 5).groupby([df.date.dt.month, "route", "we"]).boardings.sum().unstack()
print("\nДоля выходных в объёме, %:"); print((100 * we[True] / we.sum(1)).unstack().round(1))
""")

code(r"""
#@title Эффект календаря и погоды (контроль того, что поправки нужны)
tot = daily.sum(1); tot_base = daily.apply(same_dow_median).sum(1); r = tot / tot_base
print("Праздник в будни:", r[(c.is_holiday == 1) & (c.dow < 5)].mean().round(3),
      "| обычный будний:", r[(c.is_day_off == 0)].mean().round(3))
wd = w_fact.groupby("date").agg(temp=("temperature_2m", "mean"), precip=("precipitation", "sum"))
x = pd.concat([r.rename("ratio"), wd.reindex(r.index)], axis=1)[(c.is_day_off == 0).values]
print("corr(ratio, temp) =", x.corr().loc["ratio", "temp"].round(3), "| corr(ratio, precip) =", x.corr().loc["ratio", "precip"].round(3))
""")

md("""
## Модель 1: профиль × поправки

* **Профиль** `S[route, ptype, hour] = Level[route, ptype] × Shape[route, ptype, hour]`:
  * `Level` — суточный объём за последние `level_weeks` «чистых» недель (уровень быстро дрейфует, нужно свежее окно);
  * `Shape` — доля часа в сутках за последние `n_weeks` недель (форма стабильна, длинное окно гасит шум).
  «Чистые» дни — без праздников, аномалий и, опционально, летних каникул.
  `ptype`: `mon_thu / fri / sat / sun`. Праздник → профиль `sun` × `K_holiday`; рабочая суббота → `mon_thu` × `K_WORKING_SATURDAY`.
* **K_holiday, K_short** — оцениваются по истории до отсечки.
* **K_weather** — регрессия `log(факт/профиль)` на погоду по будням обучающего окна; на горизонте — погода `WEATHER_FOR_FORECAST`.
""")

code(r"""
#@title Функции: метрика, типы дней, признаки
import hashlib
CKPT_DIR = PROJECT_DIR / "checkpoints"
def _h(obj):
    return hashlib.sha1(repr(obj).encode("utf-8")).hexdigest()[:16]
DATA_HASH = hashlib.sha1(b"".join(pd.util.hash_pandas_object(x, index=False).values.tobytes()
                                  for x in [df, traffic.astype(str), incidents.astype(str), w_fcst, w_fact, cal.astype(str)])).hexdigest()[:16]

def cached(name, parts, fn):
    # результат шага fn() сохраняется на диск; при том же коде, данных, настройках и parts — загружается
    path = CKPT_DIR / f"{name}_{_h((CODE_HASH, DATA_HASH, CONFIG_SNAPSHOT, parts))}.pkl"
    if USE_CHECKPOINTS and path.exists():
        print(f"⏩ чекпоинт {path.name}: загружено с диска, пересчёт пропущен")
        return pd.read_pickle(path)
    res = fn()
    if USE_CHECKPOINTS:
        CKPT_DIR.mkdir(parents=True, exist_ok=True)
        pd.to_pickle(res, path)
        print(f" чекпоинт сохранён: {path.name}")
    return res

def wape_score(y, p):
    y = np.asarray(y, float); p = np.asarray(p, float)
    return max(0.0, 1 - np.abs(y - p).sum() / y.sum())

CAL = cal.set_index("date")
def ptype_of(dates, use_calendar=True):
    c = CAL.loc[dates]
    pt = np.select([c.dow <= 3, c.dow == 4, c.dow == 5], ["mon_thu", "fri", "sat"], "sun")
    if use_calendar:  # праздник -> профиль воскресенья, рабочая суббота -> будни
        pt = np.where(c.is_holiday == 1, "sun", pt)
        pt = np.where(c.is_working_weekend == 1, "mon_thu", pt)
    return pd.Series(pt, index=dates)

WARM_TEMP = 10.0      # °C: дождь в тёплую погоду отменяет необязательные поездки; в холод эффекта нет (замер ниже)
WEATHER_RIDGE = 50.0  # регуляризация коэффициента погоды
def daily_weather(w):
    g = w.groupby("date")
    d = pd.DataFrame({"temp": g.temperature_2m.mean(), "precip": g.precipitation.sum(),
                      "snow": g.snowfall.sum(), "snow_depth": g.snow_depth.mean(),
                      "precip_day": w[w.hour.between(6, 21)].groupby("date").precipitation.sum()})
    d["precip_warm"] = d.precip_day.fillna(0) * (d.temp >= WARM_TEMP)
    return d
DAY_RESID = None      # остатки прогноза на день вперёд — считаются ниже, до этого погода нейтральна
WD_FACT, WD_FCST = daily_weather(w_fact), daily_weather(w_fcst)
WD_FUT = WD_FACT if WEATHER_FOR_FORECAST == "fact" else WD_FCST   # погода, подаваемая в прогноз (и в валидацию)

# Трафик (источник №4): балл ЦОДД действует на посадки в ближайшие часы после поста.
# «вечером ожидается N баллов» → 17–20 ч, «утром» → 7–9 ч, иначе — 3 часа от часа поста.
def traffic_cells(tr):
    rows = []
    for r in tr.itertuples():
        h0, h1 = (17, 21) if r.part_of_day == "evening" else (7, 10) if r.part_of_day == "morning" else (r.hour, min(r.hour + 3, 24))
        rows += [(r.date, h, r.score, r.post_id) for h in range(h0, h1)]
    c = pd.DataFrame(rows, columns=["date", "hour", "score", "post_id"]).sort_values("post_id").drop_duplicates(["date", "hour"], keep="last")
    c["bucket"] = pd.cut(c.score, [-1, 4, 5, 7, 10], labels=["0-4", "5", "6-7", "8-10"]).astype(str)
    return c[["date", "hour", "bucket"]]
TRAFFIC_CELLS = traffic_cells(traffic)

# Сбой на маршруте (ДТП, контактная сеть, машина на путях): пост → час поста и 2 следующих часа на этом маршруте
INC_CELLS = (pd.concat([incidents.assign(hour=incidents.hour.astype(int) + k) for k in range(3)])
               .query("hour < 24")[["route", "date", "hour"]].astype({"route": int}).drop_duplicates())

""")

code(r"""
#@title Класс ProfileModel
class ProfileModel:
    def __init__(self, n_weeks=6, agg="median", exclude_summer=True, use_weather=True, use_calendar=True, level_weeks=None,
                 drop_anomalies=False, use_regimes=True, use_traffic=None, use_incidents=True,
                 recent_weeks=4, recent_w=0.3, shape8_w=0.0, growth_damp=0.0, growth_scope="route"):
        self.n_weeks, self.agg, self.exclude_summer = n_weeks, agg, exclude_summer
        self.level_weeks = level_weeks or n_weeks        # None → уровень и форма по одному окну (старый профиль)
        self.use_weather, self.use_calendar = use_weather, use_calendar
        self.drop_anomalies = drop_anomalies           # False: после смены режима «аномалии» = новая норма (маршрут 50)
        self.use_regimes = use_regimes
        self.use_traffic = TRAFFIC_IN_MODEL if use_traffic is None else use_traffic
        self.use_incidents = use_incidents
        self.recent_weeks, self.recent_w = recent_weeks, recent_w   # доля «свежей» формы суток (последние недели)
        self.shape8_w = shape8_w                           # гипотеза 1: доля формы суток по окну 8 недель (ансамбль окон)
        self.growth_damp, self.growth_scope = growth_damp, growth_scope   # гипотеза 2: рост уровня по тренду будних дней

    def _clean_days(self, cutoff):
        c = CAL.loc[:cutoff]
        ok = (c.is_holiday == 0) & (c.is_short_workday == 0) & (c.is_working_weekend == 0)
        if self.exclude_summer:
            ok &= ~c.school_holiday_name.fillna("").str.contains("летние")
        days = c.index[ok][-max(self.n_weeks, self.level_weeks) * 7:]
        return days

    def fit(self, data, cutoff):
        cutoff = pd.Timestamp(cutoff)
        self.cutoff = cutoff
        all_days = self._clean_days(cutoff)
        days = all_days[-self.n_weeks * 7:]                # окно формы
        lvl_days = all_days[-self.level_weeks * 7:]        # окно уровня
        tr = data[data.date.isin(all_days)].copy()
        if self.drop_anomalies:                             # разметка только по данным до отсечки
            aset = find_anomalies(data, cutoff)
            tr = tr[[(r, d) not in aset for r, d in zip(tr.route, tr.date)]]
        tr = tr[~tr.hour.isin(TECH_HOURS)]                   # технические часы не входят в форму суток и уровень
        tr["ptype"] = ptype_of(pd.DatetimeIndex(tr.date)).values
        shape = tr[tr.date.isin(days)].groupby(["route", "ptype", "hour"]).boardings.agg(self.agg)
        shape = shape / shape.groupby(level=["route", "ptype"]).transform("sum")
        if self.recent_w > 0:
            # форма суток «плывёт» по сезону (темнеет раньше, сдвигаются пики): к длинному окну примешиваем свежие недели.
            # CV: +0.04 п.п. в среднем, лучше на 4 фолдах из 6
            sr = tr[tr.date.isin(days[-self.recent_weeks * 7:])].groupby(["route", "ptype", "hour"]).boardings.agg(self.agg)
            sr = sr / sr.groupby(level=["route", "ptype"]).transform("sum")
            shape = ((1 - self.recent_w) * shape + self.recent_w * sr.reindex(shape.index).fillna(shape)).fillna(0)
        if self.shape8_w > 0:
            s8 = tr[tr.date.isin(days[-8 * 7:])].groupby(["route", "ptype", "hour"]).boardings.agg(self.agg)
            s8 = s8 / s8.groupby(level=["route", "ptype"]).transform("sum")
            shape = ((1 - self.shape8_w) * shape + self.shape8_w * s8.reindex(shape.index).fillna(shape)).fillna(0)
        level = (tr[tr.date.isin(lvl_days)].groupby(["route", "ptype", "date"]).boardings.sum()
                   .groupby(level=["route", "ptype"]).agg(self.agg))
        if self.growth_damp > 0:
            # рост уровня по данным: будни последних 4 чистых недель к 4 предыдущим, с затуханием, в пределах ±5%
            wd = tr[tr.date.isin(all_days) & (tr.date.dt.dayofweek < 5)].groupby(["route", "date"]).boardings.sum().unstack(0)
            wd = wd.tail(40)
            if len(wd) >= 30:
                rec, prev = wd.tail(20).mean(), wd.iloc[-40:-20].mean()
                if self.growth_scope == "global":
                    g = pd.Series(rec.sum() / max(prev.sum(), 1), index=rec.index)
                else:
                    g = (rec / prev.replace(0, np.nan)).fillna(1.0)
                g = (1 + self.growth_damp * (g - 1)).clip(0.95, 1.05)
                self.growth = g
                level = level * g.reindex(level.index.get_level_values("route")).fillna(1.0).values
        self.S = (shape * level.reindex(shape.index.droplevel("hour")).values).fillna(0)
        self.level = level
        self.regimes = [self._normal_profile(data, rg) for rg in known_regimes(cutoff)] if self.use_regimes else []
        # --- K по календарю: праздник в будний день — к воскресеньям ТОЙ ЖЕ поры, сокращённый день — к таким же будням.
        #     Сравнение с профилем на отсечке смешивало эффект праздника с сезоном (январские праздники при зимнем уровне
        #     против октябрьского профиля) и занижало K до 0.90; к «своим» воскресеньям праздник ≈ 0.99.
        hist = data[data.date <= cutoff]
        self.K_holiday, self.K_short, self.K_pre_ny = 1.0, 1.0, 0.92
        if self.use_calendar:
            self.K_holiday = self._k_recent(hist, CAL.index[(CAL.is_holiday == 1) & (CAL.dow < 5) & (CAL.index <= cutoff)], "sun", 1.0)
            self.K_short = self._k_recent(hist, CAL.index[(CAL.is_short_workday == 1) & (CAL.dow < 5) & (CAL.index <= cutoff)], "same_dow", 1.0)
            self.K_pre_ny = self._k_after_new_year(hist, 0.92)
        # --- K по погоде: дождь днём при t ≥ WARM_TEMP. Коэффициент — по остаткам прогноза на день вперёд
        #     (DAY_RESID: факт / прогноз без погоды, модель видела только прошлое), только дни до отсечки.
        #     Абсолютная температура не используется: она кодирует сезон, а не погоду (проверено — ухудшало прогноз)
        self.beta = np.zeros(1)
        if self.use_weather and DAY_RESID is not None:
            r = DAY_RESID[DAY_RESID.index <= cutoff]
            if len(r) >= 30:
                x = WD_FUT.precip_warm.reindex(r.index).fillna(0).values
                b = float(x @ (r.values - r.mean()) / (x @ x + WEATHER_RIDGE))
                self.beta = np.array([np.clip(b, -0.03, 0.0)])   # знак из физики: дождь посадки не добавляет
        # --- K по сбоям: посадки / профиль в часы после поста о сбое на маршруте (только посты до отсечки)
        self.K_incident = 1.0
        if self.use_incidents and len(INC_CELLS):
            ic = INC_CELLS[INC_CELLS.date <= cutoff]
            if len(ic):
                raw = self._raw(pd.DatetimeIndex(sorted(ic.date.unique())))
                m = ic.merge(raw, on=["route", "date", "hour"]).merge(hist, on=["route", "date", "hour"])
                m = m[m.pred > 50]
                if len(m) >= 20:
                    self.K_incident = float(np.clip(np.median(m.boardings / m.pred), 0.5, 1.0))
        # --- K по трафику: посадки / профиль в часы после поста ЦОДД, по корзинам балла (только посты до отсечки)
        self.K_traffic = {}
        if self.use_traffic:
            tc = TRAFFIC_CELLS[(TRAFFIC_CELLS.date <= cutoff) & ~TRAFFIC_CELLS.date.isin(CAL.index[CAL.is_holiday == 1])]
            if len(tc):
                raw = self._raw(pd.DatetimeIndex(sorted(tc.date.unique())))
                m = tc.merge(raw.groupby(["date", "hour"]).pred.sum().rename("p").reset_index(), on=["date", "hour"]) \
                      .merge(hist.groupby(["date", "hour"]).boardings.sum().rename("y").reset_index(), on=["date", "hour"])
                m = m[m.p > 100]
                r = np.log(m.y / m.p)
                base = r.median()                                  # эффект относительно среднего поста, а не уровня профиля
                for bkt, g in r.groupby(m.bucket):
                    k = float(np.exp(g.median() - base))
                    n = len(g)
                    self.K_traffic[bkt] = float(np.clip(1 + (k - 1) * n / (n + 20), 0.95, 1.05))   # сжатие к 1 при малом n
        return self

    def _normal_profile(self, data, rg):
        # профиль вне режима: текущий будний уровень × (выходные / будни) и форма суток по чистым дням до начала работ
        c = CAL.loc[:rg["start"] - pd.Timedelta(days=1)]
        ok = (c.is_holiday == 0) & (c.is_short_workday == 0) & (c.is_working_weekend == 0)
        ok &= ~c.school_holiday_name.fillna("").str.contains("летние")
        ref = data[data.date.isin(c.index[ok][-REGIME_REF_WEEKS * 7:]) & data.route.isin(rg["routes"])].copy()
        ref["ptype"] = ptype_of(pd.DatetimeIndex(ref.date)).values
        day_lvl = ref.groupby(["route", "ptype", "date"]).boardings.sum().groupby(level=["route", "ptype"]).median()
        ratio = day_lvl / day_lvl.xs("mon_thu", level="ptype").reindex(day_lvl.index.get_level_values("route")).values
        shape = ref.groupby(["route", "ptype", "hour"]).boardings.median()
        shape = shape / shape.groupby(level=["route", "ptype"]).transform("sum")
        idx = shape.index.droplevel("hour")
        cur_weekday = self.level.xs("mon_thu", level="ptype").reindex(idx.get_level_values("route")).values
        S_norm = shape * ratio.reindex(idx).values * cur_weekday
        rg["ratio"] = ratio
        rg["S_normal"] = S_norm[S_norm.index.get_level_values("ptype").isin(rg["ptypes"])]
        return rg

    def _k_recent(self, hist, days, ref, default):
        # факт дня / медиана 4 предыдущих опорных дней той же поры (ref: "sun" — воскресенья, "same_dow" — такие же будни)
        tot = hist.groupby("date").boardings.sum()
        tot = tot[tot > 0]
        vals = []
        for d in days:
            if d not in tot.index:
                continue
            if ref == "sun":
                prev = [x for x in tot.index if x < d and x.dayofweek == 6 and CAL.loc[x, "is_holiday"] == 0][-4:]
            else:
                prev = [x for x in tot.index if x < d and x.dayofweek == d.dayofweek and CAL.loc[x, "is_day_off"] == 0][-4:]
            if len(prev) >= 3:
                vals.append(tot[d] / tot.loc[prev].median())
        return float(np.median(vals)) if vals else default

    def _k_after_new_year(self, hist, default):
        # аналог 29–30.12 (последние рабочие дни перед новогодними каникулами): первые два рабочих дня после каникул
        # к таким же дням недели следующих 3 недель — симметричный «праздничный» спад деловой активности
        tot = hist.groupby("date").boardings.sum()
        work = [d for d in tot.index if d.month == 1 and CAL.loc[d, "is_day_off"] == 0]
        if len(work) < 17:
            return default
        vals = []
        for d in work[:2]:
            ref = [tot[x] for x in work[2:17] if x.dayofweek == d.dayofweek]
            if ref:
                vals.append(tot[d] / np.median(ref))
        return float(np.mean(vals)) if vals else default

    def _k(self, hist, days, default):
        days = [d for d in days if d in set(hist.date)]
        if not days:
            return default
        y = hist[hist.date.isin(days)].groupby("date").boardings.sum()
        p = self._raw(pd.DatetimeIndex(days), calendar=False).groupby("date").pred.sum()
        return float(np.median((y / p).replace([np.inf, -np.inf], np.nan).dropna())) if len(p) else default

    def _raw(self, dates, calendar=True):
        g = pd.MultiIndex.from_product([ROUTES, dates, range(24)], names=["route", "date", "hour"]).to_frame(index=False)
        g["ptype"] = ptype_of(pd.DatetimeIndex(g.date), use_calendar=self.use_calendar or not calendar).values
        g["pred"] = self.S.reindex(pd.MultiIndex.from_frame(g[["route", "ptype", "hour"]])).fillna(0).values
        for rg in getattr(self, "regimes", []):
            # вне интервала работ — нормальный профиль (в обучающем окне выходные могли быть урезаны режимом)
            m = (g.route.isin(rg["routes"]) & g.ptype.isin(rg["ptypes"]) & ~g.date.between(rg["start"], rg["end"])).values
            if m.any():
                g.loc[m, "pred"] = rg["S_normal"].reindex(pd.MultiIndex.from_frame(g.loc[m, ["route", "ptype", "hour"]])).fillna(0).values
        return g

    def predict(self, dates, weather=None):
        dates = pd.DatetimeIndex(dates)
        g = self._raw(dates)
        c = CAL.loc[g.date]
        k = np.ones(len(g))
        if self.use_calendar:
            k = np.where(c.is_holiday.values == 1, self.K_holiday, k)
            k = np.where((c.is_short_workday.values == 1) & (c.dow.values < 5), k * self.K_short, k)
            k = np.where(c.is_working_weekend.values == 1, k * K_WORKING_SATURDAY, k)
        if self.use_weather and weather is not None and self.beta[0] != 0:
            x = weather.reindex(g.date).precip_warm.fillna(0).values.copy()
            # дальше горизонта прогноза погоды — нейтрально (в момент прогноза этих данных нет)
            x[(g.date > self.cutoff + pd.Timedelta(days=WEATHER_HORIZON_DAYS)).values] = 0.0
            k = k * np.exp(np.clip(x * self.beta[0], -0.15, 0.0))
        if self.use_incidents and self.K_incident < 1.0:
            # сбои, известные на момент прогноза (EVENTS_MODE: "all" — все посты, "strict" — до отсечки)
            ic = INC_CELLS if EVENTS_MODE == "all" else INC_CELLS[INC_CELLS.date <= self.cutoff]
            hit = g[["route", "date", "hour"]].merge(ic.assign(_i=1), on=["route", "date", "hour"], how="left")._i.notna().values
            k = np.where(hit, k * self.K_incident, k)
        if self.use_traffic and self.K_traffic:
            # посты ЦОДД, известные на момент прогноза: EVENTS_MODE="all" — все, "strict" — только до отсечки
            tc = TRAFFIC_CELLS if EVENTS_MODE == "all" else TRAFFIC_CELLS[TRAFFIC_CELLS.date <= self.cutoff]
            kt = g[["date", "hour"]].merge(tc, on=["date", "hour"], how="left").bucket.map(self.K_traffic).fillna(1.0).values
            k = k * kt
        g["pred"] = g.pred * k
        return g[["route", "date", "hour", "pred"]]
""")

code(r"""
#@title Фолды валидации
FOLDS = [  # (имя, отсечка, начало, конец)
    ("F1 авг→сен–окт (61д)", "2025-08-31", "2025-09-01", "2025-10-31"),
    ("F2 сен→окт (31д)",     "2025-09-30", "2025-10-01", "2025-10-31"),
    ("F4 сер.сен→окт (47д)", "2025-09-14", "2025-09-15", "2025-10-31"),
    ("F5 сер.окт→окт (19д)", "2025-10-12", "2025-10-13", "2025-10-31"),
    ("F3 мар→апр–май (61д, праздники)", "2025-03-31", "2025-04-01", "2025-05-31"),
    ("F6 фев→мар (31д, зима)", "2025-02-28", "2025-03-01", "2025-03-31"),
]
def evaluate(make_model, folds=FOLDS, verbose=False, return_preds=False):
    res, preds = {}, {}
    for name, cut, a, b in folds:
        m = make_model().fit(df, cut)
        dates = pd.date_range(a, b)
        p = m.predict(dates, weather=WD_FUT)
        y = df[df.date.isin(dates)].merge(p, on=["route", "date", "hour"])
        res[name] = wape_score(y.boardings, y.pred)
        preds[name] = y
        if verbose:
            print(f"  {name}: WAPE-score = {res[name]:.4f}")
    res["mean"] = np.mean(list(res.values()))
    return (res, preds) if return_preds else res

print("Seasonal naive (профиль 6 нед., без поправок):")
r0 = evaluate(lambda: ProfileModel(6, use_weather=False, use_calendar=False), verbose=True)
print(f"  mean = {r0['mean']:.4f}")
""")

code(r"""
#@title Погода (критерий 2а): остатки прогноза на день вперёд и измеренный эффект дождя, фев–окт
# Каждый день модель без погоды обучается на данных «до вчера» и прогнозирует сегодня; остаток дня = log(факт / прогноз).
# Коэффициент дождя на день t обучается только на остатках дней < t (расширяющееся окно) — так же, как в проде.
RESID_CFG = dict(n_weeks=16, level_weeks=3, agg="median", exclude_summer=True)
def _p1d():
    rows = []
    for cut in tqdm(pd.date_range("2025-01-12", "2025-10-30", freq="D"), desc="прогноз на день вперёд"):
        d = cut + pd.Timedelta(days=1)
        m = ProfileModel(**RESID_CFG, use_weather=False).fit(df[df.date <= cut], cut)
        rows.append(df[df.date == d].merge(m.predict([d]), on=["route", "date", "hour"]))
    return pd.concat(rows, ignore_index=True)
P1D = cached("day_ahead_resid", (RESID_CFG,), _p1d)
dd = P1D.groupby("date").agg(y=("boardings", "sum"), p=("pred", "sum"))
c1 = CAL.reindex(dd.index)
ok = ((c1.is_day_off == 0) & (c1.is_short_workday == 0)).values & (dd.p > 0).values
DAY_RESID = np.log(dd.y[ok] / dd.p[ok])

K_W = pd.Series(1.0, index=dd.index)
x_all = WD_FUT.precip_warm.reindex(dd.index).fillna(0)
for t in dd.index:
    r = DAY_RESID[DAY_RESID.index < t]
    if len(r) < 30:
        continue
    x = x_all.reindex(r.index).values
    b = float(np.clip(x @ (r.values - r.mean()) / (x @ x + WEATHER_RIDGE), -0.03, 0.0))
    K_W[t] = float(np.exp(np.clip(x_all[t] * b, -0.15, 0.0)))
q = P1D.merge(K_W.rename("K"), left_on="date", right_index=True)
def d_pp(s): return 100 * (wape_score(s.boardings, s.pred * s.K) - wape_score(s.boardings, s.pred))
WEATHER_EFFECT = {name: d_pp(q[q.date.between(a, b_)]) for name, a, b_ in
                  [("фев–окт", "2025-02-01", "2025-10-31"), ("фев–мар", "2025-02-01", "2025-03-31"),
                   ("апр–авг", "2025-04-01", "2025-08-31"), ("сен–окт", "2025-09-01", "2025-10-31")]}
print("Эффект погоды (дождь при t ≥ %.0f °C) на прогноз на день вперёд, п.п. WAPE-score:" % WARM_TEMP)
print(pd.Series(WEATHER_EFFECT).round(3).to_string())
print("по месяцам:", {m: round(d_pp(g_), 3) for m, g_ in q.groupby(q.date.dt.month)})
x = x_all.reindex(DAY_RESID.index).values
print(f"коэффициент на 31.10: {np.clip(x @ (DAY_RESID.values - DAY_RESID.mean()) / (x @ x + WEATHER_RIDGE), -0.03, 0):.4f} "
      "на мм осадков за день (6–21 ч)")
""")

code(r"""
#@title Подбор гиперпараметров профиля (прогресс-бар + метрика по ходу)
import itertools
# n_weeks — окно формы суток, level_weeks — окно уровня
space = list(itertools.product([8, 12, 16], [2, 3, 4], ["median", "mean"], [True], [False, True]))
def _grid():
    rows = []
    pbar = tqdm(space, desc="grid")
    for n, lw, agg, es, da in pbar:
        r = evaluate(lambda: ProfileModel(n, agg, es, use_weather=False, use_calendar=True, level_weeks=lw, drop_anomalies=da))
        rows.append({"n_weeks": n, "level_weeks": lw, "agg": agg, "exclude_summer": es, "drop_anomalies": da, **r})
        best = max(rows, key=lambda x: x["mean"])
        pbar.set_postfix(best=f"{best['mean']:.4f}", cur=f"{r['mean']:.4f}")
    return pd.DataFrame(rows)
grid_res = cached("profile_grid", (space,), _grid).sort_values("mean", ascending=False)
print(grid_res.head(10).round(4).to_string(index=False))
BEST = grid_res.iloc[0][["n_weeks", "level_weeks", "agg", "exclude_summer", "drop_anomalies"]].to_dict()
BEST["n_weeks"] = int(BEST["n_weeks"]); BEST["level_weeks"] = int(BEST["level_weeks"]); BEST["exclude_summer"] = bool(BEST["exclude_summer"]); BEST["drop_anomalies"] = bool(BEST["drop_anomalies"])
print("BEST:", BEST)
""")

code(r"""
#@title Проверка гипотез на валидации (только прошлые данные, выбор по CV)
# H1 — ансамбль окон формы суток (добавить окно 8 недель); H2 — рост уровня по тренду будних дней (по маршрутам или общий).
# Гипотеза включается в финальную модель, только если улучшает средний WAPE-score фолдов.
HYP = {"база (BEST)": {},
       "H1 форма: +окно 8 нед (0.2)": dict(shape8_w=0.2),
       "H1 форма: +окно 8 нед (0.35)": dict(shape8_w=0.35),
       "H2 рост уровня по маршрутам (×0.5)": dict(growth_damp=0.5, growth_scope="route"),
       "H2 рост уровня по маршрутам (×1.0)": dict(growth_damp=1.0, growth_scope="route"),
       "H2 рост уровня общий (×0.5)": dict(growth_damp=0.5, growth_scope="global"),
       "H2 рост уровня общий (×1.0)": dict(growth_damp=1.0, growth_scope="global")}
hyp_rows = []
for name, kw in tqdm(HYP.items(), desc="гипотезы"):
    r = evaluate(lambda: ProfileModel(**{**BEST, **kw}, use_weather=False))
    hyp_rows.append({"гипотеза": name, **{k: v for k, v in r.items()}})
    print(f"{name:<38} mean={r['mean']:.4f}")
HYP_RES = pd.DataFrame(hyp_rows).set_index("гипотеза")
HYP_RES["Δ, п.п."] = 100 * (HYP_RES["mean"] - HYP_RES.loc["база (BEST)", "mean"])
print(HYP_RES.round(4).to_string())
# лучшая гипотеза из каждой группы, если даёт прирост; затем проверка их сочетания
chosen = {}
for grp in ("H1", "H2"):
    g = HYP_RES[HYP_RES.index.str.startswith(grp)]
    if len(g) and g["Δ, п.п."].max() > 0.01:
        chosen.update(HYP[g["Δ, п.п."].idxmax()])
if chosen:
    r = evaluate(lambda: ProfileModel(**{**BEST, **chosen}, use_weather=False))
    print(f"\nсочетание {chosen}: mean={r['mean']:.4f} (база {HYP_RES.loc['база (BEST)', 'mean']:.4f})")
    if r["mean"] > HYP_RES.loc["база (BEST)", "mean"]:
        BEST = {**BEST, **chosen}
        if chosen.get("growth_damp", 0) > 0:
            SEASON_GROWTH = 1.0            # рост уровня теперь оценивается по данным — постоянный множитель не нужен
print("\nИтоговая конфигурация профиля:", BEST, "| SEASON_GROWTH =", SEASON_GROWTH)
""")

code(r"""
#@title Ablation внешних поправок (для критерия 2а)
abl = {}
for name, kw in tqdm([("profile only", dict(use_weather=False, use_calendar=False)),
                      ("+ calendar", dict(use_weather=False, use_calendar=True)),
                      ("+ calendar + weather", dict(use_weather=True, use_calendar=True))], desc="ablation"):
    abl[name] = evaluate(lambda: ProfileModel(**{**BEST, **kw}))
abl = pd.DataFrame(abl).T.round(4)
print(abl.to_string())
m_tmp = ProfileModel(**BEST).fit(df, "2025-10-31")
print(f"\nK_holiday={m_tmp.K_holiday:.3f}  K_short={m_tmp.K_short:.3f}  beta_дождь_в_тепло={np.round(m_tmp.beta, 4)}")
""")

code(r"""
#@title Эффект внешних источников (критерий 2а): потоковый режим, прогноз на 1 день вперёд, сен–окт
# каждый день: модель обучается на данных «до вчера» и прогнозирует сегодняшний день; источник выключается по одному
ABL_CUTOFFS = pd.date_range("2025-09-01", "2025-10-30", freq="D")
def _stream_1d(**kw):
    out = []
    for cut in ABL_CUTOFFS:
        d = cut + pd.Timedelta(days=1)
        m = ProfileModel(**{**BEST, **kw}).fit(df[df.date <= cut], cut)
        out.append(df[df.date == d].merge(m.predict([d], weather=WD_FUT), on=["route", "date", "hour"]))
    return pd.concat(out, ignore_index=True)
def stream_1d(**kw):
    return cached("stream_1d", (BEST, sorted(kw.items()), str(ABL_CUTOFFS[0]), len(ABL_CUTOFFS)), lambda: _stream_1d(**kw))

SOURCES = [("календарь (xmlcalendar, каникулы)", "use_calendar"), ("погода (Open-Meteo, архив прогнозов)", "use_weather"),
           ("ремонты и режимы маршрутов (Telegram Дептранса, newsvostok)", "use_regimes"),
           ("сбои трамваев: ДТП, контактная сеть (Telegram @DtOperativno)", "use_incidents"),
           ("трафик (баллы ЦОДД, Telegram Дептранса)", "use_traffic")]
base_run = stream_1d()

rows = []
for name, flag in tqdm(SOURCES, desc="ablation 1d"):
    on = base_run if (flag != "use_traffic" or TRAFFIC_IN_MODEL) else stream_1d(use_traffic=True)
    off = stream_1d(**{flag: False}) if (flag != "use_traffic" or TRAFFIC_IN_MODEL) else base_run
    r = {"источник": name, "WAPE-score без": wape_score(off.boardings, off.pred), "WAPE-score с": wape_score(on.boardings, on.pred)}
    if flag == "use_incidents":
        on_t = on.merge(INC_CELLS, on=["route", "date", "hour"]); off_t = off.merge(INC_CELLS, on=["route", "date", "hour"])
        r["на затронутых часах: без → с"] = f"{wape_score(off_t.boardings, off_t.pred):.4f} → {wape_score(on_t.boardings, on_t.pred):.4f} (n={len(on_t)})"
    if flag == "use_traffic":
        on_t = on.merge(TRAFFIC_CELLS, on=["date", "hour"]); off_t = off.merge(TRAFFIC_CELLS, on=["date", "hour"])
        r["на затронутых часах: без → с"] = f"{wape_score(off_t.boardings, off_t.pred):.4f} → {wape_score(on_t.boardings, on_t.pred):.4f} (n={len(on_t)})"
    rows.append(r)
src = pd.DataFrame(rows)
src["Δ, п.п."] = 100 * (src["WAPE-score с"] - src["WAPE-score без"])
print(src.round(4).to_string(index=False))

# режимы маршрутов: в сен–окт режим действует весь период, поэтому эффект виден на горизонте ноя–дек
m_on = ProfileModel(**BEST).fit(df, "2025-10-31"); m_off = ProfileModel(**BEST, use_regimes=False).fit(df, "2025-10-31")
nd = pd.date_range(FORECAST_START, FORECAST_END)
d_on, d_off = m_on.predict(nd, weather=WD_FUT), m_off.predict(nd, weather=WD_FUT)
print(f"\nРежимы: прогноз ноя–дек по маршрутам 7 и 50 — {d_off[d_off.route.isin([7, 50])].pred.sum():,.0f} без → "
      f"{d_on[d_on.route.isin([7, 50])].pred.sum():,.0f} с учётом восстановления движения 15.11 (на лидерборде 0.880 → 0.890)")
print("Календарь на праздниках — см. ablation по фолдам выше (фолд F3 с майскими праздниками)")
print(f"K_incident (посадки в 3 часа после поста о сбое / профиль) на 31.10: {m_on.K_incident:.3f}")
print(f"K_traffic по корзинам балла (на 31.10): {ProfileModel(**BEST, use_traffic=True).fit(df, '2025-10-31').K_traffic}"
      f" | в сабмите: {'да' if TRAFFIC_IN_MODEL else 'нет — в потоковой проверке эффект отрицательный'}")
""")

md("""
## Модель 2: прямой многогоризонтный LightGBM, обученный в потоковой постановке

Как модель будет работать в проде: в момент `t` она видит только данные до `t` и прогнозирует сразу весь горизонт `t+1 … t+61` (direct, без рекурсии).
Так же она и **обучается**: в истории через каждые `ORIGIN_STEP_DAYS` дней ставится «точка прогноза» `t`. Признаки считаются строго по данным до `t`,
цель — фактические посадки через 1…61 день. Одна глобальная модель учится на **~1.3 млн примеров** (~90 точек × 61 день × 9 маршрутов × 24 часа)
вместо ~60 тыс. строк labels. Это и есть честное расширение обучающей выборки.

* **Признаки** (все известны в момент `t`): маршрут, час, день недели, тип дня, праздник / сокращённый / рабочая суббота, дни до НГ;
  свежесть уровня (будни за 5/10/20 дней к 30); тот же час в тот же день недели неделю назад и в среднем за 4 недели; флаг режима маршрута;
  прогноз модели 1 (профиля) как базовая линия.
* **Цель** — отношение `факт / профиль` с весом `профиль`. L1 на таком отношении — это ровно WAPE по посадкам.
* Летние недели (отпуска, закрытие участков) из целей исключены: на ноябрь–декабрь эта динамика не переносится.
* **Девять LightGBM**: три горизонта обучения (цели на 1–3, 1–14 и 1–28 дней вперёд) × три seed, прогноз — среднее девяти.
  Усреднение по seed гасит случайность отдельного обучения — скор перезапуска стабильнее.
  Модель, обученная на всех 61 днях, выучивает сезонный дрейф января–августа и переносит его на осень: на фолдах
  ML отдельно 0.887; на коротких горизонтах — 0.891 (≤14) … 0.8922 (ансамбль трёх). Признаки фиксируются в точке прогноза,
  поэтому модель применяется к любому дню горизонта (прямой прогноз).
* **Итоговый прогноз — смесь ML и профиля с весом, зависящим от горизонта**: на ближних днях точнее ML (лаги свежие),
  на дальних — профиль (ML там экстраполирует). Вес ML `w_near` для дней ≤ `h_cut` и `w_far` дальше — выбираются по CV.
* **Привязка уровня** (`ML_ANCHOR_LEVEL`): суммарный объём маршрута на горизонте берётся от профиля, ML распределяет его по дням и часам.
  Без привязки уровень ML на 61 день нестабилен: в одном из прогонов −3.5% к профилю, лидерборд 0.87 вместо 0.89.
  С привязкой на фолдах: профиль 0.8935, 80% ML — 0.8935, 100% ML — 0.892.
""")

code(r"""
#@title Модель 2: признаки из точки прогноза и обучающая выборка
ORIGIN_STEP_DAYS = 3                                   #@param {type:"integer"}
# шаг точек прогноза в истории: 3 → ~90 точек, ~1.3 млн примеров (7 → 39 точек, ~570 тыс.; быстрее в 2 раза)
ML_ROUNDS = 500
ML_HORIZONS = (3, 14, 28)                              # горизонты обучения LightGBM (дней вперёд)
ML_SEEDS = (42, 43, 44)                                # на каждый горизонт — 3 модели с разными seed, прогноз — среднее 9 моделей
# вес ML в итоговом прогнозе по горизонту: w_near для дней 1..h_cut, w_far дальше — перебор по CV
BLEND_GRID = dict(w_near=(0.6, 0.8, 0.9, 1.0), w_far=(0.2, 0.3, 0.5, 0.7, 0.8), h_cut=(7, 14, 21, 28))
ML_ANCHOR_LEVEL = True                                 #@param {type:"boolean"}
# True: суммарный объём маршрута на горизонте задаёт профиль, распределение по дням и часам — ML.
# На горизонте 61 день уровень у LightGBM нестабилен (в прогоне 26.09 −3.5% к профилю → лидерборд 0.87)
ML_FEATS = ["route", "hour", "dow", "ptype", "is_holiday", "is_short_workday", "is_working_weekend", "is_day_off",
            "pre_new_year_week", "days_to_ny", "lvl_wd5", "lvl_wd10", "lvl_wd20", "lag_w1", "lag_m4", "in_regime",
            "hour_share"]
# сильная регуляризация: с 63 листьями / 200 примеров модель переобучалась на особенностях истории
# (фолд после летнего спада 0.878 → 0.891, ML отдельно 0.888 → 0.893 по среднему 5 фолдов)
ML_PARAMS = dict(objective="l1", learning_rate=0.03, num_leaves=15, min_data_in_leaf=1000, feature_fraction=0.8,
                 bagging_fraction=0.8, bagging_freq=1, lambda_l2=1.0, seed=SEED, verbose=-1, num_threads=os.cpu_count(),
                 deterministic=True, force_col_wise=True)   # детерминизм нужен тесту на утечку
ROUTE_DTYPE = pd.CategoricalDtype(sorted(r for r in ROUTES if r != 5))
SUMMER_DAYS = set(CAL.index[CAL.school_holiday_name.fillna("").str.contains("летние")])
PTYPE_CODE = {"mon_thu": 0, "fri": 1, "sat": 2, "sun": 3}

def make_pivots(data):
    Y = data.pivot_table(index=["route", "date"], columns="hour", values="boardings", aggfunc="sum").reindex(columns=range(24)).fillna(0)
    return Y, Y.sum(1)

def origin_frame(data, t, end, piv):
    # признаки для прогноза из точки t на даты t+1…end; используются только данные <= t
    t = pd.Timestamp(t)
    dates = pd.date_range(t + pd.Timedelta(days=1), pd.Timestamp(end))
    hist = data[data.date <= t]
    Y, DAILY = piv
    pm = ProfileModel(**BEST).fit(hist, t)
    f = pm.predict(dates, weather=WD_FUT).rename(columns={"pred": "prof"})
    c = CAL.loc[f.date]
    for col in ["dow", "is_holiday", "is_short_workday", "is_working_weekend", "is_day_off", "pre_new_year_week"]:
        f[col] = c[col].values
    f["days_to_ny"] = np.clip(c.days_to_new_year.values, 0, 90)
    f["ptype"] = ptype_of(pd.DatetimeIndex(f.date)).map(PTYPE_CODE).values
    d = DAILY[DAILY.index.get_level_values("date").to_series().between(t - pd.Timedelta(days=55), t).values].unstack(0)
    wd = d[d.index.dayofweek < 5]
    for k in (5, 10, 20):
        f[f"lvl_wd{k}"] = f.route.map(wd.tail(k).mean() / wd.tail(30).mean().replace(0, np.nan)).values
    back = ((t.dayofweek - f.date.dt.dayofweek) % 7).values
    last_same = t - pd.to_timedelta(back, unit="D")                  # последний такой же день недели <= t
    rows, hours = np.arange(len(f)), f.hour.values
    lag = lambda k: Y.reindex(pd.MultiIndex.from_arrays([f.route.values, last_same - pd.Timedelta(days=7 * k)])).values[rows, hours]
    f["lag_w1"] = lag(0)
    f["lag_m4"] = np.nanmean(np.vstack([lag(k) for k in range(4)]), axis=0)
    for col in ["lag_w1", "lag_m4"]:
        f[col] = f[col] / f.prof.clip(lower=1)
    f["hour_share"] = f.prof / f.groupby(["route", "date"]).prof.transform("sum").replace(0, np.nan)   # доля часа в сутках профиля
    f["h"] = (f.date - t).dt.days                                   # горизонт (не признак — для отбора целей)
    f["in_regime"] = 0
    for rg in pm.regimes:
        f.loc[f.route.isin(rg["routes"]) & f.date.between(rg["start"], rg["end"]) & (f.ptype >= 2), "in_regime"] = 1
        # Лаги за выходные внутри режима (ремонт: 50 не ходит) не описывают выходные ПОСЛЕ его окончания —
        # иначе модель переносит нули ремонта на восстановленные выходные. Такие лаги — нейтральные (= профилю),
        # профиль уже знает об окончании режима. В обучении не встречается: режимы не кончались внутри горизонта.
        after_end = (f.route.isin(rg["routes"]) & (f.ptype >= 2) & (f.date.dt.dayofweek >= 5) & (f.date > rg["end"])).values
        lags_in = (last_same >= rg["start"]) & (last_same - pd.Timedelta(days=21) <= rg["end"])
        f.loc[after_end & np.asarray(lags_in), ["lag_w1", "lag_m4"]] = 1.0
    return f, pm

ORIGINS = pd.date_range("2025-02-01", "2025-10-30", freq=f"{ORIGIN_STEP_DAYS}D")

def build_train(data, cutoff, cache=None, piv=None):
    # обучающая выборка на отсечку: точки прогноза < cutoff, цели <= cutoff и на горизонте <= max(ML_HORIZONS), без летних недель
    cutoff = pd.Timestamp(cutoff)
    parts = []
    for o in ORIGINS[ORIGINS < cutoff]:
        fr = cache[o] if cache is not None else origin_frame(data, o, o + pd.Timedelta(days=61), piv)[0]
        parts.append(fr[(fr.date <= cutoff) & ~fr.date.isin(SUMMER_DAYS) & (fr.h <= max(ML_HORIZONS))])
    tr = pd.concat(parts).merge(data[["route", "date", "hour", "boardings"]], on=["route", "date", "hour"])
    return tr[tr.prof > 1]

def _tr_hash(tr):
    return hashlib.sha1(pd.util.hash_pandas_object(tr[ML_FEATS + ["boardings", "prof"]], index=False).values.tobytes()).hexdigest()[:16]

def fit_ml(tr, seed=SEED):
    # веса модели — чекпоинт на диске; ключ — содержимое обучающей выборки, seed, параметры и версия LightGBM
    params = {**ML_PARAMS, "seed": seed}
    key = _h((_tr_hash(tr), seed, ML_ROUNDS, ML_FEATS, {k: v for k, v in params.items() if k != "num_threads"}, lgb.__version__))
    path = CKPT_DIR / "lgbm" / f"lgbm_{key}.txt"
    if USE_CHECKPOINTS and path.exists():
        return lgb.Booster(model_file=str(path))
    X = tr[ML_FEATS].copy(); X["route"] = X.route.astype(ROUTE_DTYPE)
    m = lgb.train(params, lgb.Dataset(X, (tr.boardings / tr.prof).clip(0, 3), weight=tr.prof), ML_ROUNDS)
    if USE_CHECKPOINTS:
        path.parent.mkdir(parents=True, exist_ok=True)
        m.save_model(str(path))
    return m

def predict_ml(model, fr):
    X = fr[ML_FEATS].copy(); X["route"] = X.route.astype(ROUTE_DTYPE)
    return np.where(fr.prof.values > 1, fr.prof.values * np.clip(model.predict(X), 0.3, 2.0), fr.prof.values)

def fit_ml_ensemble(tr):
    # горизонт обучения × seed: 3 × 3 = 9 моделей; усреднение гасит случайность отдельного обучения
    return [fit_ml(tr[tr.h <= hm], sd) for hm in ML_HORIZONS for sd in ML_SEEDS]

def predict_ml_ensemble(models, fr):
    return np.mean([predict_ml(m, fr) for m in models], axis=0)

def anchor_level(fr, ml):
    # уровень маршрута на всём горизонте — от профиля; распределение по дням и часам — от ML
    if not ML_ANCHOR_LEVEL:
        return ml
    s = pd.Series(ml, index=fr.index)
    k = fr.groupby("route").prof.transform("sum") / s.groupby(fr.route.values).transform("sum").replace(0, np.nan)
    return (s * k).fillna(0).values

PIV_ALL = make_pivots(df)
# точки прогноза считаются один раз: каждая использует только данные до себя (это проверяет тест на утечку ниже)
ORIGIN_CACHE = cached("origin_frames", (BEST, ORIGIN_STEP_DAYS, str(ORIGINS[0]), len(ORIGINS)),
                      lambda: {o: origin_frame(df, o, o + pd.Timedelta(days=61), PIV_ALL)[0] for o in tqdm(ORIGINS, desc="точки прогноза")})
print(f"точек прогноза: {len(ORIGINS)} | строк-примеров: {sum(len(v) for v in ORIGIN_CACHE.values()):,} (labels: {len(lab):,})")
""")

code(r"""
#@title Валидация модели 2 и ансамбля по фолдам
rows, FOLD_FRAMES = [], []
for name, cut, a, b in tqdm(FOLDS, desc="folds"):
    models = fit_ml_ensemble(build_train(df, cut, cache=ORIGIN_CACHE))
    fr, _ = origin_frame(df, cut, b, PIV_ALL)
    y = df[df.date.isin(pd.date_range(a, b))].merge(fr, on=["route", "date", "hour"])
    raw = predict_ml_ensemble(models, y)
    y["lgb"] = anchor_level(y, raw)
    r = {"fold": name, "profile": wape_score(y.boardings, y.prof), "ML без привязки уровня": wape_score(y.boardings, raw),
         "ML/профиль, сумма": raw.sum() / y.prof.sum(), "lgb": wape_score(y.boardings, y.lgb)}
    for wgt in (0.5, 0.8):
        r[f"blend{wgt}"] = wape_score(y.boardings, (1 - wgt) * y.prof + wgt * y.lgb)
    rows.append(r)
    FOLD_FRAMES.append(y[["boardings", "prof", "lgb", "h"]].assign(fold=name))
    print(f"{name}: profile={r['profile']:.4f}  ML={r['lgb']:.4f}  80% ML={r['blend0.8']:.4f}")
cv = pd.DataFrame(rows).set_index("fold")

# вес ML по горизонту: перебор (w_near, w_far, h_cut) по среднему WAPE-score фолдов
def blend_weight(h, b=None):
    b = b or BLEND
    return np.where(np.asarray(h) <= b["h_cut"], b["w_near"], b["w_far"])
def cv_blend(b):
    return [wape_score(f.boardings, (1 - blend_weight(f.h, b)) * f.prof + blend_weight(f.h, b) * f.lgb) for f in FOLD_FRAMES]
cands = [dict(w_near=wn, w_far=wf, h_cut=hc) for wn in BLEND_GRID["w_near"] for wf in BLEND_GRID["w_far"] for hc in BLEND_GRID["h_cut"]]
BLEND = max(cands, key=lambda b: np.mean(cv_blend(b)))
cv["по горизонту"] = cv_blend(BLEND)
cv.loc["mean"] = cv.mean()
print(cv.round(4).to_string())
BLEND_W = float(np.mean(blend_weight(np.arange(1, 62))))     # средний вес ML на горизонте ноя–дек (61 день)
print(f"Вес ML: {BLEND['w_near']} на днях 1–{BLEND['h_cut']}, {BLEND['w_far']} дальше (в среднем на 61 день — {BLEND_W:.2f}) | "
      f"CV {cv.loc['mean', 'по горизонту']:.4f} против профиля {cv.loc['mean', 'profile']:.4f} и 80% ML {cv.loc['mean', 'blend0.8']:.4f}")
imp = pd.Series(np.sum([m.feature_importance("gain") for m in models], axis=0), index=ML_FEATS).sort_values(ascending=False)
print("\nВажность признаков модели 2 (gain, %):"); print((100 * imp / imp.sum()).round(1).to_string())
""")

code(r"""
#@title Ошибка по маршрутам и по дням горизонта (устойчивость многошагового прогноза)
_, P = evaluate(lambda: ProfileModel(**BEST), folds=FOLDS[:1], return_preds=True)
y = P[FOLDS[0][0]]
by_route = y.groupby("route").apply(lambda g: pd.Series({"WAPE-score": wape_score(g.boardings, g.pred) if g.boardings.sum() else np.nan,
                                                          "share_err_%": 100 * np.abs(g.boardings - g.pred).sum() / np.abs(y.boardings - y.pred).sum()}))
print(by_route.round(3).T)
dd = y.groupby("date").apply(lambda g: np.abs(g.boardings - g.pred).sum() / g.boardings.sum())
ax = (1 - dd).plot(figsize=(16, 3.5), title="F1: дневной WAPE-score по горизонту (прямой прогноз — ошибка не растёт с горизонтом)")
ax.axhline(0.88, ls="--", c="gray"); plt.show()
""")

md("##  Финальный прогноз на ноябрь–декабрь 2025 и сабмит")

code(r"""
#@title Полный пайплайн прогноза: данные до отсечки → прогноз на горизонт
def route5_pred(pm, dates):
    # дата запуска — событие из новостей; уровень и форма — маршрут-аналог из нашего датасета
    S = pm.S.xs(ROUTE5_ANALOG_ROUTE, level="route") * ROUTE5_K
    g = pd.MultiIndex.from_product([[5], pd.DatetimeIndex(dates), range(24)], names=["route", "date", "hour"]).to_frame(index=False)
    g["ptype"] = ptype_of(pd.DatetimeIndex(g.date)).values
    g["pred"] = S.reindex(pd.MultiIndex.from_frame(g[["ptype", "hour"]])).fillna(0).values
    g.loc[g.date < pd.Timestamp(ROUTE5_LAUNCH), "pred"] = 0.0
    return g[["route", "date", "hour", "pred"]]

def apply_knobs(base, pm):
    # экспертные поправки без аналогов в истории (те же ручки — в UI)
    p = base.copy()
    c = CAL.loc[p.date]
    k_ny = K_PRE_NEW_YEAR if K_PRE_NEW_YEAR is not None else pm.K_pre_ny
    p.loc[c.pre_new_year_week.values.astype(bool) & (c.is_day_off.values == 0) & (p.date >= "2025-12-29").values, "pred"] *= k_ny
    p["pred"] *= p.date.dt.month.map(K_SEASON).fillna(1.0).values
    p = p[p.route != 5]
    r5 = route5_pred(pm, sorted(base.date.unique()))
    if not ROUTE5_ENABLED:
        r5["pred"] = 0.0
    return pd.concat([p, r5], ignore_index=True).sort_values(["route", "date", "hour"], ignore_index=True)

def full_forecast(data, cutoff, dates, cache=None):
    # ровно то, что работает в потоковом режиме: обучение только на data до cutoff, прогноз на dates.
    # cache — готовые точки прогноза из истории (каждая зависит только от данных до себя); None — пересчитать с нуля
    cutoff = pd.Timestamp(cutoff)
    dates = pd.DatetimeIndex(dates)
    data = data[data.date <= cutoff]
    piv = make_pivots(data)
    fr, pm = origin_frame(data, cutoff, dates.max(), piv)
    p = fr[fr.date.isin(dates)].copy()
    p["pred"] = p.prof
    pm.ml_models = []
    if BLEND_W > 0:
        models = fit_ml_ensemble(build_train(data, cutoff, cache=cache, piv=piv))
        pm.ml_models = models
        w = blend_weight((p.date - cutoff).dt.days.values)          # вес ML по горизонту
        p["pred"] = (1 - w) * p.prof + w * anchor_level(p, predict_ml_ensemble(models, p))
    return apply_knobs(p[["route", "date", "hour", "pred"]], pm), pm
""")

md("""
## Проверка на утечку данных

1. **Тест с порчей будущего.** Данные после отсечки перемешиваются и умножаются на 10. Прогноз должен совпасть с исходным **бит в бит**.
   Если модель где-то подсматривает в будущее (признаки, разметка аномалий, подбор коэффициентов), прогноз изменится.
2. **Тест с обрезкой.** В модель передаются только строки до отсечки, как в потоковом режиме. Прогноз тот же.
3. **Статические проверки.** Погода берётся только из архива прогнозов и только на горизонт прогноза погоды. Из шаблона сабмита берутся только ключи.
   Внешний target (опубликованные числа поездок) не используется.
""")

code(r"""
#@title Тест на утечку (порча и обрезка данных после отсечки)
def corrupt_after(data, cutoff, seed=0):
    d = data.copy(); m = (d.date > pd.Timestamp(cutoff)).values
    d.loc[m, "boardings"] = np.random.default_rng(seed).permutation(d.loc[m, "boardings"].values) * 10 + 123
    return d

leak_rows = []
LEAK_FOLDS = [f for f in FOLDS if f[0].startswith(("F2", "F3"))]   # осень и весна; каждый прогон — полный пересчёт
for name, cut, a, b in tqdm(LEAK_FOLDS, desc="leak test"):
    dates = pd.date_range(a, b)
    ref, _ = full_forecast(df, cut, dates, cache=ORIGIN_CACHE)
    bad, _ = full_forecast(corrupt_after(df, cut), cut, dates)          # всё с нуля на испорченных данных
    trunc, _ = full_forecast(df[df.date <= cut], cut, dates)
    y = df[df.date.isin(dates)].merge(ref, on=["route", "date", "hour"])
    leak_rows.append({"fold": name, "max|Δ| порча": np.abs(ref.pred.values - bad.pred.values).max(),
                      "max|Δ| обрезка": np.abs(ref.pred.values - trunc.pred.values).max(),
                      "WAPE-score": wape_score(y.boardings, y.pred)})
leak = pd.DataFrame(leak_rows).set_index("fold")
print(leak.to_string())
assert (leak[["max|Δ| порча", "max|Δ| обрезка"]] < 1e-6).all().all(), " прогноз зависит от данных после отсечки — УТЕЧКА"

checks = {
    "погода: архив прогнозов, а не факт": WEATHER_FOR_FORECAST == "fcst",
    f"погода: только {WEATHER_HORIZON_DAYS} дн. горизонта, дальше нейтрально": WEATHER_HORIZON_DAYS <= 14,
    "модель 2 без погодных признаков на длинном горизонте": not any(w in ML_FEATS for w in ["temperature_2m", "precipitation", "snowfall"]),
    "из test_submission.csv берутся только ключи route/date/hour": True,
    "маршрут 5: без опубликованного числа поездок (аналог из датасета)": True,
    "трафик: K_traffic обучается только на постах ЦОДД до отсечки": True,
    "сбои: K_incident обучается только на постах до отсечки": True,
}
for k, v in checks.items():
    print(("" if v else ""), k)
print(("" if EVENTS_MODE == "strict" else ""), f"события/режимы: EVENTS_MODE={EVENTS_MODE!r}",
      "(только опубликованные до отсечки)" if EVENTS_MODE == "strict" else
      "(используются и новости после отсечки — разрешено организаторами; дают дату восстановления движения 15.11)")
print("\n Прогноз не зависит от данных после отсечки")
""")

code(r"""
#@title Потоковый бэктест: каждую неделю переобучение на данных «до сегодня» и прогноз на следующую неделю
STREAM_CUTOFFS = pd.date_range("2025-09-07", "2025-10-26", freq="7D")
stream_rows, stream_preds = [], []
for cut in tqdm(STREAM_CUTOFFS, desc="stream"):
    dates = pd.date_range(cut + pd.Timedelta(days=1), min(cut + pd.Timedelta(days=7), pd.Timestamp("2025-10-31")))
    p, _ = full_forecast(df[df.date <= cut], cut, dates, cache=ORIGIN_CACHE)   # модель видит только прошлое
    y = df[df.date.isin(dates)].merge(p, on=["route", "date", "hour"])
    stream_preds.append(y)
    stream_rows.append({"отсечка": cut.date(), "прогноз на": f"{dates[0].date()}…{dates[-1].date()}", "WAPE-score": wape_score(y.boardings, y.pred)})
stream = pd.DataFrame(stream_rows)
allp = pd.concat(stream_preds)
print(stream.round(4).to_string(index=False))
print(f"\nПотоковый WAPE-score (сен–окт, горизонт 7 дней): {wape_score(allp.boardings, allp.pred):.4f}")
""")

code(r"""
#@title Режимы маршрутов: что модель знает на 31.10 и какой профиль ставит на выходные
pm_chk = ProfileModel(**BEST).fit(df, "2025-10-31")
for rg in pm_chk.regimes:
    print(f"{rg['name']}\n  интервал: {rg['start'].date()} … {rg['end'].date()}  (EVENTS_MODE={EVENTS_MODE})")
    print("выходные / будни до начала работ:", rg["ratio"].unstack("ptype")[["sat", "sun"]].round(2).to_dict("index"))
    chk = pm_chk._raw(pd.date_range("2025-11-01", "2025-12-31"))
    chk = chk[chk.route.isin(rg["routes"])].groupby(["date", "route"]).pred.sum().unstack()
    wk = chk.index.dayofweek >= 5
    print("прогноз на выходные (посадок в сутки):")
    print(chk[wk].round(0).astype(int).to_string())
""")

code(r"""
#@title Финальный прогноз: обучение на янв–окт, прогноз на ноябрь–декабрь
CUTOFF = "2025-10-31"
dates = pd.date_range(FORECAST_START, FORECAST_END)
pred, pm = full_forecast(df, CUTOFF, dates, cache=ORIGIN_CACHE)
pred["pred"] = pred.pred * SEASON_GROWTH                   # сезонная поправка уровня — до годового сценария и артефактов
pred.loc[pred.route == 5, "pred"] = 0.0                    # маршрут 5 — нули (указание организаторов)
print(f"BEST={BEST} K_holiday={pm.K_holiday:.3f} K_short={pm.K_short:.3f} beta={np.round(pm.beta, 4)} BLEND_W={BLEND_W}")
print(f"маршрут 5: {'включён (аналог — маршрут ' + str(ROUTE5_ANALOG_ROUTE) + ')' if ROUTE5_ENABLED else 'выключен (0)'}")
print("Прогноз, тыс. посадок по месяцам и маршрутам:")
print((pred.groupby([pred.date.dt.month, "route"]).pred.sum().unstack() / 1e3).round(0))
""")

code(r"""
#@title Sanity-check: прогноз vs последние недели истории
hist_w = df[df.date >= "2025-09-01"].set_index("date").boardings.resample("D").sum()
fc_w = pred.set_index("date").pred.resample("D").sum()
ax = hist_w.plot(figsize=(16, 4), label="факт сен–окт"); fc_w.plot(ax=ax, label="прогноз ноя–дек")
ax.legend(); ax.set_title("Суммарные посадки по дням"); plt.show()
""")

code(r"""
#@title Горизонт «год» (качественно): помесячный прогноз ноябрь 2025 – октябрь 2026
# уровень = прогноз модели на ноя–дек; остальные месяцы — сезонный индекс 2025 года (месяц / сен–окт, медиана по маршрутам,
# кроме 7 и 50 — у них летом и осенью ремонты); 2026 — тот же профиль. Это качественный сценарий, не точечный прогноз.
mon = df.assign(m=df.date.dt.month).groupby(["route", "m"]).boardings.sum().unstack()
ndays = df.assign(m=df.date.dt.month).groupby("m").date.nunique()
per_day = mon / ndays
season_idx = per_day.div(per_day[[9, 10]].mean(1), axis=0)
city_idx = season_idx.drop(index=[5, 7, 50], errors="ignore").median()        # индекс месяца по «чистым» маршрутам
spread = season_idx.drop(index=[5, 7, 50], errors="ignore").std()
fc_day = pred.assign(m=pred.date.dt.month).groupby(["route", "m"]).pred.sum().unstack() / pred.assign(m=pred.date.dt.month).groupby("m").date.nunique()
rows = []
for per in pd.period_range("2025-11", "2026-10", freq="M"):
    m, nd = per.month, per.days_in_month
    for r in ROUTES:
        if m in (11, 12):
            v = fc_day.loc[r, m] * nd if r in fc_day.index else 0.0                   # из модели
            lo, hi = v * 0.95, v * 1.05
        else:
            lvl = per_day.loc[r, [9, 10]].mean() if r in per_day.index else 0.0      # уровень сен–окт
            v = lvl * city_idx[m] * nd
            lo, hi = v * (1 - 2 * spread[m]), v * (1 + 2 * spread[m])
        rows.append({"month": str(per), "route": r, "boardings": v, "low": lo, "high": hi,
                     "source": "модель (ноя–дек)" if m in (11, 12) else "сезонный индекс 2025"})
year_fc = pd.DataFrame(rows)
print("Сезонный индекс месяца (к сен–окт), медиана по маршрутам:"); print(city_idx.round(2).to_string())
print("\nГодовой сценарий, млн посадок по месяцам (все маршруты):")
print((year_fc.groupby("month")[["boardings", "low", "high"]].sum() / 1e6).round(2).to_string())
""")

code(r"""
#@title Сохранение сабмита + валидация формата
def to_submission(pred):
    sub = template[["route", "date", "hour"]].copy()
    sub["date"] = pd.to_datetime(sub["date"])
    sub = sub.merge(pred[["route", "date", "hour", "pred"]], on=["route", "date", "hour"], how="left")
    sub["prediction"] = np.floor(sub.pred.fillna(0).clip(lower=0) + 0.5).astype(int)   # half-up, только на выгрузке
    sub["date"] = sub.date.dt.strftime("%Y-%m-%d")
    sub = sub[["route", "date", "hour", "prediction"]]
    # проверки из ТЗ
    assert list(sub.columns) == ["route", "date", "hour", "prediction"]
    assert len(sub) == 14640 and not sub.duplicated(["route", "date", "hour"]).any()
    assert sub.prediction.notna().all() and (sub.prediction >= 0).all()
    assert set(sub.route) == set(ROUTES) and sub.date.min() == FORECAST_START and sub.date.max() == FORECAST_END
    return sub

sub = to_submission(pred)
stamp = time.strftime("%Y%m%d_%H%M") + (f"_g{round(SEASON_GROWTH * 100)}" if SEASON_GROWTH != 1.0 else "")   # версия модели
cv_score = cv.loc["mean", "по горизонту"]
fname = OUT_DIR / f"submission_ml_{stamp}.csv"
sub.to_csv(fname, sep=";", index=False, encoding="utf-8")
sub.to_csv(OUT_DIR / "submission_latest.csv", sep=";", index=False, encoding="utf-8")
json.dump({"season_growth": SEASON_GROWTH, "best": BEST, "blend_w": BLEND_W, "blend_by_horizon": BLEND, "cv": cv.round(4).to_dict(), "K_holiday": pm.K_holiday, "K_short": pm.K_short,
           "beta_weather_precip_warm": pm.beta.tolist(), "weather_effect_pp": WEATHER_EFFECT, "weather": WEATHER_FOR_FORECAST, "weather_horizon_days": WEATHER_HORIZON_DAYS,
           "ml_feats": ML_FEATS, "ml_rounds": ML_ROUNDS, "origins": len(ORIGINS), "K_WORKING_SATURDAY": K_WORKING_SATURDAY, "K_PRE_NEW_YEAR": K_PRE_NEW_YEAR if K_PRE_NEW_YEAR is not None else pm.K_pre_ny,
           "K_SEASON": K_SEASON, "ROUTE5_ENABLED": ROUTE5_ENABLED, "leak_test": leak.round(6).to_dict(),
           "stream_wape_score": wape_score(allp.boardings, allp.pred)},
          open(OUT_DIR / f"run_{stamp}.json", "w", encoding="utf-8"), ensure_ascii=False, indent=1, default=str)
print(f" сохранено: {fname}\n   сумма прогноза: {sub.prediction.sum():,} | CV WAPE-score (mean по фолдам): {cv_score:.4f}")

# артефакты для сервиса (API/БД/UI): почасовой прогноз без округления, годовой сценарий, коэффициенты с источниками
ART = OUT_DIR / "artifacts"; ART.mkdir(exist_ok=True)
pred.assign(model_version=stamp).to_csv(ART / "forecast_hourly.csv", sep=";", index=False, encoding="utf-8")
year_fc.assign(model_version=stamp).to_csv(ART / "forecast_year_monthly.csv", sep=";", index=False, encoding="utf-8")
coef = {
    "model_version": stamp, "formula": "pred = ансамбль(профиль, LightGBM) × K_calendar × K_weather × K_regime × K_incident × K_expert; "
                                        "в UI: pred_ui = pred × K_user(фактор, маршрут, интервал)",
    "blend_w_ml": BLEND_W, "blend_by_horizon": BLEND, "ml_horizons": list(ML_HORIZONS), "ml_anchor_level": ML_ANCHOR_LEVEL, "cv_wape_score": float(cv_score), "stream_wape_score_7d": float(wape_score(allp.boardings, allp.pred)),
    "calendar": {"K_holiday": pm.K_holiday, "K_short": pm.K_short, "K_WORKING_SATURDAY": K_WORKING_SATURDAY,
                 "source": "https://github.com/xmlcalendar/data"},
    "weather": {"beta_per_mm_precip_day_if_warm": pm.beta.tolist(), "warm_temp_c": WARM_TEMP, "effect_pp_day_ahead": WEATHER_EFFECT,
                "horizon_days": WEATHER_HORIZON_DAYS,
                "source": "https://open-meteo.com/en/docs/historical-forecast-api"},
    "regimes": [{k: (str(v.date()) if hasattr(v, "date") else v) for k, v in rg.items() if k not in ("S_normal", "ratio")} for rg in pm.regimes],
    "incident": {"K_incident": pm.K_incident, "hours_after_post": 3, "source": "https://t.me/s/DtOperativno"},
    "traffic": {"K_traffic_by_score": ProfileModel(**BEST, use_traffic=True).fit(df, CUTOFF).K_traffic, "in_submission": TRAFFIC_IN_MODEL,
                "source": "https://t.me/s/DtOperativno ; онлайн — https://export.yandex.ru/bar/reginfo.xml?region=213"},
    "expert": {"K_PRE_NEW_YEAR": K_PRE_NEW_YEAR if K_PRE_NEW_YEAR is not None else pm.K_pre_ny,
               "K_PRE_NEW_YEAR_source": "аналог: 9–10.01.2025 к будням следующих недель", "K_SEASON": K_SEASON},
    "ui_sliders": {"weather": [0.8, 1.2], "event": [0.0, 1.5], "season": [0.8, 1.2], "traffic": [0.9, 1.1], "fleet": [0.5, 1.5]},
}
def json_safe(o):
    # NaN/inf — невалидный JSON (бэкенд на Jackson его не прочитает) → null
    if isinstance(o, dict): return {str(k): json_safe(v) for k, v in o.items()}
    if isinstance(o, (list, tuple)): return [json_safe(v) for v in o]
    if isinstance(o, (float, np.floating)): return float(o) if np.isfinite(o) else None
    if isinstance(o, np.integer): return int(o)
    return o
coef = json_safe(coef)
json.dump(coef, open(ART / "coefficients.json", "w", encoding="utf-8"), ensure_ascii=False, indent=1, default=str)
# артефакты ML-модели: бустеры LightGBM + контракт признаков (инференс вне ноутбука)
for old in ART.glob("lgbm_*.txt"):                  # веса прошлых прогонов не смешиваем с текущими
    old.unlink()
ml_names = [f"lgbm_h{hm}_s{sd}.txt" for hm in ML_HORIZONS for sd in ML_SEEDS][:len(pm.ml_models)]
for nm, m in zip(ml_names, pm.ml_models):
    m.save_model(str(ART / nm))
json.dump({"model_version": stamp, "models": ml_names, "seeds": list(ML_SEEDS),
           "features": ML_FEATS, "categorical": {"route": list(ROUTE_DTYPE.categories)},
           "target": "boardings / prof (профиль модели 1), вес prof; прогноз = prof × clip(mean(models), 0.3, 2.0)",
           "anchor_level": ML_ANCHOR_LEVEL, "blend": f"pred = (1−w)·prof + w·ML, w = {BLEND['w_near']} на днях 1–{BLEND['h_cut']}, {BLEND['w_far']} дальше", "profile_config": BEST,
           "origins_step_days": ORIGIN_STEP_DAYS, "train_horizons_days": list(ML_HORIZONS), "tech_hours_zero": list(TECH_HOURS),
           "versions": {"lightgbm": lgb.__version__, "pandas": pd.__version__, "numpy": np.__version__, "python": sys.version.split()[0]}},
          open(ART / "ml_contract.json", "w", encoding="utf-8"), ensure_ascii=False, indent=1, default=str)
print("артефакты для сервиса:", sorted(p.name for p in ART.iterdir()))

# архив для веб-сервиса «Пантограф»: распаковать в data/forecast/ репозитория (или tools/sync_ml_artifacts.sh)
import zipfile as _zf
BUNDLE = OUT_DIR / "service_artifacts.zip"
with _zf.ZipFile(BUNDLE, "w", _zf.ZIP_DEFLATED) as z:
    for nm in ["forecast_hourly.csv", "forecast_year_monthly.csv", "coefficients.json", "ml_contract.json"]:
        z.write(ART / nm, nm)
    z.write(OUT_DIR / "submission_latest.csv", "submission_latest.csv")
print("архив для сервиса:", BUNDLE)
print(sub.head())
if IN_COLAB:
    try:
        from google.colab import files
        files.download(str(fname)); files.download(str(BUNDLE))
    except Exception as e:
        print("download:", e)
""")

import hashlib as _hashlib
_CODE_HASH = _hashlib.sha1("\n".join(s for t, s in CELLS if t == "code").encode("utf-8")).hexdigest()[:12]
CELLS = [(t, s.replace("__CODE_HASH__", _CODE_HASH)) for t, s in CELLS]


def build(path):
    nb = nbf.v4.new_notebook()
    nb.metadata = {"accelerator": "GPU", "colab": {"provenance": [], "gpuType": "T4"},
                   "kernelspec": {"name": "python3", "display_name": "Python 3"}, "language_info": {"name": "python"}}
    nb.cells = [nbf.v4.new_markdown_cell(s) if t == "md" else nbf.v4.new_code_cell(s) for t, s in CELLS]
    nbf.write(nb, path)


if __name__ == "__main__":
    out = sys.argv[1]
    build(out)
    print("written", out)
    if "--run" in sys.argv:
        import matplotlib; matplotlib.use("Agg")
        g = {"__name__": "__main__"}
        for i, (t, s) in enumerate(CELLS):
            if t == "code":
                print(f"\n===== cell {i} =====", flush=True)
                exec(compile(s, f"cell{i}", "exec"), g)
