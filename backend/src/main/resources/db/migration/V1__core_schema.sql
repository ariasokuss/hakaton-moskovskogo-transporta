-- Схема CORE: только данные организаторов (dataset.zip) и производные от них.
-- Внешние данные живут в схеме EXTERNAL и сюда не попадают (см. CLAUDE.md).

CREATE SCHEMA IF NOT EXISTS core;

-- ---------------------------------------------------------------------------
-- Справочники
-- ---------------------------------------------------------------------------

-- Маршрут. route_id = настоящий номер маршрута из ngpt_route ("25 трамвай" -> 25).
-- Представление (цвет, подпись, порядок) хранится здесь, а не во фронте:
-- обозначение обязано быть одинаковым на карте, в таблице, в графике и в экспорте.
CREATE TABLE core.route (
    route_id            smallint     PRIMARY KEY,
    short_name          varchar(8)   NOT NULL,          -- "17" — то, что видит диспетчер
    long_name           varchar(255),                   -- "Чертаново Южное — Москворецкий рынок"
    color_hex           char(7)      NOT NULL,          -- единый цвет маршрута во всех представлениях
    display_order       smallint     NOT NULL,
    -- Окно работы задаётся по маршруту: 1 и 28 с 5 ч, 25 с 6 ч, остальные 0-23.
    service_hour_start  smallint     NOT NULL DEFAULT 0 CHECK (service_hour_start BETWEEN 0 AND 23),
    service_hour_end    smallint     NOT NULL DEFAULT 23 CHECK (service_hour_end   BETWEEN 0 AND 23),
    -- Вместимость: в справочнике «Наряд» всего 17 строк, поэтому это настраиваемый
    -- параметр по умолчанию, а не факт. Используется для расчёта загрузки салона.
    default_capacity    integer,
    has_geometry        boolean      NOT NULL DEFAULT false,  -- координаты есть только у 1, 7, 11, 12
    is_active           boolean      NOT NULL DEFAULT true
);

CREATE TABLE core.stop (
    stop_id     integer       PRIMARY KEY,
    name        varchar(255)  NOT NULL,
    lat         numeric(9,6)  NOT NULL,
    lon         numeric(9,6)  NOT NULL,
    street      varchar(255),
    district    varchar(255)
);

CREATE TABLE core.route_stop (
    route_id       smallint  NOT NULL REFERENCES core.route(route_id),
    direction_id   smallint  NOT NULL,
    stop_sequence  smallint  NOT NULL,
    stop_id        integer   NOT NULL REFERENCES core.stop(stop_id),
    PRIMARY KEY (route_id, direction_id, stop_sequence)
);

-- ---------------------------------------------------------------------------
-- Факт
-- ---------------------------------------------------------------------------

-- Час — минимальная единица хранения. Всё остальное (день, месяц, год,
-- произвольный интервал) получается агрегацией вверх по этой таблице.
--
-- Две РАЗНЫЕ величины, их нельзя путать:
--   boardings — только validation_result = 1. Целевая величина, совпадает
--               с эталоном организаторов, идёт в submission и в WAPE.
--   load      — валидации по настраиваемому набору кодов (по умолчанию {1, 90}:
--               успешные + пересадки). Нагрузка на подвижной состав для диспетчера.
--               Пересадка — это реальный пассажир в салоне, она влияет только
--               на взаиморасчёты между операторами.
CREATE TABLE core.actual_hourly (
    route_id   smallint  NOT NULL REFERENCES core.route(route_id),
    fact_date  date      NOT NULL,
    hour       smallint  NOT NULL CHECK (hour BETWEEN 0 AND 23),
    boardings  integer   NOT NULL DEFAULT 0,
    load       integer,
    PRIMARY KEY (route_id, fact_date, hour)
);

CREATE INDEX idx_actual_date_hour ON core.actual_hourly (fact_date, hour);

-- ---------------------------------------------------------------------------
-- Прогноз: расчёт асинхронный и отвязан от выдачи
-- ---------------------------------------------------------------------------

-- Прогон расчёта. Долгий горизонт (год) считается отдельным заданием и
-- НЕ блокирует короткий: у каждого горизонта свои прогоны, идущие независимо.
-- Выдача всегда читает последний успешный прогон по нужному горизонту.
CREATE TABLE core.forecast_run (
    run_id         bigserial    PRIMARY KEY,
    horizon        varchar(8)   NOT NULL CHECK (horizon IN ('day','month','year')),
    status         varchar(12)  NOT NULL CHECK (status IN ('pending','running','succeeded','failed')),
    model_version  varchar(64)  NOT NULL,
    period_start   date         NOT NULL,
    period_end     date         NOT NULL,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    started_at     timestamptz,
    finished_at    timestamptz,
    error_message  text
);

