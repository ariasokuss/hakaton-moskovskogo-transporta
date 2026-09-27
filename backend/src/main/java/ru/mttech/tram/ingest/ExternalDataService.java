package ru.mttech.tram.ingest;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import ru.mttech.tram.config.AppProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Приём внешних источников в отдельную схему {@code external.*}.
 *
 * Граница данных (CLAUDE.md): основная БД — только данные организаторов, всё внешнее
 * лежит отдельно и в каждой строке несёт {@code source}, {@code source_url}, {@code fetched_at}.
 * Соединение с прогнозом — только на выдаче. Снимок источников ({@code external_data/} ML-команды)
 * грузится при старте без сети; онлайн-обновление (балл пробок Яндекса) идёт фоном с жёстким
 * таймаутом и в критический путь не входит. Каждая загрузка пишется в {@code external.fetch_log}.
 */
@Service
public class ExternalDataService {

    private static final Logger log = LoggerFactory.getLogger(ExternalDataService.class);
    private static final ZoneId MSK = ZoneId.of("Europe/Moscow");
    private static final Pattern YANDEX_LEVEL = Pattern.compile("<level>(\\d+)</level>");

    static final String URL_CALENDAR = "https://github.com/xmlcalendar/data";
    static final String URL_WEATHER_FCST = "https://open-meteo.com/en/docs/historical-forecast-api";
    static final String URL_WEATHER_FACT = "https://open-meteo.com/en/docs/historical-weather-api";

    /** Сутки по внешним источникам — то, что показывается диспетчеру рядом с прогнозом. */
    public record Day(String dayType, boolean dayOff, String holidayName, boolean schoolHoliday,
                      Double tempMin, Double tempMax, Double precipitationMm, Double snowfallCm, String weatherKind) {}

    public record Event(LocalDate from, LocalDate to, String category, List<Integer> routes, String title, String url) {}

    /** Неизменяемый снимок для выдачи: запросы к API в БД не ходят. */
    /**
     * Балл пробок ЦОДД за час (0–10), фактический. Для диспетчера — контекст суток, в прогноз не входит:
     * эффект не подтверждён ни в ML-проверке, ни как оперативная поправка (docs/traffic-operational-test.md).
     */
    public record TrafficHour(int hour, int score, String url) {}

    public record Snapshot(Map<LocalDate, Day> days, List<Event> events, List<Map<String, Object>> sources,
                           Map<LocalDate, List<TrafficHour>> traffic) {
        static Snapshot empty() { return new Snapshot(Map.of(), List.of(), List.of(), Map.of()); }
    }

    private final DatabaseClient db;
    private final AppProperties props;
    private final HttpClient http;
    private final ObjectMapper json;
    private volatile Snapshot snapshot = Snapshot.empty();

    public ExternalDataService(DatabaseClient db, AppProperties props, ObjectMapper json) {
        this.json = json;
        this.db = db;
        this.props = props;
        this.http = HttpClient.newBuilder().connectTimeout(props.external().timeout()).build();
    }

