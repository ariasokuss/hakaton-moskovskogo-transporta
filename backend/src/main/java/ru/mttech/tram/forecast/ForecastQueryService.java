package ru.mttech.tram.forecast;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import ru.mttech.tram.api.ApiException;
import ru.mttech.tram.features.GeometryService;
import ru.mttech.tram.features.GeometryService.StopShare;
import ru.mttech.tram.forecast.Model.ForecastSeries;
import ru.mttech.tram.forecast.Model.Regime;
import ru.mttech.tram.forecast.Model.Route;
import ru.mttech.tram.forecast.Model.Snapshot;

/**
 * Выдача прогноза: агрегация вверх от часа, корректирующие коэффициенты,
 * отклонение от обычного уровня. Всё считается из снимка в памяти.
 *
 * Отклонение считает сервер, а не фронт — иначе экран и экспорт разойдутся.
 * Отклонение берётся в обе стороны: превышение — риск переполнения,
 * провал — сбой, снятые вагоны или закрытый участок.
 */
@Service
public class ForecastQueryService {

    public enum Granularity { hour, day, month }

    /** Корректирующие коэффициенты сценария. Применяются на выдаче, модель не трогают. */
    public record Scenario(double kWeather, double kEvent, double kSeason, double kTraffic, double kManual) {
        public static final Scenario NEUTRAL = new Scenario(1, 1, 1, 1, 1);

