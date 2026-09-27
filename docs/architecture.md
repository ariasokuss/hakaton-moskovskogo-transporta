# Архитектура и модули

Схема соответствует критерию 3 ТЗ: **приём/нормализация → признаки/геопривязка → ML-прогноз/агрегация → API → frontend**.
Каждый этап — отдельный модуль с явной границей (файл-контракт или интерфейс сервиса), а не сквозные вызовы.

## 1. Модули и потоки данных

```mermaid
flowchart LR
    subgraph SRC["Источники"]
        DS[("dataset.zip<br/>валидации 10 ГБ, labels,<br/>справочники")]
        EXT[("Внешние: xmlcalendar, Open-Meteo,<br/>Telegram Дептранса, ЦОДД, Яндекс, OSM")]
    end

    subgraph ML["ML-контур · Python (Colab)"]
        ING["pipeline/ingest_raw.py<br/>приём и нормализация<br/>сверка с labels 100%"]
        FEAT["признаки: профиль, календарь,<br/>погода ≤7 дн, режимы, сбои"]
        MODEL["3×3 LightGBM + профиль<br/>ансамбль 80/20, привязка уровня"]
        ART[/"артефакты: forecast_hourly.csv,<br/>forecast_year_monthly.csv,<br/>coefficients.json, ml_contract.json"/]
        ING --> FEAT --> MODEL --> ART
    end

    subgraph BE["Backend · Java 21, Spring Boot 4, WebFlux/Netty"]
        BI["ingest/<br/>DataIngestService — факт, нагрузка, импорт прогона<br/>ExternalDataService — схема external.*"]
        BF["features/<br/>GeometryService — трассы, остановки,<br/>доли остановок (OSM)"]
        BFC["forecast/<br/>GridStore — снимок в памяти<br/>ForecastQueryService — агрегация, k*, отклонения<br/>ForecastRunService — годовой прогон (async)"]
        BA["api/ — REST, RFC 9457"]
        BX["export/ — CSV, XLSX, сабмит"]
        BI --> BFC
        BF --> BFC
        BFC --> BA
        BFC --> BX
    end

    DB[("PostgreSQL 17<br/>core.* — данные организаторов<br/>external.* — внешние, с source_url")]
    FE["Frontend · React 19, TS, MapLibre<br/>рабочее место диспетчера"]

    DS --> ING
    DS -->|labels, load_hourly.csv| BI
    EXT --> FEAT
    EXT -->|снимок external_data/| BI
    ART -->|data/forecast/| BI
    BI <-->|R2DBC| DB
    BFC <-->|R2DBC, только при смене прогона| DB
    BA -->|JSON| FE
    BX -->|файлы| FE
```

| Этап ТЗ | Где | Граница (контракт) |
|---|---|---|
| Приём и нормализация | `ml/pipeline/ingest_raw.py` (сырые 62 млн событий → `route × date × hour`), `tools/build_load.sh`, `backend/.../ingest/` | `labels/*.csv`, `data/load/load_hourly.csv`: `route;date;hour;boardings;load` |
| Признаки и геопривязка | ноутбук ML (календарь, погода, режимы, сбои), `backend/.../features/GeometryService` (трассы и остановки OSM, доли остановок) | `data/geo/routes_osm.json`, схема `external.*` |
| ML-прогноз и агрегация | ноутбук `ml/pantograph_best_colab.ipynb` → артефакты; `backend/.../forecast/` агрегирует час → день → месяц → год | `data/forecast/forecast_hourly.csv`: `route;date;hour;pred;model_version` + `ml_contract.json` |
| API | `backend/.../api/`, `backend/.../export/` | REST, JSON, RFC 9457 ([README §9](../README.md#9-api)) |
| Frontend | `frontend/` | те же query-параметры, что в API: состояние экрана = ссылка |

## 2. Путь запроса

```mermaid
sequenceDiagram
    participant U as Диспетчер (браузер)
    participant N as nginx (frontend)
    participant A as API (WebFlux/Netty)
    participant S as Снимок в памяти (GridStore)
    participant D as PostgreSQL
    U->>N: GET /api/dashboard?date=2025-11-08&kWeather=0.9
    N->>A: proxy
    A->>S: прогноз × k*, отклонение от обычного уровня
    Note over A,S: инференса модели и обращений к БД в пути запроса нет
    A-->>U: один ответ на весь экран (gzip, кэш готовых байтов)
    loop раз в 30 с
        S->>D: есть ли новый успешный прогон?
        D-->>S: run_id → перечитать снимок атомарно
    end
```

- **Прогноз предрассчитан.** Модель не в горячем пути: p95 определяется выборкой из памяти, а не инференсом.
- **Коэффициенты на выдаче.** `ŷ = прогноз модели × kWeather × kEvent × kSeason × kTraffic × kManual` — пересчёт без модели.
- **Прогоны иммутабельны.** Новая `model_version` → новый `forecast_run`, выдача переключается атомарно, старые прогоны остаются.
- **Stateless.** Состояние — только в PostgreSQL; инстансы взаимозаменяемы за балансировщиком.

## 3. Развёртывание

```mermaid
flowchart LR
    B[браузер] -->|:3000| F["frontend<br/>nginx + статика<br/>0.5 vCPU / 256 МБ"]
    F -->|/api| BE["backend ×N<br/>2 vCPU / 2 ГБ каждый"]
    BE --> P[("postgres<br/>1 vCPU / 1 ГБ")]
    BE -. фоном, таймаут 2 с .-> Y["export.yandex.ru<br/>балл пробок"]
```

`docker compose up -d` поднимает все три сервиса. Внешняя сеть не в критическом пути: при недоступности источника
сервис работает на снимке, коэффициент = 1, сбой виден в `external.fetch_log` и `GET /api/external`.

## 4. Хранилище

| Схема | Что | Откуда |
|---|---|---|
| `core.route`, `core.route_regime` | справочник маршрутов (цвет, подпись, часы работы), режимы — **данные, не код** | миграции Flyway V3–V6 |
| `core.actual_hourly` | факт `route × date × hour`: посадки и нагрузка с пересадками | labels организаторов или `load_hourly.csv` |
| `core.forecast_run`, `core.forecast_hourly` | прогоны прогноза (день — из ML, год — фоновый расчёт), дробные значения | артефакты ML, `ForecastRunService` |
| `external.*` | календарь, погода (факт и архив прогнозов), события, сбои, баллы пробок, журнал загрузок | `external_data/` ML-команды + онлайн Яндекс |

Внешние данные с данными организаторов в одной таблице не смешиваются: соединение — только на выдаче.
