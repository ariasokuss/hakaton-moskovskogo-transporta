"""Парсер публичного Telegram-канала Дептранса Москвы (@DtRoad) -> события/перекрытия и баллы пробок ЦОДД.

Данные используются ТОЛЬКО как поправочные коэффициенты к базовой модели (не как target).

Источник: https://t.me/s/DtRoad  (публичная веб-версия, без авторизации; пагинация ?before=<post_id>)

Использование (из корня проекта):
    python external_data/parse_deptrans_tg.py crawl      # скачать посты за период -> deptrans_posts_raw.jsonl (докачка поддерживается)
    python external_data/parse_deptrans_tg.py extract    # jsonl -> events_2025.csv, traffic_scores_2025.csv
    python external_data/parse_deptrans_tg.py measure    # замер эффекта на labels -> effects_tg_report.csv
    python external_data/parse_deptrans_tg.py all        # всё подряд
Параметры: --channel DtRoad --start 2025-01-01 --end 2025-12-31 --sleep 0.7
"""
import argparse
import html
import json
import re
import time
import urllib.request
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
RAW = HERE / "deptrans_posts_raw.jsonl"


def set_channel(channel):
    """Сырьё и выходы для канала: DtRoad -> прежние имена, иначе с префиксом канала."""
    global RAW, PREFIX
    RAW = HERE / ("deptrans_posts_raw.jsonl" if channel == "DtRoad" else f"{channel}_posts_raw.jsonl")
    PREFIX = "" if channel == "DtRoad" else f"{channel}_"


PREFIX = ""
TARGET_ROUTES = {1, 5, 7, 11, 12, 17, 25, 26, 28, 50}
MONTHS = {"январ": 1, "феврал": 2, "март": 3, "апрел": 4, "ма": 5, "июн": 6, "июл": 7, "август": 8,
          "сентябр": 9, "октябр": 10, "ноябр": 11, "декабр": 12}
MONTH_RE = r"(январ[ья]|феврал[ья]|марта?|апрел[ья]|мая|июн[ья]|июл[ья]|августа?|сентябр[ья]|октябр[ья]|ноябр[ья]|декабр[ья])"

CATEGORIES = {  # категория -> регулярка (по lower-тексту)
    "closure": r"(перекро|перекрыт|закрыт\w* движени|закроют|ограничен\w* движени|временно закрыт)",
    "tram_works": r"(трамва\w*.{0,80}(ремонт|путев|путей|реконструкц|работ|изменени|не будут ходить|приостанов|отмен|компенсационн)|(ремонт|путев|путей|реконструкц).{0,80}трамва)",
    "tram_route_change": r"(трамва\w*.{0,60}(маршрут|№)|маршрут\w* трамва)",
    "mass_event": r"(забег|марафон|парад|фестивал|концерт|матч|выпускн|праздничн\w* мероприяти|салют|велопарад)",
    "weather_alert": r"(снегопад|ливень|сильный дождь|гололед|метел|аномальн\w* (холод|жар)|оранжев\w* уровень|желт\w* уровень)",
    "new_line": r"(нов\w* трамвайн\w* лини|открыл\w*.{0,40}трамва|запуст\w*.{0,40}трамва)",
}


# ---------------------------------------------------------------- crawl
def fetch_page(channel, before=None):
    url = f"https://t.me/s/{channel}" + (f"?before={before}" if before else "")
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    t = urllib.request.urlopen(req, timeout=30).read().decode("utf-8", "replace")
    posts = []
    for blk in t.split('class="tgme_widget_message_wrap')[1:]:
        i = re.search(r'data-post="[^/]+/(\d+)"', blk)
        d = re.search(r'datetime="([^"]+)"', blk)
        m = re.search(r'(?s)tgme_widget_message_text[^>]*>(.*?)</div>', blk)
        if not (i and d):
            continue
        txt = ""
        if m:
            txt = re.sub(r"<br\s*/?>", "\n", m.group(1))
            txt = html.unescape(re.sub(r"<[^>]+>", "", txt)).strip()
        posts.append({"id": int(i.group(1)), "datetime_utc": d.group(1), "text": txt,
                      "url": f"https://t.me/{channel}/{i.group(1)}"})
    return posts


