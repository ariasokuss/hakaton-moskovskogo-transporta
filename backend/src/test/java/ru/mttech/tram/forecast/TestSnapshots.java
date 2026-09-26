package ru.mttech.tram.forecast;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import ru.mttech.tram.forecast.Model.ForecastSeries;
import ru.mttech.tram.forecast.Model.Route;
import ru.mttech.tram.forecast.Model.Snapshot;

/** Маленький детерминированный снимок: 2 маршрута × 45 дней с 01.11.2025, значения известны заранее. */
public final class TestSnapshots {
    private TestSnapshots() {}

    public static final LocalDate START = LocalDate.of(2025, 11, 1);
    public static final int DAYS = 45;   // 01.11 — 15.12: захватывает границу месяцев

    /** Прогноз маршрута 17 в каждом часе = 10 + час; маршрута 50 — 2.46 (проверка округления). */
    public static Snapshot snapshot() {
        List<Route> routes = List.of(
                new Route(17, "17", "Маршрут 17", "#C1292E", 1, 0, 23, true),
                new Route(50, "50", "Маршрут 50", "#2E86AB", 2, 0, 23, true));
        double[][] v = new double[2][DAYS * 24];
        for (int d = 0; d < DAYS; d++) {
            for (int h = 0; h < 24; h++) {
                v[0][d * 24 + h] = 10 + h;
                v[1][d * 24 + h] = 2.46;
            }
        }
        double[][][] baseline = new double[2][7][24];
        double[][][] loadRatio = new double[2][7][24];
        for (double[][] r : baseline) for (double[] dow : r) Arrays.fill(dow, 10);
        for (double[][] r : loadRatio) for (double[] dow : r) Arrays.fill(dow, 1.0);
        return new Snapshot(routes, Map.of(17, 0, 50, 1), new TreeMap<>(), baseline, loadRatio, List.of(),
                new ForecastSeries(1, "day", "test", START, DAYS, v), null);
    }
}
