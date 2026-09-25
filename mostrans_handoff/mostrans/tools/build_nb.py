"""Собирает baseline_colab.ipynb из списка ячеек. Запуск с --run выполняет код локально (проверка)."""
import os
import sys
import nbformat as nbf

CELLS = []
def md(s): CELLS.append(("md", s.strip("\n")))
def code(s): CELLS.append(("code", s.strip("\n")))

md(r"""
# 🚊 Мострансхакатон — baseline прогноза посадок в трамваи (ноябрь–декабрь 2025)

**Задача:** `boardings(route, date, hour)` на сетку 10 маршрутов × 61 день × 24 часа. Метрика: `WAPE-score = 1 − Σ|y−ŷ|/Σy`.

**Схема (см. `ML_IDEAS.md`, `EXTERNAL_DATA.md`):**
```
ŷ = Profile(route, тип_дня, час)          ← базовая модель ТОЛЬКО на основном датасете
    × K_calendar × K_weather × K_season    ← внешние данные = поправочные коэффициенты
    × LightGBM-ratio (опционально)         ← нелинейная поправка на календарь/погоду
```
Прогноз **прямой (direct)** на весь горизонт — без рекурсии, ошибка не накапливается.

**Валидация:** rolling-origin фолды, горизонт как в задаче (до 61 дня), только прошлые данные.

**Входные данные:** папка на Google Drive с той же структурой, что и локальный проект:
```
<папка>/
├── dataset.zip              # оригинальный архив хакатона (читается без распаковки)
└── external_data/           # calendar_2025.csv, weather_*_hourly_2025.csv, events_2025.csv, ...
```
Запуск: `Runtime → Run all`. GPU (T4/A100) не обязателен — данные маленькие (~60k строк), всё считается на CPU за пару минут.
""")

code(r"""
#@title ⚙️ Конфиг
# --- откуда брать данные -------------------------------------------------------
# Вариант 1 (по умолчанию): папка на своём Google Drive (Drive монтируется)
DRIVE_DATA_DIR = "/content/drive/MyDrive/mostrans"   #@param {type:"string"}
# Вариант 2: публичная ссылка на папку Google Drive (скачается через gdown). Пусто = не использовать
DRIVE_FOLDER_URL = ""                                  #@param {type:"string"}

SEED = 42
ROUTES = [1, 5, 7, 11, 12, 17, 25, 26, 28, 50]
FORECAST_START, FORECAST_END = "2025-11-01", "2025-12-31"

# Экспертные коэффициенты (нет аналогов в истории) — те же «ручки», что будут в UI
K_WORKING_SATURDAY = 0.85   # рабочая суббота 01.11: будний профиль × K
K_PRE_NEW_YEAR = 0.92       # 29–30.12: будний профиль × K
K_SEASON = {11: 1.00, 12: 0.98}  # месячная поправка к уровню сен–окт (зима, предНГ)
""")

code(r"""
#@title 📦 Установка и импорты
import os, sys, io, time, json, zipfile, warnings, subprocess
from pathlib import Path
warnings.filterwarnings("ignore")

IN_COLAB = "google.colab" in sys.modules
if IN_COLAB:
    subprocess.run([sys.executable, "-m", "pip", "-q", "install", "lightgbm>=4.3", "gdown", "tqdm"], check=False)

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
#@title 📂 Подключение данных
if os.environ.get("LOCAL_DATA_DIR"):                     # локальный прогон (проверка ноутбука)
    DATA_DIR = Path(os.environ["LOCAL_DATA_DIR"])
elif DRIVE_FOLDER_URL:
    import gdown
    DATA_DIR = Path("/content/mostrans")
    if not (DATA_DIR / "dataset.zip").exists():
        gdown.download_folder(DRIVE_FOLDER_URL, output=str(DATA_DIR), quiet=False)
else:
    from google.colab import drive
    drive.mount("/content/drive")
    DATA_DIR = Path(DRIVE_DATA_DIR)

EXT = DATA_DIR / "external_data"
OUT_DIR = DATA_DIR / "submissions"; OUT_DIR.mkdir(exist_ok=True, parents=True)
assert (DATA_DIR / "dataset.zip").exists(), f"нет {DATA_DIR/'dataset.zip'}"
assert EXT.exists(), f"нет папки {EXT}"
print("DATA_DIR:", DATA_DIR)
print(sorted(p.name for p in EXT.iterdir()))
""")

