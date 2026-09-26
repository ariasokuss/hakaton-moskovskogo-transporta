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
        ingestLoad(routes);
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
     * Нагрузка на вагон: валидации по кодам {1, 90} — успешные плюс пересадки.
     * Считается из сырых 10 ГБ скриптом tools/build_load.sh, сюда приходит агрегат.
     */
    void ingestLoad(Set<Integer> routes) {
        Long missing = db.sql("SELECT count(*) AS c FROM core.actual_hourly WHERE load IS NULL")
                .map((r, m) -> ((Number) r.get("c")).longValue()).one().block();
        Path p = Path.of(props.loadFile());
        if (missing == null || missing == 0 || !Files.exists(p)) {
            if (!Files.exists(p)) log.warn("Нет файла нагрузки {}: load не рассчитан", p);
            return;
        }
        List<String> rows = new ArrayList<>();
        for (String[] c : readCsv(p)) {
            int route = Integer.parseInt(c[0]);
            if (!routes.contains(route)) continue;
            LocalDate date = LocalDate.parse(c[1]);
            rows.add("(" + route + ",DATE '" + date + "'," + Integer.parseInt(c[2]) + "," + Integer.parseInt(c[4]) + ")");
        }
        for (int i = 0; i < rows.size(); i += BATCH) {
            db.sql("UPDATE core.actual_hourly a SET load = v.l FROM (VALUES "
                    + String.join(",", rows.subList(i, Math.min(rows.size(), i + BATCH)))
                    + ") AS v(r, d, h, l) WHERE a.route_id = v.r AND a.fact_date = v.d AND a.hour = v.h").then().block();
        }
        log.info("Нагрузка с пересадками загружена: {} строк", rows.size());
    }

    /**
     * Импорт прогноза ML-модели как отдельного прогона. Основной контракт — почасовой прогноз
     * без округления {@code route;date;hour;pred;model_version} (artifacts/forecast_hourly.csv
     * из ноутбука). Совместимость: формат сабмита {@code route;date;hour;prediction}.
     * Колонки ищутся по заголовку. Новая версия модели импортируется новым прогоном,
     * выдача переключается на последний успешный — старые прогоны остаются для сравнения.
     */
    void importForecast(Set<Integer> routes) {
        Path p = Path.of(props.forecastFile());
        if (!Files.exists(p)) {
            Path fallback = p.resolveSibling("submission_latest.csv");
            if (!Files.exists(fallback)) {
                log.warn("Нет файла прогноза {} (и {}): краткосрочный прогноз будет пуст", p, fallback);
                return;
            }
            log.warn("Нет {}, используется сабмит {}", p, fallback);
            p = fallback;
        }
        List<String[]> csv = new ArrayList<>();
        String[] head = readCsvWithHeader(p, csv);
        int iRoute = col(head, "route"), iDate = col(head, "date"), iHour = col(head, "hour");
        int iPred = col(head, "pred") >= 0 ? col(head, "pred") : col(head, "prediction");
        int iVer = col(head, "model_version");
        if (iRoute < 0 || iDate < 0 || iHour < 0 || iPred < 0) {
            throw new IllegalStateException("В файле прогноза " + p
                    + " нет колонок route;date;hour;pred|prediction. Заголовок: " + String.join(";", head));
        }
        String version = iVer >= 0 && !csv.isEmpty() ? "ml-" + csv.get(0)[iVer] : props.forecastModelVersion();
        Long same = db.sql("SELECT count(*) AS c FROM core.forecast_run WHERE horizon='day' AND status='succeeded' AND model_version=:v")
                .bind("v", version).map((r, m) -> ((Number) r.get("c")).longValue()).one().block();
        if (same != null && same > 0) {
            log.info("Прогноз {} уже загружен", version);
            return;
        }
        LocalDate min = null, max = null;
        for (String[] c : csv) {
            LocalDate d = LocalDate.parse(c[iDate].substring(0, 10));
            if (min == null || d.isBefore(min)) min = d;
            if (max == null || d.isAfter(max)) max = d;
        }
        long runId = runs.createRun("day", version, min, max, "running");
        List<String> rows = new ArrayList<>();
        for (String[] c : csv) {
            int route = Integer.parseInt(c[iRoute]);
            if (!routes.contains(route)) continue;
            double pred = Math.max(0, Double.parseDouble(c[iPred]));
            rows.add("(" + runId + "," + route + ",'" + c[iDate].substring(0, 10) + "'," + Integer.parseInt(c[iHour]) + "," + pred + ")");
        }
        insertBatched("INSERT INTO core.forecast_hourly (run_id, route_id, forecast_date, hour, prediction) VALUES ",
                "", rows);
        runs.finish(runId, null);
        log.info("Импортирован прогноз {}: {} строк, {}..{} (файл {})", version, rows.size(), min, max, p.getFileName());
    }

    private static int col(String[] head, String name) {
        for (int i = 0; i < head.length; i++) if (head[i].trim().equalsIgnoreCase(name)) return i;
        return -1;
    }

    /** Читает CSV с разделителем «;»: заголовок возвращается, строки данных — в {@code out}. */
    public static String[] readCsvWithHeader(Path p, List<String[]> out) {
        try (BufferedReader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            String first = r.readLine();
            if (first == null) throw new IllegalStateException("Пустой файл " + p);
            String[] head = first.replace("\uFEFF", "").trim().split(";");
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isBlank()) out.add(line.trim().split(";"));
            }
            return head;
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось прочитать " + p, e);
        }
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
