# Evidence Pack For Jury

Run this evidence pack on the final `keshaptisa` commit. It separates reproducible evidence from design claims.

## Forecast contract

```powershell
python -X utf8 tools/verify_forecast_artifacts.py
```

This validates headers, duplicate keys, non-negative finite values, the 14,640-key grid, and parity between `submission_latest.csv` and rounded `forecast_hourly.csv`.

## Clean startup and API smoke test

```powershell
docker compose down -v
docker compose up -d --build
powershell -ExecutionPolicy Bypass -File perf/smoke_test.ps1
```

The smoke test covers health, metadata, routes, stops, geometry, all horizons, stop-level forecast, external context, CSV/XLSX export, and frontend.

## Performance acceptance boundary

The declared single-container working SLA is the 2,500 RPS mixed scenario: 2 vCPU, 2 GB RAM, p95 102 ms, 100% HTTP success, 68% CPU, and 1.72 GB RAM. The 3,000 and 3,500 RPS rows are stress-limit measurements and intentionally show the saturation boundary; they are not claimed as zero-error operation.

## External-source evidence

The final submission must include one fixed ablation table with identical chronological folds for every row:

```text
source;scope;without_score;with_score;delta_pp;period;protocol
calendar;holiday_fold;...;...;...;...;same fold and target
weather;day_ahead;...;...;...;...;forecast archive only
route_regimes;affected_routes;...;...;...;...;same route/date/hour grid
traffic;peak_and_congested_subset;...;...;...;...;predeclared subset
```

Traffic must not be claimed as a global accuracy improvement if it worsens the global score. It can receive source credit only when the predeclared operational subset shows an improvement and the subset, period, and protocol are reported.

## Honest limitations

- `0.89234` is the official leaderboard score for run `20260926_1142`.
- Stop-level values are allocated route forecasts, not observed stop-level labels, because `place_id` is not an actual stop identifier.
- The year horizon is a qualitative scenario; day and month are principal horizons.
- Traffic is available in the external-data layer and UI, but is not in the global ML blend when its ablation is negative.