code(r"""
#@title 📥 Загрузка: labels, шаблон сабмита, внешние данные
Z = zipfile.ZipFile(DATA_DIR / "dataset.zip")
parts = []
for p in tqdm(["train", "test"], desc="labels"):
    parts.append(pd.read_csv(Z.open(f"labels/labels_day_{p}.csv"), sep=";"))
lab = pd.concat(parts, ignore_index=True)
lab["date"] = pd.to_datetime(lab["date"])
template = pd.read_csv(Z.open("test_submission.csv"), sep=";")

# полная сетка route × date × hour на историю (пропуски = 0 посадок)
hist_dates = pd.date_range("2025-01-01", "2025-10-31")
grid = pd.MultiIndex.from_product([ROUTES, hist_dates, range(24)], names=["route", "date", "hour"]).to_frame(index=False)
df = grid.merge(lab, how="left", on=["route", "date", "hour"]).fillna({"boardings": 0})

cal = pd.read_csv(EXT / "calendar_2025.csv", sep=";", parse_dates=["date"])
w_fact = pd.read_csv(EXT / "weather_fact_hourly_2025.csv", sep=";", parse_dates=["date"])
w_fcst = pd.read_csv(EXT / "weather_fcst_hourly_2025.csv", sep=";", parse_dates=["date"])
events = pd.read_csv(EXT / "events_2025.csv", sep=";", dtype=str).fillna("")

print(f"labels: {len(lab):,} строк | сетка: {len(df):,} | заполнено нулями: {(len(df)-len(lab)):,}")
print(f"шаблон сабмита: {template.shape} | календарь: {cal.shape} | погода: {w_fact.shape} | события: {events.shape}")
""")

md("## 🔎 EDA")

code(r"""
#@title Маршруты: объём, покрытие, доля в WAPE
t = df.groupby("route").agg(total=("boardings", "sum"), nonzero_hours=("boardings", lambda s: (s > 0).sum()))
t["share_%"] = (100 * t.total / t.total.sum()).round(1)
display(t.sort_values("total", ascending=False)) if "display" in dir() else print(t)
print("⚠️ маршрут 5 в истории:", int(t.loc[5, "total"]), "посадок → прогноз 0 (возвращён только 17.12.2025, см. EXTERNAL_DATA.md)")
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
#@title Аномальные дни (>±40% от медианы того же дня недели ±4 нед.)
daily = df.groupby(["route", "date"]).boardings.sum().unstack(0)
def same_dow_median(s):
    out = pd.Series(index=s.index, dtype=float)
    for d0 in s.index:
        nb = [d0 + pd.Timedelta(weeks=k) for k in (-4, -3, -2, -1, 1, 2, 3, 4)]
        out[d0] = s.reindex(nb).median()
    return out
ratio = daily.apply(same_dow_median)
ratio = daily / ratio
anom = (ratio < 0.6) | (ratio > 1.4)
c = cal.set_index("date").reindex(daily.index)
anom_nonhol = anom & (c.is_holiday.values[:, None] == 0)
print("Аномальных (маршрут, день), не праздники:", int(anom_nonhol.sum().sum()))
print(anom_nonhol.sum().rename("дней").to_frame().T)
ANOMALY = anom_nonhol.stack().rename("anomaly").reset_index().rename(columns={"level_1": "route"})
ANOMALY = ANOMALY[ANOMALY.anomaly]

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
## 🧱 Модель 1: профиль × поправки

* **Профиль** `S[route, ptype, hour]` — агрегат за последние *N* «чистых» недель до отсечки (без праздников, аномалий и, опционально, летних каникул).
  `ptype`: `mon_thu / fri / sat / sun`. Праздник → профиль `sun` × `K_holiday`; рабочая суббота → `mon_thu` × `K_WORKING_SATURDAY`.
* **K_holiday, K_short** — оцениваются по истории до отсечки.
* **K_weather** — регрессия `log(факт/профиль)` на погоду по будням обучающего окна; на горизонте — **архив прогнозов** погоды.
""")