CREATE INDEX idx_run_lookup ON core.forecast_run (horizon, status, finished_at DESC);

-- Прогноз иммутабелен: записи не обновляются, новый расчёт создаёт новый run.
-- Это позволяет сравнивать прогоны и считать фактическую точность задним числом.
--
-- prediction хранится ДРОБНЫМ. Округление к ближайшему целому выполняется
-- только на границе выгрузки: округлять до применения коэффициентов нельзя,
-- иначе ошибка копится на каждом множителе.
CREATE TABLE core.forecast_hourly (
    run_id         bigint        NOT NULL REFERENCES core.forecast_run(run_id) ON DELETE CASCADE,
    route_id       smallint      NOT NULL REFERENCES core.route(route_id),
    forecast_date  date          NOT NULL,
    hour           smallint      NOT NULL CHECK (hour BETWEEN 0 AND 23),
    prediction     numeric(12,3) NOT NULL CHECK (prediction >= 0),
    PRIMARY KEY (run_id, route_id, forecast_date, hour)
);

CREATE INDEX idx_forecast_serving ON core.forecast_hourly (run_id, forecast_date, route_id);

-- Базовый «обычный уровень» для сравнения: медиана того же дня недели
-- за +-4 недели. Считается заранее, чтобы отклонение не пересчитывалось
-- на каждый запрос. Отклонение считает сервер, а не фронт — иначе экран
-- и экспорт разойдутся.
CREATE TABLE core.baseline_hourly (
    route_id   smallint      NOT NULL REFERENCES core.route(route_id),
    day_of_week smallint     NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
    hour       smallint      NOT NULL CHECK (hour BETWEEN 0 AND 23),
    baseline   numeric(12,3) NOT NULL,
    PRIMARY KEY (route_id, day_of_week, hour)
);

-- ---------------------------------------------------------------------------
-- Режимы маршрута — адаптивность без пересборки
-- ---------------------------------------------------------------------------

-- Работы на путях, приостановка, смена уровня. Это ДАННЫЕ, а не константы в коде:
-- нашли новый выбитый период — добавили строку, а не пересобрали образ.
--
-- Примеры из данных (см. docs/data-anomalies.md):
--   маршрут 50, выходные с 06.09.2025 — приостановка (factor ~0.01);
--   маршрут 17, 12-13.04 и 26-27.04.2025 — работы по отдельным выходным;
--   маршруты 12 и 17, скачок уровня 07.04.2025 — данные до этой даты
--   непригодны для оценки уровня.
CREATE TABLE core.route_regime (
    regime_id     bigserial     PRIMARY KEY,
    route_id      smallint      NOT NULL REFERENCES core.route(route_id),
    date_from     date          NOT NULL,
    date_to       date,                                  -- NULL = до сих пор
    -- Маска дней недели: применять только к выходным, только к будням и т.п.
    -- 7 бит, 1 = понедельник. NULL = все дни.
    dow_mask      smallint,
    kind          varchar(24)   NOT NULL CHECK (kind IN ('suspension','works','level_shift','data_gap')),
    factor        numeric(6,4),                          -- множитель к прогнозу
    exclude_from_training boolean NOT NULL DEFAULT false,
    note          text,
    source_url    text,
    created_at    timestamptz   NOT NULL DEFAULT now()
);

CREATE INDEX idx_regime_lookup ON core.route_regime (route_id, date_from, date_to);

-- ---------------------------------------------------------------------------
-- Корректирующие коэффициенты, применяемые на выдаче
-- ---------------------------------------------------------------------------

-- Хранятся ОТДЕЛЬНО от базового прогноза, поэтому меняются в UI с немедленным
-- пересчётом без обращения к модели (критерий 2в).
-- Разреженная таблица: NULL в поле scope означает «любое значение».
CREATE TABLE core.adjustment (
    adjustment_id bigserial    PRIMARY KEY,
    kind          varchar(24)  NOT NULL,   -- calendar | school | weather | event | traffic | manual
    route_id      smallint     REFERENCES core.route(route_id),
    adj_date      date,
    hour          smallint     CHECK (hour BETWEEN 0 AND 23),
    day_of_week   smallint     CHECK (day_of_week BETWEEN 1 AND 7),
    factor        numeric(6,4) NOT NULL DEFAULT 1.0,
    enabled       boolean      NOT NULL DEFAULT true,
    note          text
);

CREATE INDEX idx_adjustment_lookup ON core.adjustment (kind, route_id, adj_date);