def crawl(channel, start, end, sleep):
    seen = {}
    if RAW.exists():  # докачка
        for line in RAW.open(encoding="utf-8"):
            p = json.loads(line)
            seen[p["id"]] = p
    start_ts = pd.Timestamp(start, tz="Europe/Moscow")
    end_ts = pd.Timestamp(end, tz="Europe/Moscow") + pd.Timedelta(days=1)
    # точка входа: бинарный поиск id, соответствующего end
    lo, hi = 1, fetch_page(channel)[-1]["id"] + 1
    while hi - lo > 20:
        mid = (lo + hi) // 2
        p = fetch_page(channel, mid)
        if not p or pd.Timestamp(p[-1]["datetime_utc"]) < end_ts:
            lo = mid
        else:
            hi = mid
        time.sleep(sleep)
    before = hi + 20
    if seen:
        before = min(before, min(seen) + 1) if min(seen) > 0 and pd.Timestamp(seen[min(seen)]["datetime_utc"]) > start_ts else before
    n_new, fails = 0, 0
    with RAW.open("a", encoding="utf-8") as f:
        while True:
            try:
                page = fetch_page(channel, before)
                fails = 0
            except Exception as e:  # сеть/лимит — пауза и повтор
                fails += 1
                print("retry", before, e)
                if fails > 5:
                    break
                time.sleep(10 * fails)
                continue
            if not page:  # Telegram иногда отдаёт пустую страницу — повторяем, а не выходим
                fails += 1
                if fails > 5:
                    break
                time.sleep(10 * fails)
                continue
            for p in page:
                if p["id"] not in seen:
                    seen[p["id"]] = p
                    f.write(json.dumps(p, ensure_ascii=False) + "\n")
                    n_new += 1
            oldest = min(page, key=lambda x: x["id"])
            print(f"before={before} oldest={oldest['datetime_utc'][:10]} total={len(seen)}", flush=True)
            if pd.Timestamp(oldest["datetime_utc"]) < start_ts:
                break
            before = oldest["id"]
            time.sleep(sleep)
    print("new posts:", n_new)


# ---------------------------------------------------------------- extract
def mentioned_dates(text, year):
    """'с 12 по 14 июля', '12 и 13 июля', '12 июля' -> список дат."""
    out = set()
    low = text.lower()
    for a, b, mon in re.findall(r"с\s+(\d{1,2})\s+(?:по|до)\s+(\d{1,2})\s+" + MONTH_RE, low):
        m = next(v for k, v in MONTHS.items() if mon.startswith(k))
        for d in range(int(a), int(b) + 1):
            try:
                out.add(pd.Timestamp(year, m, d))
            except ValueError:
                pass
    for d, mon in re.findall(r"(\d{1,2})\s+" + MONTH_RE, low):
        m = next(v for k, v in MONTHS.items() if mon.startswith(k))
        try:
            out.add(pd.Timestamp(year, m, int(d)))
        except ValueError:
            pass
    return sorted(x.strftime("%Y-%m-%d") for x in out)


def tram_routes(text):
    low = text.lower()
    if "трамва" not in low:
        return []
    nums = set()
    # «№ 7», «№7 и 50», «маршрутах 7, 50 и 12», «17-й трамвай», «трамваи 7 и 50»
    for lst in re.findall(r"(?:№\s*|маршрут\w*\s+(?:№\s*)?|трамва\w*\s+(?:№\s*)?)(\d{1,2}(?:\s*(?:,|и)\s*(?:№\s*)?\d{1,2})*)(?!\s*(?:\d|тыс|млн|км|мин|%|ваг|ретро|плат|дн|год|лет|раз|час))", low):
        nums |= {int(n) for n in re.findall(r"\d{1,2}", lst)}
    nums |= {int(n) for n in re.findall(r"\b(\d{1,2})-?(?:й|го|му|м)\s+(?:маршрут|трамва)", low)}
    return sorted(nums & TARGET_ROUTES)


