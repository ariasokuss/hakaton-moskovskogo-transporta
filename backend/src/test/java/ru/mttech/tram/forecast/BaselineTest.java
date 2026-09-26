package ru.mttech.tram.forecast;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import ru.mttech.tram.forecast.Model.Regime;
import ru.mttech.tram.forecast.Model.Route;

class BaselineTest {

    private static final List<Route> ROUTES = List.of(new Route(7, "7", null, "#000", 1, 0, 23, true));
    private static final LocalDate START = LocalDate.of(2025, 6, 2), END = LocalDate.of(2025, 10, 31);
    private static final LocalDate REPAIR_FROM = LocalDate.of(2025, 9, 6);

    /**
     * Лето (до 01.09): будни 50/ч, выходные 40/ч. Осень: будни 100/ч, выходные — ремонт (5/ч),
     * выходные ремонта исключены режимом. Обычная осенняя суббота без ремонта ≈ 40 × 100/50 = 80/ч.
     */
    private static NavigableMap<LocalDate, int[][]> history() {
        NavigableMap<LocalDate, int[][]> a = new TreeMap<>();
        for (LocalDate d = START; !d.isAfter(END); d = d.plusDays(1)) {
            boolean autumn = !d.isBefore(LocalDate.of(2025, 9, 1));
            boolean weekend = d.getDayOfWeek().getValue() > 5;
            int v = weekend ? (autumn && !d.isBefore(REPAIR_FROM) ? 5 : 40) : (autumn ? 100 : 50);
            int[][] day = new int[1][24];
            Arrays.fill(day[0], v);
            a.put(d, day);
        }
        return a;
    }

    private static final List<Regime> REPAIR = List.of(
            new Regime(7, REPAIR_FROM, LocalDate.of(2025, 11, 9), 96, "works", 0.6, true, "ремонт", null));

    @Test
    void будниСвежие_безПоправки() {
        double[][][] b = GridStore.computeBaseline(ROUTES, history(), REPAIR);
        int wed = DayOfWeek.WEDNESDAY.getValue() - 1;
        assertThat(b[0][wed][8]).isEqualTo(100.0);
    }

    @Test
    void выходныеВыбитыРемонтом_приводятсяКТекущемуУровню() {
        double[][][] b = GridStore.computeBaseline(ROUTES, history(), REPAIR);
        int sat = DayOfWeek.SATURDAY.getValue() - 1;
        // без поправки было бы 40 (летние субботы) — после ремонта это выглядело бы «превышением» +100%
        assertThat(b[0][sat][8]).isEqualTo(80.0);
    }

    @Test
    void безИсторииНулевойУровень() {
        assertThat(GridStore.computeBaseline(ROUTES, new TreeMap<>(), REPAIR)[0][0][8]).isZero();
    }
}
