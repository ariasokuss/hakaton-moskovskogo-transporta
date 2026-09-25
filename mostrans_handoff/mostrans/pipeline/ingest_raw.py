"""Пайплайн приёма и нормализации сырых валидаций → почасовая целевая величина (критерий 2в).

Вход: папка с train.csv / test.csv (Kaggle-датасет shotme/moscow-transport, структура как в dataset.zip)
      или сам dataset.zip (читается потоково, без распаковки).
Выход (в --out):
  hourly.parquet  — route, date, hour, boardings (успешные валидации), validations (все попытки),
                    failed, vehicles (разных бортов), exits (разных выходов) — почасовая витрина для модели и сервиса;
  ingest_report.json — сверка с labels организаторов (совпадение ключей и значений, суммы).

Правила нормализации (из README датасета):
  1. время события — tran_date_time (input_date_time битый, не используется);
  2. посадка — validation_result == 1;
  3. маршрут — число из ngpt_route («25 трамвай» → 25), только трамваи;
  4. «хвост» следующего месяца в файлах отсекается по дате события: train — янв–авг, test — сен–окт;
  5. дубликаты транзакций (tran_no + device_no + время) удаляются.

Запуск:  python pipeline/ingest_raw.py --src <папка или dataset.zip> --out pipeline/out
Движок: DuckDB для папки с CSV (минуты на 10 ГБ), pandas-чанки для zip (медленнее, без распаковки).
"""
import argparse
import json
import time
import zipfile
from pathlib import Path

import pandas as pd

PERIODS = {"train.csv": ("2025-01-01", "2025-08-31"), "test.csv": ("2025-09-01", "2025-10-31")}
ROUTES = [1, 5, 7, 11, 12, 17, 25, 26, 28, 50]


def ingest_duckdb(src: Path) -> pd.DataFrame:
    import duckdb
    parts = []
    for name, (a, b) in PERIODS.items():
        sql = f"""
        WITH raw AS (
            SELECT DISTINCT tran_no, device_no, TRY_CAST(tran_date_time AS TIMESTAMP) AS ts,
                   validation_result, ngpt_route, bus_exit_no, garage_number
            FROM read_csv('{(src / name).as_posix()}', delim=';', header=true, all_varchar=true)
            WHERE ngpt_route LIKE '%трамвай%' AND TRY_CAST(tran_date_time AS TIMESTAMP) IS NOT NULL
        )
        SELECT CAST(regexp_extract(ngpt_route, '^(\\d+)', 1) AS INTEGER) AS route,
               CAST(ts AS DATE) AS date, hour(ts) AS hour,
               count(*) FILTER (WHERE validation_result = '1') AS boardings,
               count(*) AS validations,
               count(*) FILTER (WHERE validation_result <> '1') AS failed,
               count(DISTINCT garage_number) AS vehicles,
               count(DISTINCT bus_exit_no) AS exits
        FROM raw
        WHERE CAST(ts AS DATE) BETWEEN DATE '{a}' AND DATE '{b}'
        GROUP BY 1, 2, 3
        """
        t0 = time.time()
        parts.append(duckdb.sql(sql).df())
        print(f"{name}: {len(parts[-1]):,} строк витрины за {time.time() - t0:.0f} с", flush=True)
    return pd.concat(parts, ignore_index=True)


