-- Схема EXTERNAL: всё, что подтянуто снаружи.
--
-- Отделено от core намеренно (см. CLAUDE.md):
--   * воспроизводимость — прогон на данных организаторов повторим без сети;
--   * честность — видно, что базовый прогноз построен на основном датасете,
--     а внешние данные являются только поправками;
--   * отказоустойчивость — источник недоступен, коэффициент = 1, сервис работает;
--   * критерий 2 — на каждый источник нужна ссылка, её проще предъявить,
--     когда источник помечен в самой строке.
--
-- У интернета при проверке жюри доступ есть, но сеть НЕ в критическом пути:
-- сервис всегда поднимается на снимке, живое обновление идёт фоном.

CREATE SCHEMA IF NOT EXISTS external;

-- Обязательные поля происхождения для каждой внешней таблицы.
-- source      — машинное имя источника (open-meteo, xmlcalendar, dt-road, yandex-traffic)
-- source_url  — рабочая ссылка, без неё источник не засчитывается жюри
-- fetched_at  — когда снято; отличает снимок от живого обновления

CREATE TABLE external.calendar_day (
    cal_date         date         PRIMARY KEY,
    day_type         varchar(24)  NOT NULL,   -- workday | weekend | holiday | short_workday | working_weekend
    is_day_off       boolean      NOT NULL,
    is_holiday       boolean      NOT NULL DEFAULT false,
    is_short_workday boolean      NOT NULL DEFAULT false,
    holiday_name     varchar(255),
    school_holiday   boolean      NOT NULL DEFAULT false,
    days_to_new_year smallint,
    source           varchar(64)  NOT NULL,
    source_url       text         NOT NULL,
    fetched_at       timestamptz  NOT NULL DEFAULT now()
);

-- kind: fact     — реанализ ERA5, для обучения коэффициента;
--       forecast — архив прогнозов, то, что было известно ЗАРАНЕЕ.
-- Для сабмита используется forecast: прогнозный период должен строиться
-- на том, что было доступно до его начала.
CREATE TABLE external.weather_hourly (
    obs_date            date          NOT NULL,
    hour                smallint      NOT NULL CHECK (hour BETWEEN 0 AND 23),
    kind                varchar(8)    NOT NULL CHECK (kind IN ('fact','forecast')),
    temperature_2m      numeric(5,2),
    apparent_temperature numeric(5,2),
    precipitation       numeric(6,2),
    snowfall            numeric(6,2),
    snow_depth          numeric(6,2),
    wind_speed_10m      numeric(5,2),
    weather_code        smallint,
    source              varchar(64)   NOT NULL,
    source_url          text          NOT NULL,
    fetched_at          timestamptz   NOT NULL DEFAULT now(),
    PRIMARY KEY (obs_date, hour, kind)
);

-- События: перекрытия, работы на путях, изменения маршрутов, массовые мероприятия.
-- target_routes — маршруты, упомянутые в сообщении (может быть пусто).
-- published_at важен отдельно от дат события: для прогноза на закрытый период
-- берутся только сообщения, опубликованные ДО его начала.
CREATE TABLE external.event (
    event_id      bigserial    PRIMARY KEY,
    published_at  timestamptz  NOT NULL,
    date_from     date,
    date_to       date,
    category      varchar(32)  NOT NULL,  -- closure | tram_works | tram_route_change | mass_event | new_line
    target_routes smallint[],
    title         text,
    source        varchar(64)  NOT NULL,
    source_url    text         NOT NULL,
    fetched_at    timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX idx_event_dates ON external.event (date_from, date_to);

-- Балл пробок 0-10. История собрана из публикаций ЦОДД, онлайн — из публичного
-- XML Яндекса. Покрытие истории неполное (около 40% дней) и по городу целиком,
-- а не по участкам маршрутов — это ограничение указано в README.
CREATE TABLE external.traffic_score (
    measured_at  timestamptz  NOT NULL,
    score        smallint     NOT NULL CHECK (score BETWEEN 0 AND 10),
    kind         varchar(8)   NOT NULL CHECK (kind IN ('fact','forecast')),
    source       varchar(64)  NOT NULL,
    source_url   text         NOT NULL,
    fetched_at   timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (measured_at, kind, source)
);

-- Журнал обновлений внешних источников: что, когда, успешно ли.
-- Нужен, чтобы в UI честно показывать «данные о погоде от такого-то времени»
-- и чтобы падение источника было видно, а не молча подменялось единицей.
CREATE TABLE external.fetch_log (
    fetch_id      bigserial    PRIMARY KEY,
    source        varchar(64)  NOT NULL,
    started_at    timestamptz  NOT NULL DEFAULT now(),
    finished_at   timestamptz,
    status        varchar(12)  NOT NULL CHECK (status IN ('running','succeeded','failed')),
    rows_written  integer,
    error_message text
);

CREATE INDEX idx_fetch_log_source ON external.fetch_log (source, started_at DESC);
