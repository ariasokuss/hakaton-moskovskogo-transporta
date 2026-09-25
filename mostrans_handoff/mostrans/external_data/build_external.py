"""Сборка внешних данных (поправочные факторы) и замер их эффекта на labels.

Внешние данные используются ТОЛЬКО как поправки к базовой модели,
обученной на основном датасете (train+test). Target из внешних источников не берётся.

Запуск: python external_data/build_external.py   (из корня проекта, dataset.zip рядом)
Сеть нужна только для перекачки сырья (--download).
"""
import io
import json
import sys
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
LAT, LON = 55.7558, 37.6173  # центр Москвы
HOURLY = "temperature_2m,apparent_temperature,precipitation,rain,snowfall,snow_depth,cloud_cover,wind_speed_10m,weather_code"
URLS = {
    "weather_archive_2025_hourly.json": "https://archive-api.open-meteo.com/v1/archive?latitude={lat}&longitude={lon}&start_date=2025-01-01&end_date=2025-12-31&hourly={h}&timezone=Europe%2FMoscow",
    "weather_hist_forecast_2025_hourly.json": "https://historical-forecast-api.open-meteo.com/v1/forecast?latitude={lat}&longitude={lon}&start_date=2025-01-01&end_date=2025-12-31&hourly={h}&timezone=Europe%2FMoscow",
    "xmlcalendar_ru_2025.xml": "https://raw.githubusercontent.com/xmlcalendar/data/master/ru/2025/calendar.xml",
    "xmlcalendar_ru_2026.xml": "https://raw.githubusercontent.com/xmlcalendar/data/master/ru/2026/calendar.xml",
}

# Школьные каникулы Москвы (четвертная система, рекомендации Минпросвещения / ДОНМ).
# 2025/26: https://www.banki.ru/wikibank/shkolnye_kanikuly_2025_2026/
# 2024/25: https://www.rbc.ru/life/news/650956c49a7947086a7f6d64
SCHOOL_HOLIDAYS = [
    ("2024-12-29", "2025-01-08", "зимние 2024/25"),
    ("2025-03-22", "2025-03-30", "весенние 2024/25"),
    ("2025-05-27", "2025-08-31", "летние 2025"),
    ("2025-10-25", "2025-11-02", "осенние 2025/26"),
    ("2025-12-31", "2026-01-11", "зимние 2025/26"),
]
# Вторые осенние каникулы при триместрах (часть школ) — отдельный слабый флаг
SCHOOL_TRIMESTER = [("2025-11-15", "2025-11-23", "осенние-2 (триместры)")]


def download():
    for name, url in URLS.items():
        url = url.format(lat=LAT, lon=LON, h=HOURLY)
        print("GET", url)
        (HERE / name).write_bytes(urllib.request.urlopen(url, timeout=120).read())


