-- Прогноз хранится без округления (CLAUDE.md, «Округление прогноза»).
--
-- numeric(12,3) округлял значение модели до тысячных: 745.4997 превращалось в 745.500,
-- и на выгрузке half-up давал 746 вместо 745 (4 строки сабмита расходились с ML).
-- double precision хранит значение модели как есть; округление — только на выгрузке.
ALTER TABLE core.forecast_hourly ALTER COLUMN prediction TYPE double precision;

-- Уже импортированные прогоны ML усечены до тысячных. Они целиком восстанавливаются
-- из артефакта data/forecast/forecast_hourly.csv: при старте сервис импортирует его заново
-- (импорт идемпотентен по model_version). Годовой прогон сервис считает сам — его не трогаем.
DELETE FROM core.forecast_run WHERE horizon = 'day' AND model_version LIKE 'ml-%';
