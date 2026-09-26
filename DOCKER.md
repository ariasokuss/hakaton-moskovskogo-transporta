# Docker release runbook

## Prerequisites

- Docker Desktop with the Linux engine running.
- At least 4 GB RAM assigned to Docker Desktop.
- Ports `3000`, `5432`, and `8080` free.

The raw organizer dataset is optional for the demo. The repository already contains the forecast and aggregated load artifacts required for startup. If the dataset is available, set `DATASET_DIR` to its extracted `dataset` directory.

## Clean start

```powershell
docker compose down -v
docker compose up -d --build
docker compose ps
powershell -ExecutionPolicy Bypass -File perf/smoke_test.ps1
```

Open `http://localhost:3000/?date=2025-11-08` after the backend health check becomes `UP`.

## Useful checks

```powershell
curl.exe http://localhost:8080/actuator/health
curl.exe http://localhost:8080/
python -X utf8 tools/verify_forecast_artifacts.py
```

## ML environment

The production containers do not install Python dependencies. For notebook training and raw-data ingestion on the host:

```powershell
py -3.11 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
```

The 10 GB raw dataset is intentionally excluded from Git. Keep it outside the repository and pass it with `DATASET_DIR` when needed.