code(r"""
#@title Функции: метрика, типы дней, признаки
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

def daily_weather(w):
    g = w.groupby("date")
    return pd.DataFrame({"temp": g.temperature_2m.mean(), "precip": g.precipitation.sum(),
                         "snow": g.snowfall.sum(), "snow_depth": g.snow_depth.mean()})
WD_FACT, WD_FCST = daily_weather(w_fact), daily_weather(w_fcst)

anom_set = set(zip(ANOMALY.route, ANOMALY.date))
""")

code(r"""
#@title Класс ProfileModel
class ProfileModel:
    def __init__(self, n_weeks=6, agg="median", exclude_summer=True, use_weather=True, use_calendar=True):
        self.n_weeks, self.agg, self.exclude_summer = n_weeks, agg, exclude_summer
        self.use_weather, self.use_calendar = use_weather, use_calendar

    def _clean_days(self, cutoff):
        c = CAL.loc[:cutoff]
        ok = (c.is_holiday == 0) & (c.is_short_workday == 0) & (c.is_working_weekend == 0)
        if self.exclude_summer:
            ok &= ~c.school_holiday_name.fillna("").str.contains("летние")
        days = c.index[ok][-self.n_weeks * 7:]
        return days

    def fit(self, data, cutoff):
        cutoff = pd.Timestamp(cutoff)
        days = self._clean_days(cutoff)
        tr = data[data.date.isin(days)].copy()
        tr = tr[[(r, d) not in anom_set for r, d in zip(tr.route, tr.date)]]
        tr["ptype"] = ptype_of(pd.DatetimeIndex(tr.date)).values
        self.S = tr.groupby(["route", "ptype", "hour"]).boardings.agg(self.agg)
        # --- K по календарю: факт / профиль на праздниках и сокращённых днях до отсечки
        hist = data[data.date <= cutoff]
        self.K_holiday, self.K_short = 1.0, 1.0
        if self.use_calendar:
            self.K_holiday = self._k(hist, CAL.index[(CAL.is_holiday == 1) & (CAL.index <= cutoff)], default=0.82)
            self.K_short = self._k(hist, CAL.index[(CAL.is_short_workday == 1) & (CAL.dow < 5) & (CAL.index <= cutoff)], default=1.0)
        # --- K по погоде: OLS на будних днях обучающего окна (факт погоды)
        self.beta = np.zeros(3); self.w_mu = WD_FACT.loc[days, ["temp", "precip", "snow"]].mean()
        if self.use_weather:
            wd = [d for d in days if CAL.loc[d, "dow"] < 5]
            y = hist[hist.date.isin(wd)].groupby("date").boardings.sum()
            p = self._raw(pd.DatetimeIndex(wd)).groupby("date").pred.sum()
            X = WD_FACT.loc[wd, ["temp", "precip", "snow"]] - self.w_mu
            yy = np.log((y / p).clip(0.5, 1.5)).loc[wd]
            A = np.c_[np.ones(len(X)), X.values]
            coef = np.linalg.lstsq(A, yy.values, rcond=None)[0]
            self.beta = np.clip(coef[1:], -0.02, 0.02)   # не более ±2%/ед.: защита от переобучения
        return self

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
        if self.use_weather and weather is not None:
            X = weather.reindex(g.date)[["temp", "precip", "snow"]].fillna(self.w_mu) - self.w_mu
            k = k * np.exp(np.clip(X.values @ self.beta, -0.1, 0.1))
        g["pred"] = g.pred * k
        return g[["route", "date", "hour", "pred"]]
""")

