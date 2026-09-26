"""Verify the ML/service forecast contract without loading the raw dataset."""
from __future__ import annotations
import csv
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FORECAST = ROOT / "data" / "forecast"

def read_file(path: Path, fields: set[str], value_field: str, cast):
    with path.open(encoding="utf-8-sig", newline="") as fh:
        rows = csv.DictReader(fh, delimiter=";")
        if set(rows.fieldnames or ()) != fields:
            raise AssertionError(f"unexpected header in {path}: {rows.fieldnames}")
        out = {}
        for row in rows:
            key = (int(row["route"]), row["date"], int(row["hour"]))
            value = cast(row[value_field])
            if not 0 <= key[2] <= 23 or key in out:
                raise AssertionError(f"invalid or duplicate row: {row}")
            out[key] = value
        return out

def main() -> None:
    submission = read_file(FORECAST / "submission_latest.csv", {"route", "date", "hour", "prediction"}, "prediction", int)
    hourly = read_file(FORECAST / "forecast_hourly.csv", {"route", "date", "hour", "pred", "model_version"}, "pred", float)
    if len(submission) != 14640 or len(hourly) != 14640:
        raise AssertionError(f"expected 14640 keys, got submission={len(submission)}, hourly={len(hourly)}")
    if set(submission) != set(hourly):
        raise AssertionError("submission and hourly forecast keys differ")
    for key, value in hourly.items():
        if not math.isfinite(value) or value < 0 or submission[key] != math.floor(value + 0.5):
            raise AssertionError(f"invalid service parity at {key}: {value} vs {submission[key]}")
    print(f"PASS: {len(submission)} keys, non-negative values, service parity 0 mismatches")

if __name__ == "__main__":
    main()

