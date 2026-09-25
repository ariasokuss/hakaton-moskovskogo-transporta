package ru.mttech.tram.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.mttech.tram.api.ApiException;
import ru.mttech.tram.forecast.ForecastQueryService;
import ru.mttech.tram.forecast.ForecastQueryService.Granularity;
import ru.mttech.tram.forecast.ForecastQueryService.Point;
import ru.mttech.tram.forecast.ForecastQueryService.RouteSeries;
import ru.mttech.tram.forecast.ForecastQueryService.Scenario;

/**
 * Выгрузка CSV/XLSX. Экспорт повторяет экран: те же параметры, те же
 * обозначения маршрутов, те же коэффициенты. Округление к ближайшему целому —
 * только здесь, на границе выгрузки.
 *
 * format=submission — ровно формат сабмита организаторов: route;date;hour;prediction.
 */
@RestController
@RequestMapping("/api/export")
public class ExportController {

    private final ForecastQueryService query;

    public ExportController(ForecastQueryService query) {
        this.query = query;
    }

    @GetMapping
    public ResponseEntity<byte[]> export(@RequestParam(defaultValue = "csv") String format,
                                         @RequestParam(required = false) Integer route,
                                         @RequestParam LocalDate from,
                                         @RequestParam LocalDate to,
                                         @RequestParam(defaultValue = "hour") Granularity granularity,
                                         @RequestParam(defaultValue = "1") double kWeather,
                                         @RequestParam(defaultValue = "1") double kEvent,
                                         @RequestParam(defaultValue = "1") double kSeason,
                                         @RequestParam(defaultValue = "1") double kTraffic,
                                         @RequestParam(defaultValue = "1") double kManual) throws IOException {
        Scenario sc = new Scenario(kWeather, kEvent, kSeason, kTraffic, kManual);
        String name = "forecast_" + from + "_" + to;
        return switch (format) {
            case "csv" -> file(csv(query.series(route, from, to, granularity, sc)), name + ".csv", "text/csv; charset=utf-8");
            case "submission" -> file(submission(query.series(route, from, to, Granularity.hour, sc)),
                    "submission_" + from + "_" + to + ".csv", "text/csv; charset=utf-8");
            case "xlsx" -> file(xlsx(query.series(route, from, to, granularity, sc)), name + ".xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "Неизвестный формат",
                    "format должен быть одним из: csv, xlsx, submission. Получено: " + format);
        };
    }

    private static byte[] csv(List<RouteSeries> data) {
        StringBuilder sb = new StringBuilder("﻿маршрут;период;прогноз;нагрузка_с_пересадками;обычный_уровень;факт;отклонение_%\n");
        for (RouteSeries rs : data) {
            for (Point p : rs.points()) {
                sb.append(rs.shortName()).append(';').append(p.t()).append(';')
                        .append(Math.round(p.forecast())).append(';').append(Math.round(p.load())).append(';')
                        .append(Math.round(p.baseline())).append(';')
                        .append(p.actual() == null ? "" : Math.round(p.actual())).append(';')
                        .append(p.deviationPct() == null ? "" : p.deviationPct()).append('\n');
            }
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] submission(List<RouteSeries> data) {
        StringBuilder sb = new StringBuilder("route;date;hour;prediction\n");
        for (RouteSeries rs : data) {
            for (Point p : rs.points()) {
                sb.append(rs.routeId()).append(';').append(p.t(), 0, 10).append(';')
                        .append(Integer.parseInt(p.t().substring(11, 13))).append(';')
                        .append(Math.round(p.forecast())).append('\n');
            }
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] xlsx(List<RouteSeries> data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Workbook wb = new Workbook(out, "tram-forecast", "1.0");
        Worksheet ws = wb.newWorksheet("Прогноз");
        String[] head = {"Маршрут", "Период", "Прогноз", "Нагрузка с пересадками", "Обычный уровень", "Факт", "Отклонение, %"};
        for (int c = 0; c < head.length; c++) ws.value(0, c, head[c]);
        ws.range(0, 0, 0, head.length - 1).style().bold().fillColor("DDDDDD").set();
        int row = 1;
        for (RouteSeries rs : data) {
            for (Point p : rs.points()) {
                ws.value(row, 0, rs.shortName());
                ws.value(row, 1, p.t());
                ws.value(row, 2, Math.round(p.forecast()));
                ws.value(row, 3, Math.round(p.load()));
                ws.value(row, 4, Math.round(p.baseline()));
                if (p.actual() != null) ws.value(row, 5, Math.round(p.actual()));
                if (p.deviationPct() != null) ws.value(row, 6, p.deviationPct());
                row++;
            }
        }
        ws.freezePane(0, 1);
        wb.finish();
        return out.toByteArray();
    }

    private static ResponseEntity<byte[]> file(byte[] body, String name, String type) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .contentType(MediaType.parseMediaType(type))
                .body(body);
    }
}
