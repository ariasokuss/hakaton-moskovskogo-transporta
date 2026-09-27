package ru.mttech.tram.export;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.mttech.tram.api.ForecastController.Window;
import ru.mttech.tram.forecast.ForecastQueryService;
import ru.mttech.tram.forecast.ForecastQueryService.RouteSeries;
import ru.mttech.tram.forecast.ForecastQueryService.Scenario;
import ru.mttech.tram.forecast.Model.Route;
import ru.mttech.tram.ingest.ExternalDataService;

/**
 * PDF-отчёт диспетчера: GET /api/report?horizon=day|month|year&date=&route=&stop=&k* — те же параметры, что у экрана.
 * Данные — из тех же сервисов, что главный экран и прогноз, поэтому цифры отчёта совпадают с экраном.
 */
@RestController
@RequestMapping("/api/report")
public class ReportController {

    private final ForecastQueryService query;
    private final ExternalDataService external;
    private final ru.mttech.tram.features.GeometryService geometry;

    public ReportController(ForecastQueryService query, ExternalDataService external, ru.mttech.tram.features.GeometryService geometry) {
        this.query = query;
        this.external = external;
        this.geometry = geometry;
    }

    @GetMapping
    @SuppressWarnings("unchecked")
    public Mono<ResponseEntity<byte[]>> report(@RequestParam(defaultValue = "day") String horizon,
                                               @RequestParam(required = false) LocalDate date,
                                               @RequestParam(required = false) Integer route,
                                               @RequestParam(required = false) String stop,
                                               @RequestParam(defaultValue = "1") double kWeather,
                                               @RequestParam(defaultValue = "1") double kEvent,
                                               @RequestParam(defaultValue = "1") double kSeason,
                                               @RequestParam(defaultValue = "1") double kTraffic,
                                               @RequestParam(defaultValue = "1") double kManual) {
        Scenario sc = new Scenario(kWeather, kEvent, kSeason, kTraffic, kManual);
        return Mono.fromCallable(() -> {
            Window w = Window.resolve(horizon, date, null, null, null, query);
            LocalDate dashDate = "day".equals(horizon) ? w.from() : (date != null && !date.isBefore(w.from()) && !date.isAfter(w.to()) ? date : w.from());
            List<RouteSeries> series = query.series(route, stop, w.from(), w.to(), w.granularity(), sc, false);
            Map<String, Object> dash = query.dashboard(dashDate, sc, 10);
            Map<String, Object> ctx = external.context(dashDate);

            Map<Integer, String> names = new TreeMap<>();
            String color = "#2f6fdb";
            for (Route r : query.routes()) {
                String term = geometry.terminals(r.id());
                names.put(r.id(), r.longName() != null ? r.longName() : term != null ? term : "Маршрут " + r.shortName());
                if (route != null && r.id() == route) color = r.color();
            }
            String scope = route == null ? (stop == null ? "вся сеть, 9 маршрутов" : "остановка «" + stop + "»")
                    : "маршрут " + route + (stop == null ? "" : ", остановка «" + stop + "»");
            boolean isDay = "day".equals(horizon);
            // Месяц и год: итоги по всем маршрутам и внешние факторы — за весь период, а не за его первые сутки.
            List<RouteSeries> periodRoutes = isDay ? List.of() : query.series(null, null, w.from(), w.to(), w.granularity(), sc, false);
            List<RouteSeries> routeDay = route != null || stop != null ? List.of() : isDay ? (List<RouteSeries>) dash.get("series") : periodRoutes;
            Map<String, Object> model = (Map<String, Object>) dash.get("model");

            PdfReport.Input in = new PdfReport.Input(horizon, w.from(), w.to(), dashDate, scope, color,
                    PdfReport.sumSeries(series), routeDay,
                    (List<ForecastQueryService.Attention>) dash.get("attention"), (List<ru.mttech.tram.forecast.Model.Regime>) dash.get("regimes"),
                    (ExternalDataService.Day) ctx.get("day"), (List<ExternalDataService.Event>) ctx.get("events"),
                    (List<ExternalDataService.TrafficHour>) ctx.get("traffic"), sc,
                    model == null ? "—" : String.valueOf(model.get("modelVersion")), names,
                    periodRoutes, isDay ? null : external.periodContext(w.from(), w.to()));
            byte[] pdf = PdfReport.render(in);
            String name = "pantograf_" + horizon + "_" + w.from() + (route != null ? "_r" + route : "") + ".pdf";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(pdf);
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
