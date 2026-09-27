package ru.mttech.tram.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.mttech.tram.features.GeometryService;
import ru.mttech.tram.forecast.ForecastQueryService;
import ru.mttech.tram.forecast.ForecastQueryService.Granularity;
import ru.mttech.tram.forecast.ForecastQueryService.Scenario;
import ru.mttech.tram.forecast.GridStore;
import ru.mttech.tram.forecast.TestSnapshots;
import ru.mttech.tram.ingest.ExternalDataService;

class PdfReportTest {

    @Test
    void отчётНаДеньИМесяц_валидныйPdfСоШрифтом() {
        GridStore grid = mock(GridStore.class);
        when(grid.get()).thenReturn(TestSnapshots.snapshot());
        ForecastQueryService q = new ForecastQueryService(grid, mock(GeometryService.class), mock(ExternalDataService.class));
        LocalDate d = TestSnapshots.START;
        for (String h : List.of("day", "month")) {
            LocalDate to = "day".equals(h) ? d : d.withDayOfMonth(d.lengthOfMonth());
            var series = q.series(null, null, d, to, "day".equals(h) ? Granularity.hour : Granularity.day, new Scenario(1, 1, 0.45, 1, 1), false);
            byte[] pdf = PdfReport.render(new PdfReport.Input(h, d, to, d, "вся сеть", "#2f6fdb", PdfReport.sumSeries(series),
                    q.series(null, null, d, d, Granularity.hour, Scenario.NEUTRAL, false), q.attention(d, Scenario.NEUTRAL, 10), List.of(),
                    null, List.of(), List.of(), new Scenario(1, 1, 0.45, 1, 1), "test", Map.of(17, "Маршрут 17"),
                    "day".equals(h) ? List.of() : series, null));
            assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
            assertThat(pdf.length).isGreaterThan(20_000);                       // встроен шрифт PT Sans
            try {
                assertThat(new com.lowagie.text.pdf.PdfReader(pdf).getNumberOfPages()).isGreaterThanOrEqualTo(2);   // сводка + детали
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
        }
    }
}