def ingest_zip(src: Path, chunksize=2_000_000) -> pd.DataFrame:
    cols = ["tran_no", "device_no", "tran_date_time", "validation_result", "ngpt_route", "bus_exit_no", "garage_number"]
    aggs = []
    with zipfile.ZipFile(src) as z:
        for name, (a, b) in PERIODS.items():
            t0, n = time.time(), 0
            seen = set()
            for ch in pd.read_csv(z.open(name), sep=";", usecols=cols, dtype=str, chunksize=chunksize):
                n += len(ch)
                ch = ch[ch.ngpt_route.str.contains("трамвай", na=False)]
                key = ch.tran_no + "|" + ch.device_no + "|" + ch.tran_date_time
                dup = key.isin(seen)
                seen.update(key[~dup])
                ch = ch[~dup]
                ts = pd.to_datetime(ch.tran_date_time, errors="coerce")
                ch = ch.assign(route=ch.ngpt_route.str.extract(r"^(\d+)", expand=False).astype(float),
                               date=ts.dt.normalize(), hour=ts.dt.hour, ok=(ch.validation_result == "1").astype(int))
                ch = ch[ch.date.between(a, b)]
                g = ch.groupby(["route", "date", "hour"])
                aggs.append(pd.DataFrame({"boardings": g.ok.sum(), "validations": g.size(),
                                          "vehicles_set": g.garage_number.agg(lambda x: set(x.dropna())),
                                          "exits_set": g.bus_exit_no.agg(lambda x: set(x.dropna()))}))
                print(f"  {name}: {n:,} событий, {time.time() - t0:.0f} с", flush=True)
    a = pd.concat(aggs)
    g = a.groupby(level=[0, 1, 2])
    out = pd.DataFrame({"boardings": g.boardings.sum(), "validations": g.validations.sum(),
                        "vehicles": g.vehicles_set.agg(lambda s: len(set().union(*s))),
                        "exits": g.exits_set.agg(lambda s: len(set().union(*s)))}).reset_index()
    out["failed"] = out.validations - out.boardings
    out["route"] = out.route.astype(int)
    return out


def validate(hourly: pd.DataFrame, labels: pd.DataFrame) -> dict:
    labels = labels.assign(date=pd.to_datetime(labels.date))
    h = hourly.assign(date=pd.to_datetime(hourly.date))
    m = labels.merge(h[["route", "date", "hour", "boardings"]], on=["route", "date", "hour"], how="outer",
                     suffixes=("_labels", "_pipeline"), indicator=True)
    both = m[m._merge == "both"]
    return {
        "labels_rows": int(len(labels)), "pipeline_rows": int(len(h)),
        "keys_in_both": int(len(both)),
        "keys_only_in_labels": int((m._merge == "left_only").sum()),
        "keys_only_in_pipeline": int((m._merge == "right_only").sum()),
        "exact_value_match_share": round(float((both.boardings_labels == both.boardings_pipeline).mean()), 6),
        "sum_labels": int(labels.boardings.sum()), "sum_pipeline": int(h.boardings.sum()),
        "abs_diff_share_of_sum": round(float((both.boardings_labels - both.boardings_pipeline).abs().sum() / labels.boardings.sum()), 8),
    }


def run(src, out, labels=None):
    src, out = Path(src), Path(out)
    out.mkdir(parents=True, exist_ok=True)
    t0 = time.time()
    hourly = ingest_zip(src) if src.suffix == ".zip" else ingest_duckdb(src)
    hourly = hourly[hourly.route.isin(ROUTES)].sort_values(["route", "date", "hour"], ignore_index=True)
    hourly.to_parquet(out / "hourly.parquet", index=False)
    rep = {"source": str(src), "seconds": round(time.time() - t0, 1), "rows": int(len(hourly))}
    if labels is None:
        if src.suffix == ".zip":
            with zipfile.ZipFile(src) as z:
                labels = pd.concat([pd.read_csv(z.open(f"labels/labels_day_{p}.csv"), sep=";") for p in ("train", "test")])
        elif (src / "labels").exists():
            labels = pd.concat([pd.read_csv(src / "labels" / f"labels_day_{p}.csv", sep=";") for p in ("train", "test")])
    if labels is not None:
        rep["validation_vs_labels"] = validate(hourly, labels)
    json.dump(rep, open(out / "ingest_report.json", "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(json.dumps(rep, ensure_ascii=False, indent=1))
    return hourly, rep


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", required=True, help="папка с train.csv/test.csv или dataset.zip")
    ap.add_argument("--out", default="pipeline/out")
    a = ap.parse_args()
    run(a.src, a.out)