code(r"""
#@title Фолды валидации
FOLDS = [  # (имя, отсечка, начало, конец)
    ("F1 авг→сен–окт (61д)", "2025-08-31", "2025-09-01", "2025-10-31"),
    ("F2 сен→окт (31д)",     "2025-09-30", "2025-10-01", "2025-10-31"),
    ("F3 мар→апр–май (61д, праздники)", "2025-03-31", "2025-04-01", "2025-05-31"),
]
def evaluate(make_model, folds=FOLDS, verbose=False, return_preds=False):
    res, preds = {}, {}
    for name, cut, a, b in folds:
        m = make_model().fit(df, cut)
        dates = pd.date_range(a, b)
        p = m.predict(dates, weather=WD_FCST)
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
#@title Подбор гиперпараметров профиля (прогресс-бар + метрика по ходу)
import itertools
space = list(itertools.product([3, 4, 6, 8, 10], ["median", "mean"], [True, False]))
rows = []
pbar = tqdm(space, desc="grid")
for n, agg, es in pbar:
    r = evaluate(lambda: ProfileModel(n, agg, es, use_weather=False, use_calendar=True))
    rows.append({"n_weeks": n, "agg": agg, "excl_summer": es, **r})
    best = max(rows, key=lambda x: x["mean"])
    pbar.set_postfix(best=f"{best['mean']:.4f}", cur=f"{r['mean']:.4f}")
grid_res = pd.DataFrame(rows).sort_values("mean", ascending=False)
print(grid_res.head(10).round(4).to_string(index=False))
BEST = grid_res.iloc[0][["n_weeks", "agg", "excl_summer"]].to_dict()
BEST["n_weeks"] = int(BEST["n_weeks"]); BEST["excl_summer"] = bool(BEST["excl_summer"])
print("BEST:", BEST)
""")

code(r"""
#@title Ablation внешних поправок (для критерия 2а)
abl = {}
for name, kw in tqdm([("profile only", dict(use_weather=False, use_calendar=False)),
                      ("+ calendar", dict(use_weather=False, use_calendar=True)),
                      ("+ calendar + weather", dict(use_weather=True, use_calendar=True))], desc="ablation"):
    abl[name] = evaluate(lambda: ProfileModel(BEST["n_weeks"], BEST["agg"], BEST["excl_summer"], **kw))
abl = pd.DataFrame(abl).T.round(4)
print(abl.to_string())
m_tmp = ProfileModel(BEST["n_weeks"], BEST["agg"], BEST["excl_summer"]).fit(df, "2025-10-31")
print(f"\nK_holiday={m_tmp.K_holiday:.3f}  K_short={m_tmp.K_short:.3f}  beta_weather(temp,precip,snow)={np.round(m_tmp.beta, 4)}")
""")

md("""
## 🌲 Модель 2: LightGBM-поправка к профилю

Цель — отношение `y / profile` (вес = profile ⇒ оптимизация эквивалентна L1 по посадкам, т.е. WAPE).
Признаки — только то, что известно заранее: час, маршрут, день недели, календарь, каникулы, дни до НГ, **прогноз** погоды по часам.
Прямой прогноз на весь горизонт, без лагов.
""")

