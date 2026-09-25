package ru.mttech.tram.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.zip.GZIPOutputStream;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestHeader;
import tools.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import ru.mttech.tram.features.GeometryService;
import ru.mttech.tram.forecast.ForecastQueryService;
import ru.mttech.tram.forecast.ForecastQueryService.Attention;
import ru.mttech.tram.forecast.ForecastQueryService.Granularity;
import ru.mttech.tram.forecast.ForecastQueryService.RouteSeries;
import ru.mttech.tram.forecast.ForecastQueryService.Scenario;
import ru.mttech.tram.forecast.ForecastRunService;
import ru.mttech.tram.forecast.Model.Route;

/**
 * REST API прогноза. Все фильтры — query-параметры, поэтому любое состояние
 * экрана выражается ссылкой. Коэффициенты сценария (k*) по умолчанию равны 1.
 */
@RestController
@RequestMapping("/api")
public class ForecastController {

    private final ForecastQueryService query;
    private final ForecastRunService runs;
    private final ObjectMapper json;
    private final GeometryService geometry;

    /**
     * Готовые байты главного экрана: [0] — JSON, [1] — он же в gzip.
     * Ответ неизменен, пока не сменился снимок, поэтому ни сериализация,
     * ни сжатие не повторяются на каждый запрос.
     */
    private final Map<Object, byte[][]> dashboardBytes = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Object, byte[][]> e) {
                    return size() > 2000;
                }
            });

    public ForecastController(ForecastQueryService query, ForecastRunService runs, ObjectMapper json,
                              GeometryService geometry) {
        this.geometry = geometry;
        this.query = query;
        this.runs = runs;
        this.json = json;
    }

    @GetMapping("/meta")
    public Map<String, Object> meta() {
        return query.meta();
    }

    /** Справочник представления: цвет и подпись маршрута едины во всех представлениях. */
    @GetMapping("/routes")
    public List<Route> routes() {
        return query.routes();
    }

    /** Трассы и остановки всех маршрутов одним GeoJSON — карта рисуется целиком за один запрос. */
    @GetMapping("/geometry")
    public Map<String, Object> geometry() {
        return geometry.geoJson();
    }

    /** Главный экран диспетчера одним запросом. */
    @GetMapping("/dashboard")
    public ResponseEntity<byte[]> dashboard(@RequestParam LocalDate date,
                                         @RequestParam(defaultValue = "10") double threshold,
                                         @RequestParam(defaultValue = "1") double kWeather,
                                         @RequestParam(defaultValue = "1") double kEvent,
                                         @RequestParam(defaultValue = "1") double kSeason,
                                         @RequestParam(defaultValue = "1") double kTraffic,
                                         @RequestParam(defaultValue = "1") double kManual,
                                         @RequestHeader(value = HttpHeaders.ACCEPT_ENCODING, required = false) String enc) {
        Scenario sc = new Scenario(kWeather, kEvent, kSeason, kTraffic, kManual);
        Map<String, Object> body = query.dashboard(date, sc, threshold);   // сам мемоизирован
        byte[][] b = dashboardBytes.computeIfAbsent(new IdentityKey(body), k -> encode(body));
        boolean gzip = enc != null && enc.contains("gzip");
        ResponseEntity.BodyBuilder r = ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.VARY, HttpHeaders.ACCEPT_ENCODING);
        if (gzip) r.header(HttpHeaders.CONTENT_ENCODING, "gzip");
        return r.body(gzip ? b[1] : b[0]);
    }

    private byte[][] encode(Object body) {
        try {
            byte[] raw = json.writeValueAsBytes(body);
            ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length / 4);
            try (GZIPOutputStream gz = new GZIPOutputStream(out)) { gz.write(raw); }
            return new byte[][]{raw, out.toByteArray()};
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Ключ по идентичности объекта: мемоизированный ответ — один и тот же экземпляр. */
    private record IdentityKey(Object ref) {
        @Override public boolean equals(Object o) { return o instanceof IdentityKey k && k.ref == ref; }
        @Override public int hashCode() { return System.identityHashCode(ref); }
    }

    /**
     * Прогноз по параметрам ТЗ: маршрут, интервал, горизонт.
     * horizon=day — почасово за дату; month — по дням за месяц; year — по месяцам за год.
     * Явный интервал from/to и granularity перекрывают горизонт.
     */
    @GetMapping("/forecast")
    public List<RouteSeries> forecast(@RequestParam(required = false) Integer route,
                                      @RequestParam(defaultValue = "day") String horizon,
                                      @RequestParam(required = false) LocalDate date,
                                      @RequestParam(required = false) LocalDate from,
                                      @RequestParam(required = false) LocalDate to,
                                      @RequestParam(required = false) Granularity granularity,
                                      @RequestParam(defaultValue = "1") double kWeather,
                                      @RequestParam(defaultValue = "1") double kEvent,
                                      @RequestParam(defaultValue = "1") double kSeason,
                                      @RequestParam(defaultValue = "1") double kTraffic,
                                      @RequestParam(defaultValue = "1") double kManual) {
        Scenario sc = new Scenario(kWeather, kEvent, kSeason, kTraffic, kManual);
        Window w = Window.resolve(horizon, date, from, to, granularity, query);
        return query.series(route, w.from, w.to, w.granularity, sc);
    }

    @GetMapping("/attention")
    public List<Attention> attention(@RequestParam LocalDate date,
                                     @RequestParam(defaultValue = "10") double threshold,
                                     @RequestParam(defaultValue = "1") double kManual) {
        return query.attention(date, new Scenario(1, 1, 1, 1, kManual), threshold);
    }

    /** Асинхронный запуск годового прогона. Возвращает id сразу, расчёт идёт в фоне. */
    @PostMapping("/forecast/runs")
    public Map<String, Object> startRun(@RequestParam(defaultValue = "year") String horizon) {
        if (!"year".equals(horizon)) throw new ApiException(HttpStatus.BAD_REQUEST, "Неподдерживаемый горизонт",
                "Фоновый пересчёт доступен для горизонта year. Краткосрочный прогноз загружается из артефакта ML-модели.");
        long id = runs.startYearRun();
        return Map.of("runId", id, "status", "pending", "statusUrl", "/api/forecast/runs/" + id);
    }

    @GetMapping("/forecast/runs/{id}")
    public Mono<Map<String, Object>> runStatus(@PathVariable long id) {
        return runs.status(id).switchIfEmpty(Mono.error(new ApiException(HttpStatus.NOT_FOUND,
                "Прогон не найден", "Прогона с id " + id + " не существует")));
    }

    /** Разрешение горизонта в конкретный интервал и детализацию. */
    record Window(LocalDate from, LocalDate to, Granularity granularity) {
        static Window resolve(String horizon, LocalDate date, LocalDate from, LocalDate to,
                              Granularity g, ForecastQueryService q) {
            if (from != null && to != null) return new Window(from, to, g == null ? Granularity.day : g);
            LocalDate d = date != null ? date : (LocalDate) q.meta().get("defaultDate");
            if (d == null) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Прогноз не загружен",
                    "Прогноз ещё не загружен — повторите запрос через минуту.");
            return switch (horizon) {
                case "day" -> new Window(d, d, g == null ? Granularity.hour : g);
                case "month" -> new Window(d.withDayOfMonth(1), d.withDayOfMonth(d.lengthOfMonth()),
                        g == null ? Granularity.day : g);
                case "year" -> new Window(d, d.plusDays(364), g == null ? Granularity.month : g);
                default -> throw new ApiException(HttpStatus.BAD_REQUEST, "Неизвестный горизонт",
                        "horizon должен быть одним из: day, month, year. Получено: " + horizon);
            };
        }
    }
}
