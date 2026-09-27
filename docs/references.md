# Референсы — похожие решения

Собрано 25.09.2026. Смотрим на архитектуру и состав функционала, **код не копируем** —
часть репозиториев принадлежит конкурентам по этому же хакатону.

---

## Прямые конкуренты — та же задача, тот же хакатон

### Manticore-MT / tram-forecast
https://github.com/Manticore-MT/tram-forecast

**Буквально наша задача**: ИИ-прогноз загрузки трамвайных маршрутов Москвы, три горизонта,
отклонение от обычного уровня и рекомендации диспетчеру.

Что у них (по описанию репозитория):
- Spring Boot, **гексагональная архитектура**: domain (без зависимостей от фреймворка) →
  application (use-cases без Spring) → infrastructure → web;
- **PostgreSQL** + Flyway, но **JDBC и Spring MVC** — то есть блокирующий стек, не WebFlux;
- ML вынесен в **отдельный HTTP-сервис со stub-режимом** для разработки;
- чтение из сохранённых агрегатов, поход в ML только при промахе кэша;
- прогнозы — **иммутабельные записи**, что позволяет потом считать фактическую точность;
- ошибки API в формате **RFC 9457** (Problem Details);
- эндпоинты: `/api/meta`, `/api/routes`, `/api/routes/{id}/forecast`, `/api/attention`,
  `/api/export`, `/api/model/stats` (WAPE);
- статус: бэк на синтетике, реальный датасет / ML / фронт — в работе. Замеров производительности нет.

**Что отсюда берём:**
1. Мы **независимо пришли к той же ключевой идее** — читать из предрассчитанных агрегатов,
   а не звать модель в горячем пути. Это подтверждает, что решение верное.
2. Идея stub-режима ML — ровно то, что у нас уже записано в TECHNICAL.md.
3. **RFC 9457 (Problem Details)** — готовый стандарт для «понятных сообщений об ошибках»
   из ТЗ. Берём, Spring его поддерживает из коробки. Это дешёвый способ закрыть требование.
4. **Иммутабельные прогнозы + версия модели** — совпало с нашим решением, усиливаемся.
5. `/api/model/stats` с WAPE — хорошая идея показать метрику прямо в сервисе.
6. `/api/attention` (зоны внимания, ранжированные по отклонению) — **сильный ход для
   бизнес-ценности** (критерий 5). Диспетчеру нужен не график, а список «где сейчас плохо».

**Где мы можем их обойти:**
- у них **JDBC + Spring MVC**, у нас **WebFlux + R2DBC** — по критерию 3 это прямое
  попадание в рекомендованный ТЗ стек, у них — отступление;
- у них нет замеров производительности, а это явное требование README;
- фронт у них «в работе» — наша сильная зона.

### teamv39 / mt-hack_predictor
https://github.com/teamv39/mt-hack_predictor

Соседний трек (предиктор изменений в графике движения). Go backend + **FastAPI ML-сервис**,
ситуационный BI-дашборд диспетчера. Полезен как референс по разделению «бэк ↔ ML по HTTP»
и по подаче дашборда.

### Прочее с хакатона
- https://github.com/rapogahu/MT-Hackathon-2026
- https://github.com/nezqt3/transit-schedule-predictor
- Сайт хакатона: https://mt-hackathon.ru/

---

## Прогнозирование пассажиропотока — предметная область

- **FlowCast** — https://github.com/DiarCode/flowcast — прогноз пассажиропотока и спроса
  на общественный транспорт по историческим и реальным данным, с фронтом для визуализации.
  Ближайший по смыслу не-хакатонный аналог.
- **SmartTransit-AI** — https://github.com/jerlinroshna/SmartTransit-AI — сквозной пайплайн:
  обработка сырых данных → аналитика → ML-прогноз → оптимизация частоты → визуализация →
  рекомендации. Полезен структурой пайплайна.
- **passenger-flow-forecasting** — https://github.com/jeffreybakker/passenger-flow-forecasting —
  магистерская работа (Twente) по краткосрочному прогнозу пассажиропотока **при событиях**.
  Прямо релевантно нашему «учёту внешних факторов».
- **Transit_Demand_Prediction** — https://github.com/YomnaAlaaDarwish/Transit_Demand_Prediction —
  статическое обучение + онлайн-дообучение по мере поступления данных.
- **transit-demand-forecasting** — https://github.com/Dhruv-cs50/transit-demand-forecasting
- **Traffic-Prediction-Open-Code-Summary** — https://github.com/aptx1231/Traffic-Prediction-Open-Code-Summary —
  сводка открытого кода DL-моделей прогнозирования трафика. Для ML-команды.
- **GNN4Traffic** — https://github.com/jwwthu/GNN4Traffic — графовые нейросети для
  прогнозирования трафика. Для ML-команды, если дойдут руки до графа маршрутной сети.
- **gtfs-measures** — https://github.com/VolpeUSDOT/gtfs-measures — оценка пассажиропотока
  на уровне сегментов маршрута из GTFS. Релевантно нашей агрегации «по участку».
- Тема на GitHub: https://github.com/topics/ridership

---

## Технические референсы — WebFlux + R2DBC

Точного примера «WebFlux + R2DBC + ONNX Runtime» в открытом доступе нет —
придётся собирать из двух половин.

- https://github.com/bezkoder/spring-boot-webflux-example — базовый CRUD на WebFlux + R2DBC
- https://github.com/ChristopheMaldivi/spring-webflux-r2dbc — R2DBC + WebClient + Mono/Flux
- https://github.com/ianic1999/spring-webflux-r2dbc — пошаговый разбор
- https://github.com/canyaman/spring-boot-r2dbc-sample
- https://github.com/arafkarsh/ms-springboot-310-webflux-r2dbc — ближе всего к «боевому»:
  Java 17 + WebFlux + R2DBC + **Redis как распределённый кэш** + Kafka + слой обработки
  исключений. Полезен именно кэшем и обработкой ошибок.
- Тема на GitHub: https://github.com/topics/r2dbc-postgresql