def extract(start, end):
    posts = pd.read_json(RAW, lines=True).drop_duplicates("id")
    posts["dt"] = pd.to_datetime(posts.datetime_utc, utc=True).dt.tz_convert("Europe/Moscow")
    posts = posts[(posts.dt >= pd.Timestamp(start, tz="Europe/Moscow")) &
                  (posts.dt < pd.Timestamp(end, tz="Europe/Moscow") + pd.Timedelta(days=1))].sort_values("dt")
    posts["post_date"] = posts.dt.dt.strftime("%Y-%m-%d")
    posts["post_hour"] = posts.dt.dt.hour
    low = posts.text.str.lower()

    # --- события
    ev = []
    for (_, r), l in zip(posts.iterrows(), low):
        cats = [c for c, rx in CATEGORIES.items() if re.search(rx, l)]
        if not cats:
            continue
        ev.append({"post_id": r.id, "post_date": r.post_date, "post_hour": r.post_hour,
                   "categories": "|".join(cats), "tram_related": int("трамва" in l),
                   "target_routes": "|".join(map(str, tram_routes(r.text))),
                   "event_dates": "|".join(mentioned_dates(r.text, r["dt"].year)),
                   "url": r.url, "text": r.text.replace("\n", " ")[:600]})
    ev = pd.DataFrame(ev)
    ev.to_csv(HERE / f"{PREFIX}events_2025.csv", sep=";", index=False, encoding="utf-8")

    # --- баллы пробок ЦОДД: «оценивается в 5 баллов» (fact), «до 7 баллов», «ожидаем 8 баллов» (forecast)
    tr = []
    for (_, r), l in zip(posts.iterrows(), low):
        if not re.search(r"(цодд|дорог|пробк|движени|загруженност)", l):
            continue
        for pre, s in re.findall(r"([^.\n]{0,60}?)(\d{1,2})\s*балл", l):
            if 0 <= int(s) <= 10 and "парков" not in pre:
                kind = "forecast" if re.search(r"(до\s*$|ожида|прогноз|достигн|может|будет|вырастет|возраст)", pre) else "fact"
                part = ("evening" if re.search(r"вечер", pre + l[:200]) else
                        "morning" if re.search(r"утр", pre + l[:200]) else "")
                tr.append({"post_id": r.id, "date": r.post_date, "hour": r.post_hour, "score": int(s),
                           "kind": kind, "part_of_day": part, "url": r.url})
    tr = pd.DataFrame(tr, columns=["post_id", "date", "hour", "score", "kind", "part_of_day", "url"]).drop_duplicates(["post_id", "score"])
    tr.to_csv(HERE / f"{PREFIX}traffic_scores_2025.csv", sep=";", index=False, encoding="utf-8")
    print(f"posts={len(posts)} events={len(ev)} (tram={ev.tram_related.sum()}, with_routes={(ev.target_routes != '').sum()}) "
          f"traffic_scores={len(tr)} days_with_score={tr.date.nunique() if len(tr) else 0}")


