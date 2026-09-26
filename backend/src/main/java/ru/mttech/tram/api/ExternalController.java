package ru.mttech.tram.api;

import java.time.LocalDate;
import java.util.LinkedHashMap;
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

    public ExternalController(ExternalDataService external) {
        this.external = external;
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
}
