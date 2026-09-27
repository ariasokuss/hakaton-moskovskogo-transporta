# Локальный запуск

Два независимых контура:

| Контур | Что нужно | Время |
|---|---|---|
| **Сервис** «Пантограф» (backend + frontend + PostgreSQL) | Docker Desktop / Docker Engine с Compose | сборка ~5–10 мин, старт ~1 мин |
| **ML** (обучение модели и сабмит) | Python 3.10+ | ~25–40 мин на CPU |

---

## 1. Сервис в Docker

```bash
git clone https://github.com/shotmee/moscow_transport.git
cd moscow_transport
docker compose up -d --build
```

| Что | Адрес |
|---|---|
| Дашборд диспетчера | http://localhost:3000/?date=2025-11-08 |
| API (оглавление методов) | http://localhost:8080/ |
| Проверка живости | http://localhost:8080/actuator/health |
| PostgreSQL | `localhost:5432`, база / пользователь / пароль — `tram` |

Сервис поднимается **без датасета организаторов**: исторический факт берётся из `data/load/load_hourly.csv`,
прогноз — из `data/forecast/`, геометрия маршрутов — из `data/geo/`, внешние источники — из `ml/external_data/`.
Чтобы подключить полный датасет, распакуйте `dataset.zip` и укажите путь:

```bash
DATASET_DIR=/путь/к/dataset docker compose up -d
```

Проверки после старта:

```bash
curl http://localhost:8080/actuator/health          # {"status":"UP"} после загрузки данных (~1 мин)
python tools/verify_artifacts.py                     # прогноз ML = БД = выгрузка сервиса (нужен Python 3.9+)
bash perf/smoke_test.sh                              # основные эндпоинты и коды ошибок
```

Остановить: `docker compose down` (данные БД сохраняются в volume `pgdata`; полный сброс — `docker compose down -v`).

Ресурсы контейнеров заданы в `docker-compose.yml`: backend 2 vCPU / 2 ГБ, PostgreSQL 1 vCPU / 1 ГБ,
frontend 0.5 vCPU / 256 МБ. Замеры производительности — `perf/README.md`.

---

## 2. ML локально

### Установка

```bash
python -m venv .venv
# Windows: .venv\Scripts\activate        Linux/macOS: source .venv/bin/activate
pip install -r requirements.txt
python ml/load_models.py                 # проверка весов финального прогона
```

### Данные

Положите архив организаторов `dataset.zip` (ссылка из ТЗ: https://disk.yandex.ru/d/DiFwlfMOauxjBg)
в каталог `ml/` — рядом с `ml/external_data/`. Ноутбук читает labels прямо из zip, распаковывать не нужно.

### Запуск ноутбука

Переменная `LOCAL_DATA_DIR` включает локальный режим: датасет — `ml/dataset.zip`, внешние данные — `ml/external_data/`,
результаты — `ml/submissions/` (каталоги `ml/submissions/` и `ml/checkpoints/` создаются при первом запуске).

```bash
# интерактивно
LOCAL_DATA_DIR=ml jupyter lab ml/pantograph_best_colab.ipynb

# без интерфейса, с сохранением выполненного ноутбука
LOCAL_DATA_DIR=ml jupyter nbconvert --to notebook --execute --ExecutePreprocessor.timeout=-1 \
    ml/pantograph_best_colab.ipynb --output pantograph_best_executed.ipynb
```

Windows PowerShell: `$env:LOCAL_DATA_DIR = "ml"`, затем команду `jupyter …`.

Результат: `ml/submissions/submission_ml_<время>.csv` и `ml/submissions/artifacts/` (веса, прогноз для сервиса).
Чтобы показать новый прогноз в сервисе — скопируйте `ml/submissions/artifacts/*` в `data/forecast/`
и выполните `docker compose restart backend`.

### Пересобрать ноутбук из исходника

```bash
python ml/tools/build_nb_best.py ml/pantograph_best_colab.ipynb
```

### Пайплайн приёма сырых валидаций (опционально, ~10 ГБ)

```bash
python ml/pipeline/ingest_raw.py --help
```

Читает `train.csv` / `test.csv` через DuckDB, строит почасовую витрину `route × date × hour` и сверяет её с labels организаторов.
