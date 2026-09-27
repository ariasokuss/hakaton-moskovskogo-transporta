package ru.mttech.tram.api;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.mttech.tram.ingest.ExternalDataService;

/**
 * Внешние источники (схема external): что загружено, откуда (ссылка), когда и с каким статусом,
 * плюс контекст конкретных суток. Источник без ссылки жюри не засчитывает — ссылка в каждой записи.
 */
@RestController
@RequestMapping("/api/external")
public class ExternalController {

    private final ExternalDataService external;
    private final ru.mttech.tram.features.RouteStreets routeStreets;

    public ExternalController(ExternalDataService external, ru.mttech.tram.features.RouteStreets routeStreets) {
        this.external = external;
        this.routeStreets = routeStreets;
    }

    @GetMapping
    public Mono<Map<String, Object>> external(@RequestParam(required = false) LocalDate date) {
        // Последний балл пробок читается из БД — уводим блокирующее чтение с event loop.
        return Mono.fromCallable(() -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("sources", external.get().sources());
            out.put("trafficLatest", external.latestTraffic());
            if (date != null) out.put("context", external.context(date));
            return out;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** Внешние факторы за период (месяц, год): сводка календаря, погоды, событий и пробок. */
    @GetMapping("/period")
    public Mono<Map<String, Object>> period(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        if (to.isBefore(from) || to.toEpochDay() - from.toEpochDay() > 400)
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "Неверный период",
                    "Период задаётся датами from ≤ to и не длиннее 400 дней");
        return Mono.just(external.periodContext(from, to));
    }

    /**
     * Пробки за сутки: баллы ЦОДД по часам с адаптированным текстом постов (прогноз на вечер, скорость, места
     * затруднений) и измеренная связь балла с посадками. Балл — контекст для диспетчера, в прогноз модели не входит.
     */
    @GetMapping("/traffic")
    public Mono<Map<String, Object>> traffic(@RequestParam LocalDate date) {
        return Mono.fromCallable(() -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("date", date);
            // Пробки по маршрутам: места затруднений из постов, названные по улицам вдоль трасс (OSM)
            List<Map<String, Object>> posts = external.trafficDay(date);
            Map<Integer, java.util.Set<String>> byRoute = new java.util.TreeMap<>();
            for (Map<String, Object> p : posts) {
                Map<Integer, List<String>> routes = new java.util.TreeMap<>();
                @SuppressWarnings("unchecked") List<String> spots = (List<String>) p.get("spots");
                for (String spot : spots) {
                    routeStreets.match(spot).forEach((r, st) -> {
                        routes.computeIfAbsent(r, k -> new java.util.ArrayList<>()).add(spot);
                        if ("fact".equals(p.get("kind"))) byRoute.computeIfAbsent(r, k -> new java.util.TreeSet<>()).add(spot);
                    });
                }
                p.put("routes", routes);
            }
            out.put("posts", posts);
            out.put("byRoute", byRoute);
            Map<String, Object> latest = external.latestTraffic();
            if (latest != null) out.put("latestOnline", latest);
            // Замер: посадки к обычному уровню в часы с баллом, мар–окт 2025 (docs/traffic-operational-test.md)
            out.put("effect", Map.of(
                    "freeRoadsVsUsual", -0.029, "jamsVsUsual", 0.015,
                    "jamsVsFreeRatio", 1.045, "jamsVsFreeCi95", List.of(1.003, 1.093),
                    "forecastGainPp", -0.01, "method", "docs/traffic-operational-test.md",
                    // tools/route_traffic_match.py: посадки маршрута в час с затруднением на его трассе к остальным маршрутам
                    "routeSpotVsOthers", -0.017, "routeSpotHours", 75));
            return out;
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
