package ru.mttech.tram.forecast;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.mttech.tram.forecast.Model.ForecastSeries;
import ru.mttech.tram.forecast.Model.Regime;
import ru.mttech.tram.forecast.Model.Route;
import ru.mttech.tram.forecast.Model.Snapshot;

/**
 * Держит в памяти снимок данных для выдачи. Запросы к API в БД не ходят.
 *
 * Горизонтальное масштабирование: каждый инстанс сам читает из БД последний
 * успешный прогон и периодически проверяет, не появился ли новый. Состояния,
 * привязанного к клиенту, нет — инстансы взаимозаменяемы.
 */
@Component
public class GridStore {

    private static final Logger log = LoggerFactory.getLogger(GridStore.class);

    /** Сколько последних «чистых» одноимённых дней недели берём в базовый уровень. */
    private static final int BASELINE_WEEKS = 8;

    private final DatabaseClient db;
    private final AtomicReference<Snapshot> current = new AtomicReference<>(Snapshot.empty());

    public GridStore(DatabaseClient db) {
        this.db = db;
    }

    public Snapshot get() {
        return current.get();
    }

    /** Перечитывает БД, если появился новый прогон. Дёшево: один запрос на id. */
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void refreshIfChanged() {
        Snapshot s = current.get();
        Long shortId = latestRunId("day");
        Long yearId = latestRunId("year");
        long curShort = s.shortTerm() == null ? -1 : s.shortTerm().runId();
        long curYear = s.year() == null ? -1 : s.year().runId();
        if ((shortId != null && shortId != curShort) || (yearId != null && yearId != curYear)) {
            reload();
        }
    }

    public synchronized void reload() {
        long t0 = System.currentTimeMillis();
        List<Route> routes = loadRoutes();
        Map<Integer, Integer> idx = new HashMap<>();
        for (int i = 0; i < routes.size(); i++) idx.put(routes.get(i).id(), i);

        List<Regime> regimes = loadRegimes();
        NavigableMap<LocalDate, int[][]> actuals = loadActuals(idx, routes.size());
        double[][][] baseline = computeBaseline(routes, actuals, regimes);
        double[][][] loadRatio = computeLoadRatio(idx, routes.size(), regimes, routes);

        ForecastSeries shortTerm = loadSeries(latestRunId("day"), idx, routes.size());
        ForecastSeries year = loadSeries(latestRunId("year"), idx, routes.size());

        current.set(new Snapshot(List.copyOf(routes), Map.copyOf(idx), actuals, baseline, loadRatio,
                List.copyOf(regimes), shortTerm, year));
        log.info("Снимок загружен за {} мс: маршрутов {}, дней факта {}, прогон day={}, year={}",
                System.currentTimeMillis() - t0, routes.size(), actuals.size(),
                shortTerm == null ? "нет" : shortTerm.runId(), year == null ? "нет" : year.runId());
    }

    // -------------------------------------------------------------------------

    private List<Route> loadRoutes() {
        return db.sql("""
                SELECT route_id, short_name, long_name, color_hex, display_order,
                       service_hour_start, service_hour_end, has_geometry
                FROM core.route WHERE is_active ORDER BY display_order""")
                .map((r, m) -> new Route(num(r.get("route_id")), r.get("short_name", String.class),
                        r.get("long_name", String.class), r.get("color_hex", String.class),
                        num(r.get("display_order")), num(r.get("service_hour_start")),
                        num(r.get("service_hour_end")), Boolean.TRUE.equals(r.get("has_geometry"))))
                .all().collectList().block();
    }

    private List<Regime> loadRegimes() {
        return db.sql("""
                SELECT route_id, date_from, date_to, dow_mask, kind, factor,
                       exclude_from_training, note, source_url
                FROM core.route_regime ORDER BY route_id, date_from""")
                .map((r, m) -> new Regime(num(r.get("route_id")), r.get("date_from", LocalDate.class),
                        r.get("date_to", LocalDate.class),
                        r.get("dow_mask") == null ? null : num(r.get("dow_mask")),
                        r.get("kind", String.class),
                        r.get("factor") == null ? null : ((Number) r.get("factor")).doubleValue(),
                        Boolean.TRUE.equals(r.get("exclude_from_training")),
                        r.get("note", String.class), r.get("source_url", String.class)))
                .all().collectList().block();
    }

    private NavigableMap<LocalDate, int[][]> loadActuals(Map<Integer, Integer> idx, int n) {
        NavigableMap<LocalDate, int[][]> out = new TreeMap<>();
        db.sql("SELECT route_id, fact_date, hour, boardings FROM core.actual_hourly")
                .map((r, m) -> new Object[]{num(r.get("route_id")), r.get("fact_date", LocalDate.class),
                        num(r.get("hour")), num(r.get("boardings"))})
                .all().toIterable().forEach(row -> {
                    Integer i = idx.get((Integer) row[0]);
                    if (i == null) return;
                    out.computeIfAbsent((LocalDate) row[1], d -> new int[n][24])[i][(Integer) row[2]] = (Integer) row[3];
                });
        return out;
    }