def build_calendar():
    days = pd.DataFrame({"date": pd.date_range("2025-01-01", "2025-12-31")})
    days["dow"] = days.date.dt.dayofweek
    days["is_weekend"] = (days.dow >= 5).astype(int)
    days["is_holiday"] = 0
    days["is_short_workday"] = 0
    days["is_working_weekend"] = 0
    days["holiday_name"] = ""
    root = ET.parse(HERE / "xmlcalendar_ru_2025.xml").getroot()
    names = {h.get("id"): h.get("title") for h in root.iter("holiday")}
    idx = days.set_index(days.date.dt.strftime("%m.%d")).index
    for d in root.iter("day"):
        i = np.where(idx == d.get("d"))[0][0]
        t = d.get("t")
        if t == "1":
            days.loc[i, "is_holiday"] = 1
            days.loc[i, "holiday_name"] = names.get(d.get("h"), "перенос выходного")
        elif t == "2":
            days.loc[i, "is_short_workday"] = 1
            if days.loc[i, "dow"] >= 5:
                days.loc[i, "is_working_weekend"] = 1
        elif t == "3":
            days.loc[i, "is_working_weekend"] = 1
    days["is_day_off"] = (((days.is_weekend == 1) & (days.is_working_weekend == 0)) | (days.is_holiday == 1)).astype(int)
    # тип дня для профилей: workday / short / sat / sun / holiday
    days["day_type"] = np.select(
        [days.is_holiday == 1, days.is_working_weekend == 1, days.is_short_workday == 1, days.dow == 5, days.dow == 6],
        ["holiday", "working_weekend", "short_workday", "saturday", "sunday"], "workday")
    days["school_holiday"] = 0
    days["school_holiday_name"] = ""
    for a, b, n in SCHOOL_HOLIDAYS:
        m = days.date.between(a, b)
        days.loc[m, "school_holiday"] = 1
        days.loc[m, "school_holiday_name"] = n
    days["school_holiday_trimester"] = 0
    for a, b, n in SCHOOL_TRIMESTER:
        days.loc[days.date.between(a, b), "school_holiday_trimester"] = 1
    ny = pd.Timestamp("2026-01-01")
    days["days_to_new_year"] = (ny - days.date).dt.days
    days["pre_new_year_week"] = days.date.between("2025-12-24", "2025-12-31").astype(int)
    days["date"] = days.date.dt.strftime("%Y-%m-%d")
    days.to_csv(HERE / "calendar_2025.csv", sep=";", index=False, encoding="utf-8")
    return days


def build_weather():
    out = {}
    for tag, fn in [("fact", "weather_archive_2025_hourly.json"), ("fcst", "weather_hist_forecast_2025_hourly.json")]:
        h = json.loads((HERE / fn).read_text())["hourly"]
        w = pd.DataFrame(h)
        w["time"] = pd.to_datetime(w.time)
        w.insert(0, "date", w.time.dt.strftime("%Y-%m-%d"))
        w.insert(1, "hour", w.time.dt.hour)
        w = w.drop(columns="time")
        w.to_csv(HERE / f"weather_{tag}_hourly_2025.csv", sep=";", index=False)
        out[tag] = w
    return out


def load_labels():
    with zipfile.ZipFile(ROOT / "dataset.zip") as z:
        dfs = [pd.read_csv(io.BytesIO(z.read(f"labels/labels_day_{p}.csv")), sep=";") for p in ("train", "test")]
    return pd.concat(dfs)


def wape_score(y, p):
    return 1 - np.abs(y - p).sum() / y.sum()


