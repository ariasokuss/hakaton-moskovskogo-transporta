package ru.mttech.tram.ingest;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import ru.mttech.tram.config.AppProperties;
import ru.mttech.tram.forecast.ForecastRunService;
import ru.mttech.tram.forecast.GridStore;

/**
 * Приём и нормализация данных при старте. Идемпотентно: повторный запуск
 * ничего не дублирует. Если датасета нет — сервис всё равно поднимается
 * (правило запускаемости), просто без исторического факта.
 */
@Service
public class DataIngestService {

    private static final Logger log = LoggerFactory.getLogger(DataIngestService.class);
    private static final int BATCH = 2000;

    private final DatabaseClient db;
    private final AppProperties props;
    private final GridStore grid;
    private final ForecastRunService runs;

    public DataIngestService(DatabaseClient db, AppProperties props, GridStore grid, ForecastRunService runs) {
        this.db = db;
        this.props = props;
        this.grid = grid;
        this.runs = runs;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        Set<Integer> routes = Set.copyOf(db.sql("SELECT route_id FROM core.route")
                .map((r, m) -> ((Number) r.get("route_id")).intValue()).all().collectList().block());

        ingestLabels(routes);
        importForecast(routes);
        grid.reload();
        runs.ensureYearRun();
    }

    /** Факт по часам из labels/. Маршруты вне справочника (маршрут 5) отбрасываются. */
    void ingestLabels(Set<Integer> routes) {
        Long existing = db.sql("SELECT count(*) AS c FROM core.actual_hourly")
                .map((r, m) -> ((Number) r.get("c")).longValue()).one().block();
        if (existing != null && existing > 0) {
            log.info("Факт уже загружен: {} строк", existing);
            return;
        }
        Path dir = Path.of(props.datasetDir(), "labels");
        List<String> rows = new ArrayList<>();
        int skipped = 0;
        for (String f : List.of("labels_day_train.csv", "labels_day_test.csv")) {
            Path p = dir.resolve(f);
            if (!Files.exists(p)) {
                log.warn("Нет файла {}: сервис работает без исторического факта", p);
                continue;
            }
            for (String[] c : readCsv(p)) {
                int route = Integer.parseInt(c[0]);
                if (!routes.contains(route)) { skipped++; continue; }
                LocalDate date = LocalDate.parse(c[1]);
                int hour = Integer.parseInt(c[2]);
                int boardings = Integer.parseInt(c[3]);
                rows.add("(" + route + ",'" + date + "'," + hour + "," + boardings + ")");
            }
        }
        insertBatched("INSERT INTO core.actual_hourly (route_id, fact_date, hour, boardings) VALUES ",
                " ON CONFLICT DO NOTHING", rows);
        log.info("Загружено строк факта: {}, отброшено (маршрут вне справочника): {}", rows.size(), skipped);
    }

    /**
     * Импорт прогноза ML-команды как отдельного прогона. Контракт: CSV
     * route;date;hour;prediction — тот же формат, что и submission.
     */
    void importForecast(Set<Integer> routes) {
        Long done = db.sql("SELECT count(*) AS c FROM core.forecast_run WHERE horizon='day' AND status='succeeded'")
                .map((r, m) -> ((Number) r.get("c")).longValue()).one().block();
        if (done != null && done > 0) return;

        Path p = Path.of(props.forecastFile());
        if (!Files.exists(p)) {
            log.warn("Нет файла прогноза {}: краткосрочный прогноз будет пуст", p);
            return;
        }
        List<String[]> csv = readCsv(p);
        LocalDate min = null, max = null;
        for (String[] c : csv) {
            LocalDate d = LocalDate.parse(c[1]);
            if (min == null || d.isBefore(min)) min = d;
            if (max == null || d.isAfter(max)) max = d;
        }
        long runId = runs.createRun("day", props.forecastModelVersion(), min, max, "running");
        List<String> rows = new ArrayList<>();
        for (String[] c : csv) {
            int route = Integer.parseInt(c[0]);
            if (!routes.contains(route)) continue;
            double pred = Math.max(0, Double.parseDouble(c[3]));
            rows.add("(" + runId + "," + route + ",'" + c[1] + "'," + Integer.parseInt(c[2]) + "," + pred + ")");
        }
        insertBatched("INSERT INTO core.forecast_hourly (run_id, route_id, forecast_date, hour, prediction) VALUES ",
                "", rows);
        runs.finish(runId, null);
        log.info("Импортирован прогноз {}: {} строк, {}..{}", props.forecastModelVersion(), rows.size(), min, max);
    }

    /**
     * Пакетная вставка. Значения собираются из уже распарсенных чисел и дат,
     * поэтому конкатенация здесь безопасна: пользовательского ввода нет.
     */
    public void insertBatched(String head, String tail, List<String> rows) {
        for (int i = 0; i < rows.size(); i += BATCH) {
            String sql = head + String.join(",", rows.subList(i, Math.min(rows.size(), i + BATCH))) + tail;
            db.sql(sql).then().block();
        }
    }

    static List<String[]> readCsv(Path p) {
        List<String[]> out = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            String line = r.readLine();                  // заголовок
            while ((line = r.readLine()) != null) {
                if (!line.isBlank()) out.add(line.trim().split(";"));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось прочитать " + p, e);
        }
        return out;
    }
}
