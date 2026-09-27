-- Полный текст поста ЦОДД к баллу пробок: диспетчер открывает его в окне «Пробки» (прогноз на вечер, средняя скорость,
-- места затруднений с объездами). Строки codd-telegram удаляются — при старте они загружаются заново вместе с текстом.
ALTER TABLE external.traffic_score ADD COLUMN post_id bigint, ADD COLUMN post_text text;
DELETE FROM external.traffic_score WHERE source = 'codd-telegram';