    /**
     * «Обычный уровень» для сравнения: медиана по последним {@value #BASELINE_WEEKS}
     * одноимённым дням недели, НЕ попавшим под режим с exclude_from_training.
     * Так у маршрута 50 базовые выходные берутся до начала ремонта, а не нули.
     */
    private double[][][] computeBaseline(List<Route> routes, NavigableMap<LocalDate, int[][]> actuals,
                                         List<Regime> regimes) {
        double[][][] b = new double[routes.size()][7][24];
        for (int ri = 0; ri < routes.size(); ri++) {
            int routeId = routes.get(ri).id();
            for (int dow = 0; dow < 7; dow++) {
                List<int[]> picked = new ArrayList<>();
                for (LocalDate d : actuals.descendingKeySet()) {
                    if (d.getDayOfWeek().getValue() - 1 != dow) continue;
                    if (isExcluded(routeId, d, regimes)) continue;
                    picked.add(actuals.get(d)[ri]);
                    if (picked.size() == BASELINE_WEEKS) break;
                }
                for (int h = 0; h < 24; h++) {
                    double[] v = new double[picked.size()];
                    for (int k = 0; k < v.length; k++) v[k] = picked.get(k)[h];
                    b[ri][dow][h] = median(v);
                }
            }
        }
        return b;
    }

    /**
     * Отношение нагрузки (успешные + пересадки) к посадкам по маршруту, дню недели
     * и часу. На прогнозный период нагрузка на вагон = прогноз посадок × отношение.
     * Нет данных о нагрузке — отношение 1, то есть нагрузка = посадки.
     */
    private double[][][] computeLoadRatio(Map<Integer, Integer> idx, int n, List<Regime> regimes, List<Route> routes) {
        double[][][] load = new double[n][7][24], board = new double[n][7][24];
        db.sql("SELECT route_id, fact_date, hour, boardings, load FROM core.actual_hourly WHERE load IS NOT NULL")
                .map((r, m) -> new Object[]{num(r.get("route_id")), r.get("fact_date", LocalDate.class),
                        num(r.get("hour")), num(r.get("boardings")), num(r.get("load"))})
                .all().toIterable().forEach(row -> {
                    Integer i = idx.get((Integer) row[0]);
                    LocalDate d = (LocalDate) row[1];
                    if (i == null || isExcluded((Integer) row[0], d, regimes)) return;
                    int dow = d.getDayOfWeek().getValue() - 1, h = (Integer) row[2];
                    board[i][dow][h] += (Integer) row[3];
                    load[i][dow][h] += (Integer) row[4];
                });
        double[][][] ratio = new double[n][7][24];
        for (int i = 0; i < n; i++) for (int d = 0; d < 7; d++) for (int h = 0; h < 24; h++)
            ratio[i][d][h] = board[i][d][h] >= 50 ? load[i][d][h] / board[i][d][h] : 1.0;
        return ratio;
    }

    private static boolean isExcluded(int routeId, LocalDate d, List<Regime> regimes) {
        if (d.getMonthValue() == 1 && d.getDayOfMonth() <= 8) return true;  // новогодние каникулы
        for (Regime r : regimes) if (r.excludeFromTraining() && r.covers(routeId, d)) return true;
        return false;
    }

    private Long latestRunId(String horizon) {
        return db.sql("""
                SELECT run_id FROM core.forecast_run
                WHERE horizon = :h AND status = 'succeeded'
                ORDER BY finished_at DESC, run_id DESC LIMIT 1""")
                .bind("h", horizon)
                .map((r, m) -> ((Number) r.get("run_id")).longValue())
                .one().block();
    }

    private ForecastSeries loadSeries(Long runId, Map<Integer, Integer> idx, int n) {
        if (runId == null) return null;
        Object[] meta = db.sql("""
                SELECT horizon, model_version, period_start, period_end
                FROM core.forecast_run WHERE run_id = :id""")
                .bind("id", runId)
                .map((r, m) -> new Object[]{r.get("horizon", String.class), r.get("model_version", String.class),
                        r.get("period_start", LocalDate.class), r.get("period_end", LocalDate.class)})
                .one().block();
        LocalDate start = (LocalDate) meta[2];
        int days = (int) (((LocalDate) meta[3]).toEpochDay() - start.toEpochDay()) + 1;
        double[][] v = new double[n][days * 24];
        db.sql("SELECT route_id, forecast_date, hour, prediction FROM core.forecast_hourly WHERE run_id = :id")
                .bind("id", runId)
                .map((r, m) -> new Object[]{num(r.get("route_id")), r.get("forecast_date", LocalDate.class),
                        num(r.get("hour")), ((Number) r.get("prediction")).doubleValue()})
                .all().toIterable().forEach(row -> {
                    Integer i = idx.get((Integer) row[0]);
                    if (i == null) return;
                    int day = (int) (((LocalDate) row[1]).toEpochDay() - start.toEpochDay());
                    v[i][day * 24 + (Integer) row[2]] = (Double) row[3];
                });
        return new ForecastSeries(runId, (String) meta[0], (String) meta[1], start, days, v);
    }

    static double median(double[] v) {
        if (v.length == 0) return 0;
        double[] s = v.clone();
        Arrays.sort(s);
        int m = s.length / 2;
        return s.length % 2 == 1 ? s[m] : (s[m - 1] + s[m]) / 2.0;
    }

    static int num(Object o) {
        return ((Number) o).intValue();
    }
}