        public Scenario {
            for (double k : new double[]{kWeather, kEvent, kSeason, kTraffic, kManual}) {
                if (!(k >= 0.3 && k <= 3.0)) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "Некорректный коэффициент",
                            "Корректирующие коэффициенты должны быть в диапазоне от 0.3 до 3.0, получено: " + k);
                }
            }
        }

        public double total() { return kWeather * kEvent * kSeason * kTraffic * kManual; }
    }

    /** load — нагрузка на вагон с учётом пересадок (прогноз посадок × отношение load/boardings из истории). */
    public record Point(String t, double forecast, double load, double baseline, Double actual, Double deviationPct) {}

    public record RouteSeries(int routeId, String shortName, String color, List<Point> points,
                              double forecastTotal, double loadTotal, double baselineTotal, Double deviationPct) {}

    public record Attention(int routeId, String shortName, String color, String direction,
                            double forecast, double baseline, double delta, double deviationPct,
                            int peakHour, String reason) {}

    private final GridStore grid;
    private final GeometryService geometry;

    /**
     * Мемоизация главного экрана. Снимок неизменяемый, поэтому при тех же
     * параметрах ответ тот же. Кэш привязан к снимку: пришёл новый прогон —
     * ключи сменились сами. LRU на 2000 записей (61 день × сценарии).
     */
    private final Map<String, Map<String, Object>> dashboardCache =
            java.util.Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Map<String, Object>> e) {
                    return size() > 2000;
                }
            });

    public ForecastQueryService(GridStore grid, GeometryService geometry) {
        this.grid = grid;
        this.geometry = geometry;
    }

    // -------------------------------------------------------------------------

    public Map<String, Object> meta() {
        Snapshot s = grid.get();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("routes", s.routes().size());
        m.put("actualFrom", s.actuals().isEmpty() ? null : s.actuals().firstKey());
        m.put("actualTo", s.actuals().isEmpty() ? null : s.actuals().lastKey());
        m.put("shortTerm", describe(s.shortTerm()));
        m.put("year", describe(s.year()));
        m.put("defaultDate", s.shortTerm() == null ? null : s.shortTerm().start());
        return m;
    }

    public List<Route> routes() {
        return grid.get().routes();
    }

    /**
     * Композитный ответ «один экран — один запрос»: всё, что нужно главному
     * экрану диспетчера на выбранную дату.
     */
    public Map<String, Object> dashboard(LocalDate date, Scenario sc, double threshold) {
        Snapshot s = grid.get();
        String key = System.identityHashCode(s) + "|" + date + "|" + threshold + "|" + sc;
        Map<String, Object> hit = dashboardCache.get(key);
        if (hit != null) return hit;
        Map<String, Object> out = buildDashboard(s, date, sc, threshold);
        dashboardCache.put(key, out);
        return out;
    }

    private Map<String, Object> buildDashboard(Snapshot s, LocalDate date, Scenario sc, double threshold) {
        requireForecast(s, date);
        List<RouteSeries> series = new ArrayList<>();
        for (Route r : s.routes()) series.add(routeSeries(s, r, date, date, Granularity.hour, sc));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", date);
        out.put("dayOfWeek", date.getDayOfWeek().getValue());
        out.put("scenario", Map.of("kTotal", round(sc.total()), "kWeather", sc.kWeather(), "kEvent", sc.kEvent(),
                "kSeason", sc.kSeason(), "kTraffic", sc.kTraffic(), "kManual", sc.kManual()));
        out.put("model", describe(s.seriesFor(date)));
        out.put("routes", s.routes());
        out.put("series", series);
        out.put("attention", attention(date, sc, threshold));
        out.put("regimes", activeRegimes(s, date));
        double f = series.stream().mapToDouble(RouteSeries::forecastTotal).sum();
        double b = series.stream().mapToDouble(RouteSeries::baselineTotal).sum();
        out.put("network", Map.of("forecastTotal", round(f), "baselineTotal", round(b),
                "deviationPct", b > 0 ? round((f - b) / b * 100) : 0));
        return out;
    }

    public List<RouteSeries> series(Integer routeId, LocalDate from, LocalDate to, Granularity g, Scenario sc) {
        return series(routeId, null, from, to, g, sc);
    }

    /**
     * Прогноз по параметрам ТЗ: маршрут, остановка, интервал, детализация. Остановка задаётся
     * названием; прогноз остановки = прогноз маршрута × доля остановки (оценка распределением,
     * см. {@link GeometryService.StopShare}). Без маршрута — все маршруты, проходящие через остановку.
     */
    public List<RouteSeries> series(Integer routeId, String stop, LocalDate from, LocalDate to, Granularity g, Scenario sc) {
        Snapshot s = grid.get();
        if (to.isBefore(from)) throw new ApiException(HttpStatus.BAD_REQUEST, "Некорректный интервал",
                "Дата окончания " + to + " раньше даты начала " + from);
        if (to.toEpochDay() - from.toEpochDay() > 366) throw new ApiException(HttpStatus.BAD_REQUEST,
                "Слишком длинный интервал", "Максимальный интервал — 366 дней");
        requireForecast(s, from);
        requireForecast(s, to);
        List<RouteSeries> out = new ArrayList<>();
        for (Route r : s.routes()) {
            if (routeId == null || r.id() == routeId) out.add(routeSeries(s, r, from, to, g, sc));
        }
        if (out.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Маршрут не найден",
                "Маршрута " + routeId + " нет в справочнике. Доступны: "
                        + s.routes().stream().map(Route::shortName).toList());
        if (stop == null || stop.isBlank()) return out;
        String name = stop.trim();
        List<RouteSeries> atStop = new ArrayList<>();
        for (RouteSeries rs : out) {
            double share = geometry.stops(rs.routeId()).stream()
                    .filter(x -> x.name().equalsIgnoreCase(name)).mapToDouble(StopShare::share).sum();
            if (share > 0) atStop.add(scale(rs, share, name));
        }
        if (atStop.isEmpty()) {
            List<String> known = out.stream().flatMap(rs -> geometry.stops(rs.routeId()).stream().map(StopShare::name))
                    .distinct().limit(15).toList();
            throw new ApiException(HttpStatus.NOT_FOUND, "Остановка не найдена",
                    "Остановки «" + name + "» нет на " + (routeId == null ? "маршрутах сети" : "маршруте " + routeId)
                            + ". Например: " + known + ". Полный список — /api/stops"
                            + (routeId == null ? "" : "?route=" + routeId));
        }
        return atStop;
    }

    /** Ряд маршрута в пересчёте на остановку: доля от прогноза, обычного уровня и нагрузки. Факта по остановке нет. */
    private static RouteSeries scale(RouteSeries rs, double k, String stop) {
        List<Point> pts = new ArrayList<>(rs.points().size());
        for (Point p : rs.points()) {
            pts.add(new Point(p.t(), round(p.forecast() * k), round(p.load() * k), round(p.baseline() * k), null, p.deviationPct()));
        }
        return new RouteSeries(rs.routeId(), rs.shortName() + " · " + stop, rs.color(), pts,
                round(rs.forecastTotal() * k), round(rs.loadTotal() * k), round(rs.baselineTotal() * k), rs.deviationPct());
    }

    /** Ранжированный список отклонений от обычного уровня — в обе стороны. */
    public List<Attention> attention(LocalDate date, Scenario sc, double threshold) {
        Snapshot s = grid.get();
        requireForecast(s, date);
        ForecastSeries fs = s.seriesFor(date);
        int dow = date.getDayOfWeek().getValue() - 1;
        List<Attention> out = new ArrayList<>();
        for (Route r : s.routes()) {
            int ri = s.routeIdx().get(r.id());
            double f = 0, b = 0, peakDelta = 0;
            int peakHour = 0;
            for (int h = 0; h < 24; h++) {
                double fh = fs.at(ri, date, h) * sc.total();
                double bh = s.baseline()[ri][dow][h];
                f += fh;
                b += bh;
                if (Math.abs(fh - bh) > Math.abs(peakDelta)) { peakDelta = fh - bh; peakHour = h; }
            }
            if (b <= 0) continue;
            double pct = (f - b) / b * 100;
            if (Math.abs(pct) < threshold) continue;
            out.add(new Attention(r.id(), r.shortName(), r.color(), pct > 0 ? "above" : "below",
                    round(f), round(b), round(f - b), round(pct), peakHour, reason(s, r.id(), date)));
        }
        out.sort(Comparator.comparingDouble((Attention a) -> Math.abs(a.delta())).reversed());
        return out;
    }

    // -------------------------------------------------------------------------

    private RouteSeries routeSeries(Snapshot s, Route r, LocalDate from, LocalDate to, Granularity g, Scenario sc) {
        int ri = s.routeIdx().get(r.id());
        Map<String, double[]> buckets = new LinkedHashMap<>();   // t -> [forecast, baseline, actual, actualSeen, load]
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            ForecastSeries fs = s.seriesFor(d);
            int dow = d.getDayOfWeek().getValue() - 1;
            int[][] act = s.actuals().get(d);
            for (int h = 0; h < 24; h++) {
                String key = switch (g) {
                    case hour -> d + "T" + (h < 10 ? "0" : "") + h + ":00";
                    case day -> d.toString();
                    case month -> YearMonth.from(d).toString();
                };
                double[] acc = buckets.computeIfAbsent(key, k -> new double[5]);
                double f = fs == null ? 0 : fs.at(ri, d, h) * sc.total();
                acc[0] += f;
                acc[4] += f * s.loadRatio()[ri][dow][h];
                acc[1] += s.baseline()[ri][dow][h];
                if (act != null) { acc[2] += act[ri][h]; acc[3] = 1; }
            }
        }
        List<Point> pts = new ArrayList<>(buckets.size());
        double ft = 0, bt = 0, lt = 0;
        for (var e : buckets.entrySet()) {
            double[] a = e.getValue();
            ft += a[0];
            bt += a[1];
            lt += a[4];
            pts.add(new Point(e.getKey(), round(a[0]), round(a[4]), round(a[1]), a[3] > 0 ? a[2] : null,
                    a[1] > 0 ? round((a[0] - a[1]) / a[1] * 100) : null));
        }
        return new RouteSeries(r.id(), r.shortName(), r.color(), pts, round(ft), round(lt), round(bt),
                bt > 0 ? round((ft - bt) / bt * 100) : null);
    }

    private static List<Regime> activeRegimes(Snapshot s, LocalDate d) {
        return s.regimes().stream().filter(r -> r.covers(r.routeId(), d)).toList();
    }

    private static String reason(Snapshot s, int routeId, LocalDate d) {
        return s.regimes().stream().filter(r -> r.covers(routeId, d)).map(Regime::note).findFirst().orElse(null);
    }

    private static void requireForecast(Snapshot s, LocalDate d) {
        if (s.seriesFor(d) != null) return;
        StringBuilder sb = new StringBuilder("На дату ").append(d).append(" прогноза нет.");
        if (s.shortTerm() != null) sb.append(" Почасовой прогноз: ").append(s.shortTerm().start())
                .append(" — ").append(s.shortTerm().end()).append('.');
        if (s.year() != null) sb.append(" Годовой: ").append(s.year().start()).append(" — ").append(s.year().end()).append('.');
        if (s.shortTerm() == null && s.year() == null) sb.append(" Прогноз ещё не загружен — повторите через минуту.");
        throw new ApiException(HttpStatus.NOT_FOUND, "Нет прогноза на дату", sb.toString());
    }

    private static Map<String, Object> describe(ForecastSeries f) {
        if (f == null) return null;
        return Map.of("runId", f.runId(), "horizon", f.horizon(), "modelVersion", f.modelVersion(),
                "from", f.start(), "to", f.end());
    }

    static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