# ---------------------------------------------------------------- measure
def measure():
    import sys
    sys.path.insert(0, str(HERE))
    from build_external import load_labels, wape_score  # noqa
    lab = load_labels()
    cal = pd.read_csv(HERE / "calendar_2025.csv", sep=";")
    daily = lab.groupby(["route", "date"]).boardings.sum().unstack(0).fillna(0)
    daily = daily.reindex(pd.date_range("2025-01-01", "2025-10-31").strftime("%Y-%m-%d"), fill_value=0)
    idx = pd.to_datetime(daily.index)
    base = pd.DataFrame(index=daily.index, columns=daily.columns, dtype=float)
    for j, d in enumerate(idx):
        nb = [(d + pd.Timedelta(weeks=k)).strftime("%Y-%m-%d") for k in (-4, -3, -2, -1, 1, 2, 3, 4)]
        nb = [x for x in nb if x in daily.index]
        base.iloc[j] = daily.loc[nb].median().values
    ratio_tot = daily.sum(1) / base.sum(1)
    ratio_rt = daily / base
    c = cal.set_index("date").loc[daily.index]
    normal = (c.is_day_off == 0) & (c.is_short_workday == 0) & (c.school_holiday == 0)
    rows = []

    # трафик: баллы ЦОДД из всех собранных каналов (DtRoad + DtOperativno)
    tr = pd.concat([pd.read_csv(f, sep=";") for f in sorted(HERE.glob("*traffic_scores_2025.csv"))], ignore_index=True)
    dmax = tr.groupby("date").score.max().reindex(daily.index)
    # вечерний пик 16–20 ч: объём vs медиана того же дня недели ±4 нед.
    eve = lab[lab.hour.between(16, 20)].groupby(["route", "date"]).boardings.sum().unstack(0).reindex(daily.index).fillna(0)
    eve_base = pd.DataFrame(index=eve.index, columns=eve.columns, dtype=float)
    for j, d in enumerate(idx):
        nb = [(d + pd.Timedelta(weeks=k)).strftime("%Y-%m-%d") for k in (-4, -3, -2, -1, 1, 2, 3, 4)]
        eve_base.iloc[j] = eve.loc[[x for x in nb if x in eve.index]].median().values
    ratio_eve = eve.sum(1) / eve_base.sum(1)
    ev_scores = tr[tr.part_of_day.fillna("").eq("evening")] if "part_of_day" in tr else tr.iloc[0:0]
    emax = ev_scores.groupby("date").score.max().reindex(daily.index)
    rows.append({"factor": "traffic: дней с баллом ЦОДД (все / вечерних)", "n": int(dmax.notna().sum()),
                 "ratio_vs_base": int(emax.notna().sum()), "control": ""})
    for name, s, rat in (("day max", dmax, ratio_tot), ("evening", emax, ratio_eve)):
        m = normal & s.notna()
        if m.sum() < 10:
            continue
        rho = np.corrcoef(s[m], rat[m])[0, 1]
        rows.append({"factor": f"traffic[{name}] corr(score, ratio) будни", "n": int(m.sum()), "ratio_vs_base": round(rho, 3), "control": ""})
        for lo_, hi_ in ((0, 5), (6, 7), (8, 10)):
            mm = m & s.between(lo_, hi_)
            rows.append({"factor": f"traffic[{name}] score {lo_}-{hi_} (будни): ratio", "n": int(mm.sum()),
                         "ratio_vs_base": round(rat[mm].mean(), 3) if mm.any() else None, "control": ""})
        # ablation: K_traffic(bucket) LOO -> WAPE на пиковых часах (evening) или сутках (day)
        y = (eve if name == "evening" else daily).sum(1).values.astype(float)
        p0 = (eve_base if name == "evening" else base).sum(1).values.astype(float)
        p1 = p0.copy()
        bucket = pd.cut(s, [-1, 5, 7, 10], labels=False).values
        mv = m.values
        for i in np.where(mv)[0]:
            same = mv & (bucket == bucket[i]); same[i] = False
            if same.any():
                p1[i] = p0[i] * np.median(rat.values[same])
        rows.append({"factor": f"ABLATION traffic[{name}] WAPE-score на днях с баллом: base -> base*K_traffic(LOO)",
                     "n": int(mv.sum()), "ratio_vs_base": round(wape_score(y[mv], p0[mv]), 4),
                     "control": round(wape_score(y[mv], p1[mv]), 4)})

    # трафик почасово: посадки за 3 часа от момента поста с баллом vs медиана тех же часов ±4 нед. (рабочие дни)
    hh = lab.groupby(["date", "hour"]).boardings.sum().unstack().reindex(daily.index).fillna(0)
    hb = hh.copy() * np.nan
    for j, d in enumerate(idx):
        nb = [(d + pd.Timedelta(weeks=k)).strftime("%Y-%m-%d") for k in (-4, -3, -2, -1, 1, 2, 3, 4)]
        hb.iloc[j] = hh.loc[[x for x in nb if x in hh.index]].median().values
    work = c.is_day_off.eq(0) & c.is_short_workday.eq(0)
    obs = []
    for _, r in tr[tr.date.isin(daily.index)].iterrows():
        if not work[r.date]:
            continue
        hs = [x for x in range(int(r.hour), int(r.hour) + 3) if x < 24 and x in hh.columns]
        b = hb.loc[r.date, hs].sum()
        if b > 0:
            obs.append((r.score, hh.loc[r.date, hs].sum() / b, int(r.hour)))
    o = pd.DataFrame(obs, columns=["score", "ratio", "hour"]).drop_duplicates()
    if len(o) > 10:
        rows.append({"factor": "traffic[hourly +3h] corr(score, ratio) рабочие дни", "n": len(o),
                     "ratio_vs_base": round(o[["score", "ratio"]].corr().iloc[0, 1], 3), "control": ""})
        ev_o = o[o.hour >= 15]
        rows.append({"factor": "traffic[hourly +3h, вечер >=15ч] corr(score, ratio)", "n": len(ev_o),
                     "ratio_vs_base": round(ev_o[["score", "ratio"]].corr().iloc[0, 1], 3), "control": ""})
        for lo_, hi_ in ((0, 4), (5, 5), (6, 7), (8, 10)):
            g = o[o.score.between(lo_, hi_)]
            rows.append({"factor": f"traffic[hourly +3h] score {lo_}-{hi_}: median ratio", "n": len(g),
                         "ratio_vs_base": round(g.ratio.median(), 3) if len(g) else None, "control": ""})

    # события: день события для маршрута (упомянут в посте) vs прочие дни маршрута
    ev = pd.read_csv(HERE / "events_2025.csv", sep=";", dtype=str).fillna("")
    hits = []
    for _, r in ev.iterrows():
        dates = r.event_dates.split("|") if r.event_dates else [r.post_date]
        for d in dates:
            if d in daily.index:
                hits.append((d, r.categories, r.target_routes, int(r.tram_related)))
    h = pd.DataFrame(hits, columns=["date", "cats", "routes", "tram"])
    # 1) перекрытия/массовые события — общий эффект на трамвай
    for cat in ("closure", "mass_event", "tram_works"):
        ds = set(h[h.cats.str.contains(cat)].date)
        m = normal & daily.index.isin(ds)
        rows.append({"factor": f"events[{cat}] дни события (будни)", "n": int(m.sum()),
                     "ratio_vs_base": round(ratio_tot[m].mean(), 3) if m.any() else None,
                     "control": round(ratio_tot[normal & ~daily.index.isin(ds)].mean(), 3)})
    # 2) события с явным упоминанием нашего маршрута
    vals, ctrl = [], []
    for _, r in h[h.routes != ""].iterrows():
        for rt in map(int, r.routes.split("|")):
            if rt in ratio_rt.columns and not np.isnan(ratio_rt.loc[r.date, rt]):
                vals.append(ratio_rt.loc[r.date, rt])
    rows.append({"factor": "events с упоминанием целевого маршрута: ratio маршрута", "n": len(vals),
                 "ratio_vs_base": round(float(np.mean(vals)), 3) if vals else None, "control": 1.0})
    # 2b) только работы/перекрытия с упоминанием маршрута: ablation WAPE по (маршрут, день), LOO
    hw = h[(h.routes != "") & h.cats.str.contains("tram_works|closure")]
    keys = sorted({(r.date, int(rt)) for _, r in hw.iterrows() for rt in r.routes.split("|")
                   if int(rt) in ratio_rt.columns})
    kv = {k: ratio_rt.loc[k[0], k[1]] for k in keys}
    kv = {k: v for k, v in kv.items() if np.isfinite(v)}
    if kv:
        yb = daily.values.astype(float); pb = base.values.astype(float); pa = pb.copy()
        for k in kv:
            k_loo = np.median([v for kk, v in kv.items() if kk != k]) if len(kv) > 1 else 1.0
            i, j = daily.index.get_loc(k[0]), list(daily.columns).index(k[1])
            pa[i, j] = pb[i, j] * k_loo
        ok = np.isfinite(pb)
        sel = np.zeros_like(ok)
        for k in kv:
            sel[daily.index.get_loc(k[0]), list(daily.columns).index(k[1])] = True
        rows.append({"factor": "events[tram_works|closure] с маршрутом: median ratio маршрута", "n": len(kv),
                     "ratio_vs_base": round(float(np.median(list(kv.values()))), 3), "control": 1.0})
        rows.append({"factor": "ABLATION WAPE-score на затронутых (маршрут,день): base -> base*K_event(LOO)", "n": len(kv),
                     "ratio_vs_base": round(wape_score(yb[sel], pb[sel]), 4), "control": round(wape_score(yb[sel], pa[sel]), 4)})
        rows.append({"factor": "ABLATION WAPE-score все (маршрут,день): base -> base*K_event(LOO)", "n": int(ok.sum()),
                     "ratio_vs_base": round(wape_score(yb[ok], pb[ok]), 4), "control": round(wape_score(yb[ok], pa[ok]), 4)})

    # ablation: поправка на дни событий (коэф. = медиана LOO по категории) -> дневной WAPE
    y = daily.values.astype(float)
    p0 = base.values.astype(float)
    p1 = p0.copy()
    for cat in ("closure", "mass_event", "tram_works"):
        ds = daily.index.isin(set(h[h.cats.str.contains(cat)].date)) & normal.values
        for i in np.where(ds)[0]:
            oth = ds.copy(); oth[i] = False
            if oth.any():
                p1[i] *= np.nanmedian(ratio_tot.values[oth])
    ok = ~np.isnan(p0)
    rows.append({"factor": "ABLATION daily WAPE-score base -> base*events(LOO)", "n": int(len(daily)),
                 "ratio_vs_base": round(wape_score(y[ok], p0[ok]), 4), "control": round(wape_score(y[ok], p1[ok]), 4)})
    rep = pd.DataFrame(rows)
    rep.to_csv(HERE / "effects_tg_report.csv", sep=";", index=False, encoding="utf-8")
    print(rep.to_string())


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["crawl", "extract", "measure", "all"])
    ap.add_argument("--channel", default="DtRoad")
    ap.add_argument("--start", default="2025-01-01")
    ap.add_argument("--end", default="2025-12-31")
    ap.add_argument("--sleep", type=float, default=0.7)
    a = ap.parse_args()
    set_channel(a.channel)
    if a.cmd in ("crawl", "all"):
        crawl(a.channel, a.start, a.end, a.sleep)
    if a.cmd in ("extract", "all"):
        extract(a.start, a.end)
    if a.cmd in ("measure", "all"):
        measure()