code(r"""
#@title Признаки для LightGBM
WH_FACT = w_fact.set_index(["date", "hour"])
WH_FCST = w_fcst.set_index(["date", "hour"])
WCOLS = ["temperature_2m", "apparent_temperature", "precipitation", "snowfall", "snow_depth", "cloud_cover", "wind_speed_10m"]
CCOLS = ["dow", "is_holiday", "is_short_workday", "is_working_weekend", "is_day_off", "school_holiday", "pre_new_year_week"]

def make_features(frame, weather):
    X = frame[["route", "hour"]].copy()
    c = CAL.loc[frame.date]
    for col in CCOLS:
        X[col] = c[col].values
    X["days_to_ny"] = np.clip(c.days_to_new_year.values, 0, 90)
    w = weather.reindex(pd.MultiIndex.from_arrays([frame.date, frame.hour]))
    for col in WCOLS:
        X[col] = w[col].values
    X["route"] = X["route"].astype("category")
    return X

LGB_PARAMS = dict(objective="l1", learning_rate=0.03, num_leaves=31, min_data_in_leaf=100,
                  feature_fraction=0.8, bagging_fraction=0.8, bagging_freq=1, lambda_l2=1.0,
                  seed=SEED, verbose=-1, num_threads=os.cpu_count())
N_ROUNDS = 600

def fit_lgb(train_frame, prof_model):
    p = prof_model.predict(pd.DatetimeIndex(sorted(train_frame.date.unique())), weather=WD_FACT)
    t = train_frame.merge(p, on=["route", "date", "hour"])
    t = t[t.pred > 1]
    y = (t.boardings / t.pred).clip(0, 3)
    X = make_features(t, WH_FACT)
    ds = lgb.Dataset(X, y, weight=t.pred)
    bar = tqdm(total=N_ROUNDS, desc="lgb", leave=False)
    def cb(env): bar.update(1)
    model = lgb.train(LGB_PARAMS, ds, N_ROUNDS, callbacks=[cb]); bar.close()
    return model

def predict_lgb(model, prof_pred):
    X = make_features(prof_pred, WH_FCST)
    r = np.clip(model.predict(X), 0.3, 1.7)
    return np.where(prof_pred.pred.values > 1, prof_pred.pred.values * r, prof_pred.pred.values)
""")

code(r"""
#@title Валидация LightGBM и ансамбля по фолдам
LGB_TRAIN_START = "2025-01-09"   # без новогодних каникул в обучении отношения
rows = []
for name, cut, a, b in tqdm(FOLDS, desc="folds"):
    pm = ProfileModel(BEST["n_weeks"], BEST["agg"], BEST["excl_summer"]).fit(df, cut)
    train = df[(df.date >= LGB_TRAIN_START) & (df.date <= cut)]
    booster = fit_lgb(train, pm)
    dates = pd.date_range(a, b)
    pp = pm.predict(dates, weather=WD_FCST)
    pp["lgb"] = predict_lgb(booster, pp)
    y = df[df.date.isin(dates)].merge(pp, on=["route", "date", "hour"])
    r = {"fold": name, "profile": wape_score(y.boardings, y.pred), "lgb": wape_score(y.boardings, y.lgb)}
    for wgt in (0.3, 0.5, 0.7):
        r[f"blend{wgt}"] = wape_score(y.boardings, (1 - wgt) * y.pred + wgt * y.lgb)
    rows.append(r)
    print(f"{name}: profile={r['profile']:.4f}  lgb={r['lgb']:.4f}  blend0.5={r['blend0.5']:.4f}")
cv = pd.DataFrame(rows).set_index("fold")
cv.loc["mean"] = cv.mean()
print(cv.round(4).to_string())
BLEND_W = float(max([0.0, 0.3, 0.5, 0.7, 1.0], key=lambda w: cv.loc["mean", {0.0: "profile", 1.0: "lgb"}.get(w, f"blend{w}")]))
print("Вес LightGBM в ансамбле:", BLEND_W)
""")

code(r"""
#@title Ошибка по маршрутам и по дням горизонта (устойчивость многошагового прогноза)
_, P = evaluate(lambda: ProfileModel(BEST["n_weeks"], BEST["agg"], BEST["excl_summer"]), folds=FOLDS[:1], return_preds=True)
y = P[FOLDS[0][0]]
by_route = y.groupby("route").apply(lambda g: pd.Series({"WAPE-score": wape_score(g.boardings, g.pred) if g.boardings.sum() else np.nan,
                                                          "share_err_%": 100 * np.abs(g.boardings - g.pred).sum() / np.abs(y.boardings - y.pred).sum()}))
print(by_route.round(3).T)
dd = y.groupby("date").apply(lambda g: np.abs(g.boardings - g.pred).sum() / g.boardings.sum())
ax = (1 - dd).plot(figsize=(16, 3.5), title="F1: дневной WAPE-score по горизонту (прямой прогноз — ошибка не растёт с горизонтом)")
ax.axhline(0.88, ls="--", c="gray"); plt.show()
""")

