package ru.mttech.tram.forecast;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.mttech.tram.forecast.Model.Snapshot;

/**
 * Асинхронные прогоны расчёта. Каждый прогон — строка forecast_run со статусом.
 * Долгий горизонт считается отдельно и не блокирует короткий: выдача читает
 * последний успешный прогон своего горизонта, а новый появляется, когда готов.
 */
@Service
public class ForecastRunService {

    private static final Logger log = LoggerFactory.getLogger(ForecastRunService.class);

    /** Годовой горизонт: с начала прогнозного периода на 365 дней. */
    static final LocalDate YEAR_START = LocalDate.of(2025, 11, 1);
    static final String YEAR_MODEL = "seasonal-profile-year-v1";

    private final DatabaseClient db;
    private final GridStore grid;

    public ForecastRunService(DatabaseClient db, GridStore grid) {
        this.db = db;
        this.grid = grid;
    }

    public long createRun(String horizon, String model, LocalDate from, LocalDate to, String status) {
        return db.sql("""
                INSERT INTO core.forecast_run (horizon, status, model_version, period_start, period_end, started_at)
                VALUES (:h, :s, :m, :f, :t, now()) RETURNING run_id""")
                .bind("h", horizon).bind("s", status).bind("m", model).bind("f", from).bind("t", to)
                .map((r, m) -> ((Number) r.get("run_id")).longValue()).one().block();
    }

    public void finish(long runId, String error) {
        db.sql("UPDATE core.forecast_run SET status = :s, finished_at = now(), error_message = :e WHERE run_id = :id")
                .bind("s", error == null ? "succeeded" : "failed")
                .bind("e", error == null ? "" : error)
                .bind("id", runId).then().block();
    }

    public void ensureYearRun() {
        Long n = db.sql("SELECT count(*) AS c FROM core.forecast_run WHERE horizon='year' AND status IN ('succeeded','running','pending')")
                .map((r, m) -> ((Number) r.get("c")).longValue()).one().block();
        if (n == null || n == 0) startYearRun();
    }

    /** Ставит годовой прогон в очередь и сразу возвращает его id. Расчёт идёт в фоне. */
    public long startYearRun() {
        long runId = createRun("year", YEAR_MODEL, YEAR_START, YEAR_START.plusDays(364), "pending");
        Mono.fromRunnable(() -> computeYear(runId))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(v -> {}, e -> log.error("Годовой прогон {} упал", runId, e));
        return runId;
    }

    public Mono<Map<String, Object>> status(long runId) {
        return db.sql("""
                SELECT run_id, horizon, status, model_version, period_start, period_end,
                       created_at, finished_at, error_message
                FROM core.forecast_run WHERE run_id = :id""")
                .bind("id", runId).fetch().one();
    }

    /**
     * Годовой прогноз — качественный, как и допускают организаторы: истории
     * всего 10 месяцев. Базовый уровень (день недели × час) умножается на
     * месячный коэффициент сезонности из 2025 года. Для ноября и декабря
     * истории нет — берётся зимний режим января–февраля.
     */
    void computeYear(long runId) {
        try {
            db.sql("UPDATE core.forecast_run SET status='running', started_at=now() WHERE run_id=:id")
                    .bind("id", runId).then().block();
            Snapshot s = grid.get();
            if (s.routes().isEmpty() || s.actuals().isEmpty()) {
                finish(runId, "Нет исторического факта: годовой прогноз не из чего строить");
                return;
            }
            double[][] monthFactor = monthFactors(s);
            List<String> rows = new ArrayList<>();
            for (int day = 0; day < 365; day++) {
                LocalDate d = YEAR_START.plusDays(day);
                int dow = d.getDayOfWeek().getValue() - 1;
                for (int ri = 0; ri < s.routes().size(); ri++) {
                    double f = monthFactor[ri][d.getMonthValue() - 1];
                    int routeId = s.routes().get(ri).id();
                    for (int h = 0; h < 24; h++) {
                        double v = s.baseline()[ri][dow][h] * f;
                        rows.add("(" + runId + "," + routeId + ",'" + d + "'," + h + "," + Math.round(v * 1000) / 1000.0 + ")");
                    }
                }
            }
            for (int i = 0; i < rows.size(); i += 5000) {
                db.sql("INSERT INTO core.forecast_hourly (run_id, route_id, forecast_date, hour, prediction) VALUES "
                        + String.join(",", rows.subList(i, Math.min(rows.size(), i + 5000)))).then().block();
            }
            finish(runId, null);
            grid.reload();
            log.info("Годовой прогон {} готов: {} строк", runId, rows.size());
        } catch (Exception e) {
            finish(runId, e.getMessage());
            throw e;
        }
    }

    /** Отношение среднего дневного объёма месяца к сентябрю–октябрю (опорный уровень). */
    private static double[][] monthFactors(Snapshot s) {
        int n = s.routes().size();
        double[][] sum = new double[n][12];
        int[][] cnt = new int[n][12];
        s.actuals().forEach((d, v) -> {
            int m = d.getMonthValue() - 1;
            for (int ri = 0; ri < n; ri++) {
                int total = 0;
                for (int h = 0; h < 24; h++) total += v[ri][h];
                sum[ri][m] += total;
                cnt[ri][m]++;
            }
        });
        double[][] f = new double[n][12];
        for (int ri = 0; ri < n; ri++) {
            double ref = avg(sum[ri][8], cnt[ri][8], sum[ri][9], cnt[ri][9]);
            for (int m = 0; m < 12; m++) {
                f[ri][m] = cnt[ri][m] > 0 && ref > 0 ? (sum[ri][m] / cnt[ri][m]) / ref : 1.0;
            }
            // Ноябрь и декабрь: истории нет, берём зимний режим (январь без каникул ~ февраль).
            double winter = cnt[ri][1] > 0 && ref > 0 ? (sum[ri][1] / cnt[ri][1]) / ref : 1.0;
            f[ri][10] = (1.0 + winter) / 2;   // ноябрь — переход от осени к зиме
            f[ri][11] = winter;
        }
        return f;
    }

    private static double avg(double s1, int c1, double s2, int c2) {
        return c1 + c2 == 0 ? 0 : (s1 + s2) / (c1 + c2);
    }
}