def measure(cal, weather):
    """Эффект поправок: базовая модель = медиана той же (маршрут, день недели, час)
    за соседние 4 недели ±; поправка = коэффициент, оцененный на других днях (leave-one-out по датам)."""
    lab = load_labels()
    routes = sorted(lab.route.unique())
    grid = pd.MultiIndex.from_product([routes, pd.date_range("2025-01-01", "2025-10-31").strftime("%Y-%m-%d"), range(24)],
                                      names=["route", "date", "hour"]).to_frame(index=False)
    df = grid.merge(lab, how="left").fillna({"boardings": 0})
    df = df.merge(cal, on="date")
    daily = df.groupby(["route", "date"]).boardings.sum().unstack(0)
    daily.index = pd.to_datetime(daily.index)
    dow = daily.index.dayofweek
    # baseline: медиана того же дня недели за ±4 недели (без самого дня)
    base = pd.DataFrame(index=daily.index, columns=daily.columns, dtype=float)
    for d in daily.index:
        nb = [d + pd.Timedelta(weeks=k) for k in (-4, -3, -2, -1, 1, 2, 3, 4)]
        nb = [x for x in nb if x in daily.index]
        base.loc[d] = daily.loc[nb].median()
    c = cal.assign(date=pd.to_datetime(cal.date)).set_index("date").loc[daily.index]
    ratio = (daily.sum(1) / base.sum(1))
    rows = []

    def report(name, mask, note):
        r = ratio[mask]
        rows.append({"factor": name, "n_days": int(mask.sum()), "mean_ratio_to_same_dow": round(r.mean(), 3),
                     "median_ratio": round(r.median(), 3), "note": note})

    report("holiday (производственный календарь)", (c.is_holiday == 1) & (dow < 5), "праздник в будний день / обычный будний")
    report("holiday on weekend", (c.is_holiday == 1) & (dow >= 5), "праздник в выходной / обычный выходной")
    report("short_workday", (c.is_short_workday == 1) & (dow < 5), "предпраздничный будний")
    report("school_holiday weekday (без лета и НГ)",
           (c.school_holiday == 1) & (dow < 5) & (c.is_holiday == 0) & ~c.school_holiday_name.str.contains("летние|зимние"),
           "каникулы в будни")
    report("normal weekday", (c.is_day_off == 0) & (c.school_holiday == 0) & (c.is_short_workday == 0), "контроль ~1.0")

    # WAPE-эффект календарной поправки на тренировочных данных (дневной уровень, почасово)
    hourly_share = df[df.is_day_off == 0].groupby(["route", "hour"]).boardings.sum()
    abl = []
    for use_cal in (False, True):
        pred = base.copy()
        if use_cal:
            for mask in [(c.is_holiday == 1) & (dow < 5), (c.is_holiday == 1) & (dow >= 5), (c.is_short_workday == 1) & (dow < 5)]:
                m = mask.values
                for i in np.where(m)[0]:
                    others = m.copy(); others[i] = False  # leave-one-out
                    k = ratio[others].median() if others.any() else 1.0
                    pred.iloc[i] = base.iloc[i] * k
        y, p = daily.values.ravel(), pred.values.astype(float).ravel()
        ok = ~np.isnan(p)
        abl.append(wape_score(y[ok], p[ok]))
    rows.append({"factor": "ABLATION daily WAPE-score: base -> base*calendar(LOO)", "n_days": int(len(daily)),
                 "mean_ratio_to_same_dow": round(abl[0], 4), "median_ratio": round(abl[1], 4),
                 "note": "до / после календарной поправки"})

    # Погода: остаток дневного объёма vs погодные признаки (будни без праздников)
    for tag, w in weather.items():
        wd = w.groupby("date").agg(temp=("temperature_2m", "mean"), precip=("precipitation", "sum"),
                                   snow=("snowfall", "sum"), snow_depth=("snow_depth", "mean"))
        wd.index = pd.to_datetime(wd.index)
        wd = wd.loc[daily.index]
        m = (c.is_day_off == 0) & (c.is_short_workday == 0) & (c.school_holiday == 0)
        for col, thr, label in [("precip", 5, "осадки >5 мм/сут"), ("snow", 2, "снег >2 см/сут"), ("temp", -10, "t<-10°C")]:
            flag = (wd[col] > thr) if col != "temp" else (wd[col] < thr)
            report(f"weather[{tag}] {label} (будни)", m & flag, "vs будни без события")
        yy = np.log(ratio[m].astype(float))
        X = wd.loc[m, ["temp", "precip", "snow"]]
        X = (X - X.mean()) / X.std()
        beta = np.linalg.lstsq(np.c_[np.ones(len(X)), X.values], yy.values, rcond=None)[0]
        rows.append({"factor": f"weather[{tag}] OLS log-ratio ~ temp+precip+snow (std coef)", "n_days": int(m.sum()),
                     "mean_ratio_to_same_dow": "; ".join(f"{n}={b:+.4f}" for n, b in zip(["temp", "precip", "snow"], beta[1:])),
                     "median_ratio": "", "note": "эффект на 1 std признака, в долях объёма"})
    rep = pd.DataFrame(rows)
    rep.to_csv(HERE / "effects_report.csv", sep=";", index=False, encoding="utf-8")
    print(rep.to_string())


if __name__ == "__main__":
    if "--download" in sys.argv:
        download()
    cal = build_calendar()
    weather = build_weather()
    measure(cal, weather)