md("## 🚀 Финальный прогноз на ноябрь–декабрь 2025 и сабмит")

code(r"""
#@title Обучение на всей истории (янв–окт) и прогноз
CUTOFF = "2025-10-31"
dates = pd.date_range(FORECAST_START, FORECAST_END)
steps = tqdm(total=4, desc="final")
pm = ProfileModel(BEST["n_weeks"], BEST["agg"], BEST["excl_summer"]).fit(df, CUTOFF); steps.update(1)
pred = pm.predict(dates, weather=WD_FCST); steps.update(1)
if BLEND_W > 0:
    booster = fit_lgb(df[(df.date >= LGB_TRAIN_START) & (df.date <= CUTOFF)], pm)
    pred["lgb"] = predict_lgb(booster, pred)
    pred["pred"] = (1 - BLEND_W) * pred.pred + BLEND_W * pred.lgb
steps.update(1)
# экспертные поправки без аналогов в истории
c = CAL.loc[pred.date]
pred.loc[c.pre_new_year_week.values.astype(bool) & (c.is_day_off.values == 0) & (pred.date >= "2025-12-29").values, "pred"] *= K_PRE_NEW_YEAR
pred["pred"] *= pred.date.dt.month.map(K_SEASON).values
pred.loc[pred.route == 5, "pred"] = 0.0     # маршрута 5 нет в истории
steps.update(1); steps.close()
print(f"K_holiday={pm.K_holiday:.3f} K_short={pm.K_short:.3f} beta={np.round(pm.beta, 4)} BLEND_W={BLEND_W}")
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
#@title 💾 Сохранение сабмита + валидация формата
sub = template[["route", "date", "hour"]].copy()
sub["date"] = pd.to_datetime(sub["date"])
sub = sub.merge(pred[["route", "date", "hour", "pred"]], on=["route", "date", "hour"], how="left")
sub["prediction"] = sub.pred.fillna(0).clip(lower=0).round().astype(int)
sub["date"] = sub.date.dt.strftime("%Y-%m-%d")
sub = sub[["route", "date", "hour", "prediction"]]

# проверки из ТЗ
assert list(sub.columns) == ["route", "date", "hour", "prediction"]
assert len(sub) == 14640 and not sub.duplicated(["route", "date", "hour"]).any()
assert sub.prediction.notna().all() and (sub.prediction >= 0).all()
assert set(sub.route) == set(ROUTES) and sub.date.min() == FORECAST_START and sub.date.max() == FORECAST_END

stamp = time.strftime("%Y%m%d_%H%M")
cv_score = cv.loc["mean", {0.0: "profile", 1.0: "lgb"}.get(BLEND_W, f"blend{BLEND_W}")]
fname = OUT_DIR / f"submission_baseline_{stamp}_cv{cv_score:.4f}.csv"
sub.to_csv(fname, sep=";", index=False, encoding="utf-8")
sub.to_csv(OUT_DIR / "submission_latest.csv", sep=";", index=False, encoding="utf-8")
json.dump({"best": BEST, "blend_w": BLEND_W, "cv": cv.round(4).to_dict(), "K_holiday": pm.K_holiday, "K_short": pm.K_short,
           "beta_weather": pm.beta.tolist(), "K_WORKING_SATURDAY": K_WORKING_SATURDAY, "K_PRE_NEW_YEAR": K_PRE_NEW_YEAR,
           "K_SEASON": K_SEASON}, open(OUT_DIR / f"run_{stamp}.json", "w", encoding="utf-8"), ensure_ascii=False, indent=1, default=str)
print(f"✅ сохранено: {fname}\n   сумма прогноза: {sub.prediction.sum():,} | CV WAPE-score (mean по фолдам): {cv_score:.4f}")
print(sub.head())
if IN_COLAB:
    try:
        from google.colab import files; files.download(str(fname))
    except Exception as e:
        print("download:", e)
""")


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
