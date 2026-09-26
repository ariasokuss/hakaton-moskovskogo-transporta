package ru.mttech.tram.forecast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import ru.mttech.tram.api.ApiException;
import ru.mttech.tram.features.GeometryService;
import ru.mttech.tram.features.GeometryService.StopShare;
import ru.mttech.tram.forecast.ForecastQueryService.Granularity;
import ru.mttech.tram.forecast.ForecastQueryService.RouteSeries;
import ru.mttech.tram.forecast.ForecastQueryService.Scenario;
import ru.mttech.tram.ingest.ExternalDataService;

class ForecastQueryServiceTest {

    private ForecastQueryService query;

    @BeforeEach
    void setUp() {
        GridStore grid = mock(GridStore.class);
        when(grid.get()).thenReturn(TestSnapshots.snapshot());
        GeometryService geometry = mock(GeometryService.class);
        when(geometry.stops(17)).thenReturn(List.of(
                new StopShare("Останкино", 55.8, 37.6, 2, 0.25),
                new StopShare("Рижская", 55.79, 37.63, 1, 0.75)));
        query = new ForecastQueryService(grid, geometry, mock(ExternalDataService.class));
    }

    @Test
    void часМинимальнаяЕдиница_деньИМесяцСходятсяСоСуммойЧасов() {
        LocalDate from = LocalDate.of(2025, 11, 1), to = LocalDate.of(2025, 12, 15);
        double hours = total(query.series(17, from, to, Granularity.hour, Scenario.NEUTRAL));
        double days = total(query.series(17, from, to, Granularity.day, Scenario.NEUTRAL));
        double months = total(query.series(17, from, to, Granularity.month, Scenario.NEUTRAL));
        double expected = 45 * (24 * 10 + 276);   // Σ(10 + h) по суткам = 516
        assertThat(hours).isEqualTo(expected);
        assertThat(days).isEqualTo(expected);
        assertThat(months).isEqualTo(expected);
        assertThat(query.series(17, from, to, Granularity.month, Scenario.NEUTRAL).get(0).points())
                .extracting(ForecastQueryService.Point::t).containsExactly("2025-11", "2025-12");
    }

    @Test
    void коэффициентыУмножаютПрогнозНаВыдаче() {
        LocalDate d = LocalDate.of(2025, 11, 3);
        double base = query.series(17, d, d, Granularity.day, Scenario.NEUTRAL).get(0).forecastTotal();
        double scaled = query.series(17, d, d, Granularity.day, new Scenario(1.2, 1, 1, 1, 0.5)).get(0).forecastTotal();
        assertThat(scaled).isEqualTo(Math.round(base * 0.6 * 10) / 10.0);
    }

    @Test
    void коэффициентВнеДиапазона_400() {
        assertThatThrownBy(() -> new Scenario(5, 1, 1, 1, 1))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void датаВнеПрогноза_404СДоступнымПериодом() {
        LocalDate d = LocalDate.of(2027, 1, 1);
        assertThatThrownBy(() -> query.series(17, d, d, Granularity.hour, Scenario.NEUTRAL))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("2025-11-01")
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void неизвестныйМаршрут_404СоСпискомДоступных() {
        LocalDate d = TestSnapshots.START;
        assertThatThrownBy(() -> query.series(99, d, d, Granularity.hour, Scenario.NEUTRAL))
                .isInstanceOf(ApiException.class).hasMessageContaining("17");
    }

    @Test
    void остановка_доляПрогнозаМаршрута() {
        LocalDate d = TestSnapshots.START;
        double route = query.series(17, null, d, d, Granularity.day, Scenario.NEUTRAL).get(0).forecastTotal();
        RouteSeries stop = query.series(17, "останкино", d, d, Granularity.day, Scenario.NEUTRAL).get(0);
        assertThat(stop.forecastTotal()).isEqualTo(route * 0.25);
        assertThat(stop.shortName()).isEqualTo("17 · останкино");
    }

    @Test
    void неизвестнаяОстановка_404ССписком() {
        LocalDate d = TestSnapshots.START;
        assertThatThrownBy(() -> query.series(17, "Луна", d, d, Granularity.day, Scenario.NEUTRAL))
                .isInstanceOf(ApiException.class).hasMessageContaining("Рижская");
    }

    @Test
    void безОкругления_дляЭкспорта() {
        LocalDate d = TestSnapshots.START;
        double rounded = query.series(50, null, d, d, Granularity.hour, Scenario.NEUTRAL, true).get(0).points().get(0).forecast();
        double raw = query.series(50, null, d, d, Granularity.hour, Scenario.NEUTRAL, false).get(0).points().get(0).forecast();
        assertThat(rounded).isEqualTo(2.5);
        assertThat(raw).isEqualTo(2.46);
    }

    @Test
    void отклонениеСчитаетсяВОбеСтороны() {
        // Маршрут 50: 2.46 × 24 против обычных 10 × 24 — провал; маршрут 17: 516 против 240 — превышение.
        var att = query.attention(TestSnapshots.START, Scenario.NEUTRAL, 10);
        assertThat(att).extracting(ForecastQueryService.Attention::direction).containsExactlyInAnyOrder("above", "below");
    }

    @Test
    void последняяДатаПрогноза() {
        assertThat(query.lastForecastDate()).isEqualTo(LocalDate.of(2025, 12, 15));
    }

    private static double total(List<RouteSeries> s) {
        return s.get(0).points().stream().mapToDouble(ForecastQueryService.Point::forecast).sum();
    }
}
