package ru.mttech.tram.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.mttech.tram.features.GeometryService;
import ru.mttech.tram.forecast.ForecastQueryService;
import ru.mttech.tram.forecast.ForecastQueryService.Granularity;
import ru.mttech.tram.forecast.ForecastQueryService.Scenario;
import ru.mttech.tram.forecast.GridStore;
import ru.mttech.tram.forecast.TestSnapshots;
import ru.mttech.tram.ingest.ExternalDataService;

class ExportControllerTest {

    private final ForecastQueryService query;

    ExportControllerTest() {
        GridStore grid = mock(GridStore.class);
        when(grid.get()).thenReturn(TestSnapshots.snapshot());
        query = new ForecastQueryService(grid, mock(GeometryService.class), mock(ExternalDataService.class));
    }

    @Test
    void сабмит_полнаяСеткаШаблонаИМаршрут5Нулями() {
        LocalDate from = TestSnapshots.START, to = from.plusDays(1);
        var data = query.series(null, null, from, to, Granularity.hour, Scenario.NEUTRAL, false);
        String[] lines = new String(ExportController.submission(data, List.of(5, 17, 50), from, to), StandardCharsets.UTF_8)
                .split("\n");
        assertThat(lines[0]).isEqualTo("route;date;hour;prediction");
        assertThat(lines).hasSize(1 + 3 * 2 * 24);
        assertThat(lines[1]).isEqualTo("5;2025-11-01;0;0");
        assertThat(lines[1 + 48]).isEqualTo("17;2025-11-01;0;10");
    }

    @Test
    void округлениеТолькоНаВыгрузке_2_46даёт2() {
        // Раньше значение сначала округлялось до 0.1 (2.5), потом до целого (3). Правильно — 2.
        LocalDate d = TestSnapshots.START;
        var data = query.series(50, null, d, d, Granularity.hour, Scenario.NEUTRAL, false);
        String[] lines = new String(ExportController.submission(data, List.of(50), d, d), StandardCharsets.UTF_8).split("\n");
        assertThat(lines[1]).isEqualTo("50;2025-11-01;0;2");
    }

    @Test
    void csv_целыеЧислаИBomДляExcel() {
        LocalDate d = TestSnapshots.START;
        String csv = new String(ExportController.csv(query.series(50, null, d, d, Granularity.day, Scenario.NEUTRAL, false)),
                StandardCharsets.UTF_8);
        assertThat(csv).startsWith("﻿маршрут;");
        assertThat(csv.split("\n")[1]).startsWith("50;2025-11-01;59;");   // 2.46 × 24 = 59.04 → 59
    }
}
