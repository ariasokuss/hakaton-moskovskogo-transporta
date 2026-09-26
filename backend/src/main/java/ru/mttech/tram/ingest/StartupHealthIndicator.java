package ru.mttech.tram.ingest;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import ru.mttech.tram.forecast.GridStore;
import ru.mttech.tram.forecast.Model.Snapshot;

/**
 * Готовность к выдаче. Netty начинает слушать порт раньше, чем загружены факт
 * и прогноз, поэтому без этого индикатора health отвечал бы UP на пустом снимке
 * и фронтенд открывался бы с ошибкой «прогноз не загружен».
 */
@Component("forecastData")
public class StartupHealthIndicator implements HealthIndicator {

    private final DataIngestService ingest;
    private final GridStore grid;

    public StartupHealthIndicator(DataIngestService ingest, GridStore grid) {
        this.ingest = ingest;
        this.grid = grid;
    }

    @Override
    public Health health() {
        if (!ingest.started()) return Health.down().withDetail("status", "загрузка данных при старте").build();
        Snapshot s = grid.get();
        // После старта — UP даже без прогноза: сервис обязан подниматься и отвечать понятной ошибкой.
        Health.Builder b = Health.up();
        b.withDetail("routes", s.routes().size())
                .withDetail("actualDays", s.actuals().size())
                .withDetail("shortTermRun", s.shortTerm() == null ? "нет" : s.shortTerm().modelVersion())
                .withDetail("yearRun", s.year() == null ? "нет" : s.year().modelVersion());
        if (ingest.startupError() != null) b.withDetail("startupError", ingest.startupError());
        return b.build();
    }
}
