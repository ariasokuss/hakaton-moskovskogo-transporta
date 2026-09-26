package ru.mttech.tram.forecast;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

/**
 * Доменные типы. Вся рабочая сетка держится в памяти: прогноз ноябрь–декабрь —
 * 9 × 61 × 24 = 13 176 значений, годовой — ~79 тыс. Это меньше мегабайта,
 * поэтому выдача не ходит в БД и укладывается в единицы миллисекунд.
 */
public final class Model {
    private Model() {}

    /** Маршрут и его представление: цвет и подпись одинаковы на карте, в таблице и в экспорте. */
    public record Route(int id, String shortName, String longName, String color, int order,
                        int serviceHourStart, int serviceHourEnd, boolean hasGeometry) {}

    /** Режим маршрута: работы, приостановка, смена уровня. Данные, а не код. */
    public record Regime(int routeId, LocalDate from, LocalDate to, Integer dowMask, String kind,
                         Double factor, boolean excludeFromTraining, String note, String sourceUrl) {

        public boolean covers(int route, LocalDate date) {
            if (route != routeId || date.isBefore(from) || (to != null && date.isAfter(to))) return false;
            return dowMask == null || (dowMask & (1 << (date.getDayOfWeek().getValue() - 1))) != 0;
        }
    }

    /** Почасовой прогноз одного прогона. values[routeIdx][dayIdx * 24 + hour]. */
    public record ForecastSeries(long runId, String horizon, String modelVersion,
                                 LocalDate start, int days, double[][] values) {
        public boolean contains(LocalDate d) {
            return !d.isBefore(start) && d.isBefore(start.plusDays(days));
        }
        public double at(int routeIdx, LocalDate d, int hour) {
            return values[routeIdx][(int) (d.toEpochDay() - start.toEpochDay()) * 24 + hour];
        }
        public LocalDate end() { return start.plusDays(days - 1); }
    }

    /** Неизменяемый снимок всего, что нужно для выдачи. Подменяется атомарно. */
    public record Snapshot(List<Route> routes, Map<Integer, Integer> routeIdx,
                           NavigableMap<LocalDate, int[][]> actuals,
                           double[][][] baseline,            // [routeIdx][dow 0..6][hour]
                           double[][][] loadRatio,           // load / boardings, [routeIdx][dow][hour]
                           List<Regime> regimes,
                           ForecastSeries shortTerm,         // день/месяц: ноябрь–декабрь
                           ForecastSeries year) {            // год: качественно, может быть null

        public static Snapshot empty() {
            return new Snapshot(List.of(), Map.of(), new java.util.TreeMap<>(), new double[0][7][24],
                    new double[0][7][24], List.of(), null, null);
        }

        public ForecastSeries seriesFor(LocalDate d) {
            if (shortTerm != null && shortTerm.contains(d)) return shortTerm;
            if (year != null && year.contains(d)) return year;
            return null;
        }
    }
}
