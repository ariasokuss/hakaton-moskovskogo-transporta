package ru.mttech.tram.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import ru.mttech.tram.forecast.ForecastQueryService;
import ru.mttech.tram.forecast.ForecastQueryService.Granularity;

class ApiContractTest {

    private final ForecastQueryService q = mock(ForecastQueryService.class);

    ApiContractTest() {
        when(q.meta()).thenReturn(Map.of("defaultDate", LocalDate.of(2025, 11, 1)));
        when(q.lastForecastDate()).thenReturn(LocalDate.of(2026, 10, 31));
    }

    @Test
    void горизонтДень_почасово() {
        var w = ForecastController.Window.resolve("day", LocalDate.of(2025, 11, 8), null, null, null, q);
        assertThat(w.from()).isEqualTo(w.to());
        assertThat(w.granularity()).isEqualTo(Granularity.hour);
    }

    @Test
    void горизонтМесяц_календарныйМесяцПоДням() {
        var w = ForecastController.Window.resolve("month", LocalDate.of(2025, 11, 8), null, null, null, q);
        assertThat(w.from()).isEqualTo(LocalDate.of(2025, 11, 1));
        assertThat(w.to()).isEqualTo(LocalDate.of(2025, 11, 30));
        assertThat(w.granularity()).isEqualTo(Granularity.day);
    }

    @Test
    void горизонтГод_12МесяцевНеДальшеПрогона() {
        var w = ForecastController.Window.resolve("year", LocalDate.of(2025, 11, 8), null, null, null, q);
        assertThat(w.from()).isEqualTo(LocalDate.of(2025, 11, 1));
        assertThat(w.to()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(w.granularity()).isEqualTo(Granularity.month);

        var clipped = ForecastController.Window.resolve("year", LocalDate.of(2025, 12, 5), null, null, null, q);
        assertThat(clipped.to()).isEqualTo(LocalDate.of(2026, 10, 31));
    }

    @Test
    void явныйИнтервалПерекрываетГоризонт() {
        var w = ForecastController.Window.resolve("year", null, LocalDate.of(2025, 11, 3), LocalDate.of(2025, 11, 9),
                Granularity.hour, q);
        assertThat(w.from()).isEqualTo(LocalDate.of(2025, 11, 3));
        assertThat(w.granularity()).isEqualTo(Granularity.hour);
    }

    @Test
    void неизвестныйГоризонт_400ПоРусски() {
        assertThatThrownBy(() -> ForecastController.Window.resolve("week", null, null, null, null, q))
                .isInstanceOf(ApiException.class).hasMessageContaining("day, month, year");
    }

    @Test
    void ошибкаВФорматеRfc9457_безСтектрейса() {
        ProblemDetail p = new ApiErrorHandler().api(new ApiException(HttpStatus.NOT_FOUND, "Нет прогноза на дату", "На дату 2027-01-01 прогноза нет."));
        assertThat(p.getStatus()).isEqualTo(404);
        assertThat(p.getTitle()).isEqualTo("Нет прогноза на дату");
        assertThat(p.getDetail()).isEqualTo("На дату 2027-01-01 прогноза нет.");
        assertThat(p.getType().toString()).isEqualTo("about:blank");

        ProblemDetail internal = new ApiErrorHandler().other(new NullPointerException("secret"));
        assertThat(internal.getStatus()).isEqualTo(500);
        assertThat(internal.getDetail()).doesNotContain("secret").doesNotContain("NullPointer");
    }
}