    public Snapshot get() {
        return snapshot;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            loadSnapshotFiles();
            reload();
        } catch (RuntimeException e) {
            // Внешние данные — только поправки: их сбой не должен мешать выдаче прогноза.
            log.error("Внешние источники не загружены, прогноз выдаётся без них", e);
        }
    }

    // -------------------------------------------------------------------------
    // Снимок из файлов (воспроизводимо, без сети)
    // -------------------------------------------------------------------------

    void loadSnapshotFiles() {
        String dirName = props.external().dir();
        if (dirName == null || !Files.isDirectory(Path.of(dirName))) {
            log.warn("Нет каталога внешних источников {}: схема external пуста, коэффициенты по умолчанию = 1", dirName);
            return;
        }
        Path dir = Path.of(dirName);
        loadOnce("xmlcalendar", "external.calendar_day", "source = 'xmlcalendar'", () -> loadCalendar(dir.resolve("calendar_2025.csv")));
        loadOnce("open-meteo-forecast", "external.weather_hourly", "kind = 'forecast'",
                () -> loadWeather(dir.resolve("weather_fcst_hourly_2025.csv"), "forecast", "open-meteo-forecast", URL_WEATHER_FCST));
        loadOnce("open-meteo-fact", "external.weather_hourly", "kind = 'fact'",
                () -> loadWeather(dir.resolve("weather_fact_hourly_2025.csv"), "fact", "open-meteo-fact", URL_WEATHER_FACT));
        loadOnce("telegram-dtroad", "external.event", "source = 'telegram-dtroad'",
                () -> loadEvents(dir.resolve("events_2025.csv"), "telegram-dtroad"));
        loadOnce("telegram-dtoperativno", "external.event", "source = 'telegram-dtoperativno'",
                () -> loadEvents(dir.resolve("DtOperativno_events_2025.csv"), "telegram-dtoperativno"));
        loadOnce("telegram-incidents", "external.event", "source = 'telegram-incidents'",
                () -> loadIncidents(dir.resolve("tram_incidents_2025.csv")));
        loadOnce("codd-telegram", "external.traffic_score", "source = 'codd-telegram'",
                () -> {
                    Map<Long, String> texts = new HashMap<>();
                    readPostTexts(dir.resolve("DtOperativno_posts_raw.jsonl"), texts);
                    readPostTexts(dir.resolve("deptrans_posts_raw.jsonl"), texts);
                    return loadTrafficScores(dir.resolve("DtOperativno_traffic_scores_2025.csv"), texts)
                            + loadTrafficScores(dir.resolve("traffic_scores_2025.csv"), texts);
                });
    }

    /** Идемпотентно: источник грузится, только если его строк в таблице ещё нет. */
    private void loadOnce(String source, String table, String where, java.util.function.IntSupplier load) {
        Long n = db.sql("SELECT count(*) AS c FROM " + table + " WHERE " + where)
                .map((r, m) -> ((Number) r.get("c")).longValue()).one().block();
        if (n != null && n > 0) return;
        long fetchId = startFetch(source);
        try {
            int rows = load.getAsInt();
            finishFetch(fetchId, rows, null);
            log.info("Внешний источник {}: загружено {} строк", source, rows);
        } catch (RuntimeException e) {
            finishFetch(fetchId, 0, e.getMessage());
            log.warn("Внешний источник {} не загружен: {}", source, e.getMessage());
        }
    }

    int loadCalendar(Path p) {
        List<Map<String, String>> rows = readCsv(p);
        Flux.fromIterable(rows).concatMap(r -> {
            var spec = db.sql("""
                    INSERT INTO external.calendar_day (cal_date, day_type, is_day_off, is_holiday, is_short_workday,
                        holiday_name, school_holiday, days_to_new_year, source, source_url)
                    VALUES (:d, :t, :off, :hol, :short, :name, :school, :ny, 'xmlcalendar', :url)
                    ON CONFLICT (cal_date) DO NOTHING""")
                    .bind("d", LocalDate.parse(r.get("date")))
                    .bind("t", r.get("day_type"))
                    .bind("off", "1".equals(r.get("is_day_off")))
                    .bind("hol", "1".equals(r.get("is_holiday")))
                    .bind("short", "1".equals(r.get("is_short_workday")))
                    .bind("school", "1".equals(r.get("school_holiday")))
                    .bind("url", URL_CALENDAR);
            String name = r.get("holiday_name");
            spec = name == null || name.isBlank() ? spec.bindNull("name", String.class) : spec.bind("name", name);
            String ny = r.get("days_to_new_year");
            spec = ny == null || ny.isBlank() ? spec.bindNull("ny", Short.class) : spec.bind("ny", Short.parseShort(ny));
            return spec.then();
        }).blockLast();
        return rows.size();
    }

    int loadWeather(Path p, String kind, String source, String url) {
        List<Map<String, String>> rows = readCsv(p);
        List<String> values = new ArrayList<>(rows.size());
        for (Map<String, String> r : rows) {
            // Значения — распарсенные числа и даты, пользовательского ввода нет: конкатенация безопасна.
            values.add("('" + LocalDate.parse(r.get("date")) + "'," + Integer.parseInt(r.get("hour")) + ",'" + kind + "',"
                    + num(r.get("temperature_2m")) + "," + num(r.get("apparent_temperature")) + ","
                    + num(r.get("precipitation")) + "," + num(r.get("snowfall")) + "," + num(r.get("snow_depth")) + ","
                    + num(r.get("wind_speed_10m")) + "," + intOrNull(r.get("weather_code")) + ",'" + source + "','" + url + "')");
        }
        for (int i = 0; i < values.size(); i += 2000) {
            db.sql("INSERT INTO external.weather_hourly (obs_date, hour, kind, temperature_2m, apparent_temperature,"
                    + " precipitation, snowfall, snow_depth, wind_speed_10m, weather_code, source, source_url) VALUES "
                    + String.join(",", values.subList(i, Math.min(values.size(), i + 2000)))
                    + " ON CONFLICT DO NOTHING").then().block();
        }
        return values.size();
    }

    /** Посты Дептранса, относящиеся к трамваю: работы, перекрытия, изменения маршрутов, новые линии. */
    int loadEvents(Path p, String source) {
        List<Map<String, String>> rows = readCsv(p).stream().filter(r -> "1".equals(r.get("tram_related"))).toList();
        Flux.fromIterable(rows).concatMap(r -> {
            String[] dates = blank(r.get("event_dates")) ? new String[0] : r.get("event_dates").split("\\|");
            String category = r.get("categories").split("\\|")[0];
            return insertEvent(publishedAt(r.get("post_date"), r.get("post_hour")),
                    dates.length == 0 ? null : LocalDate.parse(dates[0]),
                    dates.length == 0 ? null : LocalDate.parse(dates[dates.length - 1]),
                    category, routes(r.get("target_routes")), abbreviate(r.get("text"), 600), source, r.get("url"));
        }).blockLast();
        return rows.size();
    }

    /** Оперативные сбои на трамвайных маршрутах (ДТП, контактная сеть): K_incident измерен ML-командой. */
    int loadIncidents(Path p) {
        List<Map<String, String>> rows = readCsv(p);
        Flux.fromIterable(rows).concatMap(r -> {
            LocalDate d = LocalDate.parse(r.get("date"));
            return insertEvent(publishedAt(r.get("date"), r.get("hour")), d, d, "tram_incident",
                    routes(r.get("route")), CAUSES.getOrDefault(r.get("cause"), "сбой движения"), "telegram-incidents", r.get("url"));
        }).blockLast();
        return rows.size();
    }

    /** Коды причин из парсера постов (parse_deptrans_tg.py incidents) — в текст для диспетчера. */
    static final Map<String, String> CAUSES = Map.of(
            "catenary", "неисправность контактной сети",
            "dtp", "ДТП",
            "foreign_vehicle", "автомобиль на путях",
            "other", "сбой движения");

    int loadTrafficScores(Path p, Map<Long, String> texts) {
        if (!Files.exists(p)) return 0;
        List<Map<String, String>> rows = readCsv(p);
        Flux.fromIterable(rows).concatMap(r -> {
            long postId = Long.parseLong(r.get("post_id"));
            String text = texts.get(postId);
            var spec = db.sql("""
                            INSERT INTO external.traffic_score (measured_at, score, kind, source, source_url, post_id, post_text)
                            VALUES (:t, :s, :k, 'codd-telegram', :u, :pid, :txt) ON CONFLICT DO NOTHING""")
                    .bind("t", publishedAt(r.get("date"), r.get("hour")))
                    .bind("s", Short.parseShort(r.get("score")))
                    .bind("k", "forecast".equals(r.get("kind")) ? "forecast" : "fact")
                    .bind("u", r.get("url")).bind("pid", postId);
            return (text == null ? spec.bindNull("txt", String.class) : spec.bind("txt", text)).then();
        }).blockLast();
        return rows.size();
    }

    /** Тексты постов Telegram из выгрузки парсера (jsonl: id, text, url) — для окна «Пробки». */
    private void readPostTexts(Path p, Map<Long, String> out) {
        if (!Files.exists(p)) return;
        try (BufferedReader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode n = json.readTree(line);
                if (n.hasNonNull("id") && n.hasNonNull("text")) out.put(n.get("id").asLong(), n.get("text").asString());
            }
        } catch (IOException e) {
            log.warn("Тексты постов {} не прочитаны: {}", p.getFileName(), e.getMessage());
        }
    }

    /** Баллы пробок за сутки с адаптированными текстами постов — окно «Пробки» на экране диспетчера. */
    public List<Map<String, Object>> trafficDay(LocalDate date) {
        OffsetDateTime from = date.atStartOfDay(MSK).toOffsetDateTime(), to = date.plusDays(1).atStartOfDay(MSK).toOffsetDateTime();
        return db.sql("""
                SELECT measured_at, score, kind, source, source_url, post_text FROM external.traffic_score
                WHERE measured_at >= :f AND measured_at < :t ORDER BY measured_at, kind""")
                .bind("f", from).bind("t", to)
                .map((r, m) -> {
                    Map<String, Object> o = new LinkedHashMap<>();
                    var t = r.get("measured_at", OffsetDateTime.class).atZoneSameInstant(MSK);
                    o.put("hour", t.getHour());
                    o.put("score", ((Number) r.get("score")).intValue());
                    o.put("kind", r.get("kind", String.class));
                    o.put("source", r.get("source", String.class));
                    o.put("url", r.get("source_url", String.class));
                    TrafficPosts.Parsed p = TrafficPosts.parse(r.get("post_text", String.class));
                    o.put("text", p.text());
                    o.put("speedKmh", p.speedKmh());
                    o.put("forecastScore", p.forecastScore());
                    o.put("spots", p.spots());
                    return o;
                }).all().collectList().block();
    }

    private reactor.core.publisher.Mono<Void> insertEvent(OffsetDateTime published, LocalDate from, LocalDate to, String category,
                                                          Short[] routes, String title, String source, String url) {
        var spec = db.sql("""
                INSERT INTO external.event (published_at, date_from, date_to, category, target_routes, title, source, source_url)
                VALUES (:p, :f, :t, :c, :r, :title, :s, :u)""")
                .bind("p", published).bind("c", category).bind("s", source).bind("u", url);
        spec = from == null ? spec.bindNull("f", LocalDate.class) : spec.bind("f", from);
        spec = to == null ? spec.bindNull("t", LocalDate.class) : spec.bind("t", to);
        spec = routes.length == 0 ? spec.bindNull("r", Short[].class) : spec.bind("r", routes);
        spec = blank(title) ? spec.bindNull("title", String.class) : spec.bind("title", title);
        return spec.then();
    }

    // -------------------------------------------------------------------------
    // Онлайн-обновление: балл пробок Яндекса. Фоном, с таймаутом, вне пути запроса.
    // -------------------------------------------------------------------------

    @Scheduled(initialDelay = 60_000, fixedDelay = 600_000)
    public void refreshTrafficOnline() {
        AppProperties.External ext = props.external();
        if (!ext.enabled() || blank(ext.yandexTrafficUrl())) return;
        long fetchId = startFetch("yandex-traffic");
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(ext.yandexTrafficUrl())).timeout(ext.timeout()).GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Matcher m = YANDEX_LEVEL.matcher(res.body());
            if (res.statusCode() != 200 || !m.find()) throw new IllegalStateException("ответ без балла, HTTP " + res.statusCode());
            short level = Short.parseShort(m.group(1));
            db.sql("""
                    INSERT INTO external.traffic_score (measured_at, score, kind, source, source_url)
                    VALUES (:t, :s, 'fact', 'yandex-traffic', :u) ON CONFLICT DO NOTHING""")
                    .bind("t", OffsetDateTime.now(MSK).truncatedTo(ChronoUnit.MINUTES))
                    .bind("s", level).bind("u", ext.yandexTrafficUrl()).then().block();
            finishFetch(fetchId, 1, null);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            finishFetch(fetchId, 0, e.getClass().getSimpleName() + ": " + e.getMessage());
            log.info("Онлайн-балл пробок недоступен ({}), работаем на снимке", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Снимок для выдачи
    // -------------------------------------------------------------------------

    public void reload() {
        Map<LocalDate, String[]> cal = new HashMap<>();
        db.sql("SELECT cal_date, day_type, is_day_off, holiday_name, school_holiday FROM external.calendar_day")
                .map((r, m) -> new Object[]{r.get("cal_date", LocalDate.class), r.get("day_type", String.class),
                        r.get("is_day_off", Boolean.class), r.get("holiday_name", String.class), r.get("school_holiday", Boolean.class)})
                .all().toIterable().forEach(o -> cal.put((LocalDate) o[0], new String[]{(String) o[1],
                        String.valueOf(o[2]), (String) o[3], String.valueOf(o[4])}));

        // Для прогноза — архив прогнозов погоды (то, что было известно заранее), а не факт.
        Map<LocalDate, Object[]> weather = new HashMap<>();
        db.sql("""
                SELECT obs_date, min(temperature_2m) AS tmin, max(temperature_2m) AS tmax,
                       sum(precipitation) AS prec, sum(snowfall) AS snow
                FROM external.weather_hourly WHERE kind = 'forecast' GROUP BY obs_date""")
                .map((r, m) -> new Object[]{r.get("obs_date", LocalDate.class), dbl(r.get("tmin")), dbl(r.get("tmax")),
                        dbl(r.get("prec")), dbl(r.get("snow"))})
                .all().toIterable().forEach(o -> weather.put((LocalDate) o[0], o));

        Map<LocalDate, Day> days = new HashMap<>();
        for (LocalDate d : union(cal.keySet(), weather.keySet())) {
            String[] c = cal.get(d);
            Object[] w = weather.get(d);
            Double prec = w == null ? null : (Double) w[3], snow = w == null ? null : (Double) w[4];
            days.put(d, new Day(c == null ? null : c[0], c != null && Boolean.parseBoolean(c[1]), c == null ? null : c[2],
                    c != null && Boolean.parseBoolean(c[3]),
                    w == null ? null : (Double) w[1], w == null ? null : (Double) w[2], round1(prec), round1(snow),
                    weatherKind(prec, snow)));
        }

        List<Event> events = db.sql("""
                SELECT date_from, date_to, category, target_routes, title, source_url FROM external.event
                WHERE date_from IS NOT NULL ORDER BY date_from""")
                .map((r, m) -> {
                    Object arr = r.get("target_routes");
                    List<Integer> rs = arr instanceof Object[] a
                            ? Arrays.stream(a).map(x -> ((Number) x).intValue()).toList() : List.of();
                    return new Event(r.get("date_from", LocalDate.class), r.get("date_to", LocalDate.class),
                            r.get("category", String.class), rs, r.get("title", String.class), r.get("source_url", String.class));
                }).all().collectList().block();

        List<Map<String, Object>> sources = db.sql("""
                SELECT DISTINCT ON (source) source, status, finished_at, rows_written, error_message
                FROM external.fetch_log ORDER BY source, started_at DESC""")
                .map((r, m) -> {
                    Map<String, Object> s = new LinkedHashMap<>();
                    s.put("source", r.get("source", String.class));
                    s.put("url", SOURCE_URLS.getOrDefault(r.get("source", String.class), ""));
                    s.put("status", r.get("status", String.class));
                    s.put("fetchedAt", r.get("finished_at", OffsetDateTime.class));
                    s.put("rows", r.get("rows_written", Integer.class));
                    s.put("error", r.get("error_message", String.class));
                    return s;
                }).all().collectList().block();

        // Баллы пробок по суткам (МСК): последний пост за час, часы по возрастанию.
        Map<LocalDate, java.util.TreeMap<Integer, TrafficHour>> byDay = new HashMap<>();
        db.sql("""
                SELECT measured_at, score, source_url FROM external.traffic_score
                WHERE kind = 'fact' AND source = 'codd-telegram' ORDER BY measured_at""")
                .map((r, m) -> {
                    var t = r.get("measured_at", OffsetDateTime.class).atZoneSameInstant(MSK);
                    return new Object[]{t.toLocalDate(), new TrafficHour(t.getHour(), ((Number) r.get("score")).intValue(),
                            r.get("source_url", String.class))};
                }).all().toIterable()
                .forEach(o -> byDay.computeIfAbsent((LocalDate) o[0], k -> new java.util.TreeMap<>())
                        .put(((TrafficHour) o[1]).hour(), (TrafficHour) o[1]));
        Map<LocalDate, List<TrafficHour>> traffic = new HashMap<>();
        byDay.forEach((d, m) -> traffic.put(d, List.copyOf(m.values())));

        snapshot = new Snapshot(Map.copyOf(days), List.copyOf(events), List.copyOf(sources), Map.copyOf(traffic));
        log.info("Внешние источники в памяти: {} дней календаря/погоды, {} событий с датами, {} источников",
                days.size(), events.size(), sources.size());
    }

    /** Внешний контекст даты для экрана диспетчера: календарь, погода, события и сбои по маршрутам. */
    public Map<String, Object> context(LocalDate date) {
        Snapshot s = snapshot;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("day", s.days().get(date));
        out.put("events", s.events().stream()
                .filter(e -> !date.isBefore(e.from()) && !date.isAfter(e.to() == null ? e.from() : e.to()))
                // Годовые «фоновые» посты (весь год) не информативны для конкретных суток.
                .filter(e -> e.to() == null || e.to().toEpochDay() - e.from().toEpochDay() < 60)
                .limit(20).toList());
        out.put("traffic", s.traffic().getOrDefault(date, List.of()));
        return out;
    }

    /**
     * Внешние факторы за период (месяц, год) — сводка вместо контекста одних суток: состав календаря
     * и праздники, погода по дням с прогнозом, события и режимы, пересекающие период, дни с пробками.
     * Покрытие каждого источника указывается явно: на будущие даты погоды и постов ещё нет.
     */
    public Map<String, Object> periodContext(LocalDate from, LocalDate to) {
        Snapshot s = snapshot;
        int total = (int) (to.toEpochDay() - from.toEpochDay() + 1);
        int calDays = 0, work = 0, off = 0, school = 0, weatherDays = 0, rainy = 0, heavy = 0, snowy = 0, frost = 0;
        double tmin = Double.POSITIVE_INFINITY, tmax = Double.NEGATIVE_INFINITY, prec = 0;
        List<Map<String, Object>> holidays = new ArrayList<>();
        List<LocalDate> workingWeekends = new ArrayList<>();
        int trafficDays = 0, jamDays = 0, maxScore = -1;
        LocalDate maxScoreDay = null;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            Day day = s.days().get(d);
            if (day != null && day.dayType() != null) {
                calDays++;
                if (day.dayOff()) off++; else work++;
                if (day.schoolHoliday()) school++;
                if (day.holidayName() != null) holidays.add(Map.of("date", d, "name", day.holidayName()));
                if ("working_weekend".equals(day.dayType())) workingWeekends.add(d);
            }
            if (day != null && day.tempMin() != null) {
                weatherDays++;
                tmin = Math.min(tmin, day.tempMin());
                tmax = Math.max(tmax, day.tempMax());
                double p = day.precipitationMm() == null ? 0 : day.precipitationMm();
                prec += p;
                if (p >= 1) rainy++;
                if (p >= 10) heavy++;
                if (day.snowfallCm() != null && day.snowfallCm() >= 1) snowy++;
                if (day.tempMin() <= -10) frost++;
            }
            List<TrafficHour> th = s.traffic().get(d);
            if (th != null && !th.isEmpty()) {
                trafficDays++;
                int m = th.stream().mapToInt(TrafficHour::score).max().orElse(0);
                if (m >= 7) jamDays++;
                if (m > maxScore) { maxScore = m; maxScoreDay = d; }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", from);
        out.put("to", to);
        out.put("days", total);
        Map<String, Object> cal = new LinkedHashMap<>();
        cal.put("coveredDays", calDays);
        cal.put("workdays", work);
        cal.put("daysOff", off);
        cal.put("schoolHolidayDays", school);
        cal.put("holidays", holidays);
        cal.put("workingWeekends", workingWeekends);
        out.put("calendar", cal);
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("coveredDays", weatherDays);
        if (weatherDays > 0) {
            w.put("tempMin", Math.round(tmin));
            w.put("tempMax", Math.round(tmax));
            w.put("precipitationMm", Math.round(prec));
            w.put("rainyDays", rainy);
            w.put("heavyDays", heavy);
            w.put("snowDays", snowy);
            w.put("frostDays", frost);
        }
        out.put("weather", w);
        Map<String, Object> tr = new LinkedHashMap<>();
        tr.put("coveredDays", trafficDays);
        tr.put("jamDays", jamDays);
        if (maxScoreDay != null) { tr.put("maxScore", maxScore); tr.put("maxScoreDay", maxScoreDay); }
        out.put("traffic", tr);
        List<Event> ev = s.events().stream()
                .filter(e -> !e.from().isAfter(to) && !(e.to() == null ? e.from() : e.to()).isBefore(from))
                .filter(e -> e.to() == null || e.to().toEpochDay() - e.from().toEpochDay() < 60)
                .toList();
        Map<String, Long> byCat = new java.util.TreeMap<>();
        ev.forEach(e -> byCat.merge(e.category(), 1L, Long::sum));
        out.put("eventCounts", byCat);
        // Режимы и работы на путях — самые важные для выпуска, показываем первыми; сбои — только счётчиком.
        out.put("events", ev.stream()
                .sorted(java.util.Comparator.comparing((Event e) -> "tram_incident".equals(e.category())).thenComparing(Event::from))
                .limit(12).toList());
        return out;
    }

    /** Онлайн-балл пробок (последний) — для /api/external, не мемоизируется. */
    public Map<String, Object> latestTraffic() {
        return db.sql("""
                SELECT measured_at, score, source, source_url FROM external.traffic_score
                WHERE kind = 'fact' ORDER BY measured_at DESC LIMIT 1""")
                .map((r, m) -> {
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("measuredAt", r.get("measured_at", OffsetDateTime.class));
                    t.put("score", ((Number) r.get("score")).intValue());
                    t.put("source", r.get("source", String.class));
                    t.put("url", r.get("source_url", String.class));
                    return t;
                }).one().block();
    }

    static final Map<String, String> SOURCE_URLS = Map.of(
            "xmlcalendar", URL_CALENDAR,
            "open-meteo-forecast", URL_WEATHER_FCST,
            "open-meteo-fact", URL_WEATHER_FACT,
            "telegram-dtroad", "https://t.me/s/DtRoad",
            "telegram-dtoperativno", "https://t.me/s/DtOperativno",
            "telegram-incidents", "https://t.me/s/DtOperativno",
            "codd-telegram", "https://t.me/s/DtOperativno",
            "yandex-traffic", "https://export.yandex.ru/bar/reginfo.xml?region=213");

    // -------------------------------------------------------------------------

    private long startFetch(String source) {
        return db.sql("INSERT INTO external.fetch_log (source, status) VALUES (:s, 'running') RETURNING fetch_id")
                .bind("s", source).map((r, m) -> ((Number) r.get("fetch_id")).longValue()).one().block();
    }

    private void finishFetch(long id, int rows, String error) {
        var spec = db.sql("""
                UPDATE external.fetch_log SET finished_at = now(), status = :st, rows_written = :n, error_message = :e
                WHERE fetch_id = :id""")
                .bind("st", error == null ? "succeeded" : "failed").bind("n", rows).bind("id", id);
        (error == null ? spec.bindNull("e", String.class) : spec.bind("e", abbreviate(error, 500))).then().block();
    }

    /** CSV с разделителем «;» и кавычками (тексты постов). Переносов строк внутри полей в файлах нет. */
    static List<Map<String, String>> readCsv(Path p) {
        List<Map<String, String>> out = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            String first = r.readLine();
            if (first == null) return out;
            List<String> head = split(first.replace("﻿", ""));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> v = split(line);
                Map<String, String> row = new HashMap<>();
                for (int i = 0; i < head.size(); i++) row.put(head.get(i).trim(), i < v.size() ? v.get(i) : "");
                out.add(row);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось прочитать " + p + ": " + e.getMessage(), e);
        }
        return out;
    }

    static List<String> split(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                else if (ch == '"') quoted = false;
                else cur.append(ch);
            } else if (ch == '"') quoted = true;
            else if (ch == ';') { out.add(cur.toString()); cur.setLength(0); }
            else cur.append(ch);
        }
        out.add(cur.toString());
        return out;
    }

    private static OffsetDateTime publishedAt(String date, String hour) {
        int h = blank(hour) ? 0 : Integer.parseInt(hour);
        return ZonedDateTime.of(LocalDate.parse(date).atTime(h, 0), MSK).toOffsetDateTime();
    }

    private static Short[] routes(String s) {
        if (blank(s)) return new Short[0];
        return Arrays.stream(s.split("\\|")).map(String::trim).filter(x -> !x.isEmpty()).map(Short::valueOf).toArray(Short[]::new);
    }

    private static String num(String s) {
        return blank(s) ? "NULL" : String.valueOf(Double.parseDouble(s));
    }

    private static String intOrNull(String s) {
        return blank(s) ? "NULL" : String.valueOf((int) Double.parseDouble(s));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String abbreviate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static Double dbl(Object o) {
        return o == null ? null : ((Number) o).doubleValue();
    }

    private static Double round1(Double v) {
        return v == null ? null : Math.round(v * 10) / 10.0;
    }

    private static String weatherKind(Double prec, Double snow) {
        if (prec == null) return null;
        if (snow != null && snow >= 1) return "снег";
        if (prec >= 5) return "сильные осадки";
        if (prec >= 0.5) return "осадки";
        return "без осадков";
    }

    private static <T> java.util.Set<T> union(java.util.Set<T> a, java.util.Set<T> b) {
        java.util.Set<T> s = new java.util.HashSet<>(a);
        s.addAll(b);
        return s;
    }
}
