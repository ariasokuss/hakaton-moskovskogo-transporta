package ru.mttech.tram.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Оглавление API на корне сервиса: жюри и фронтенд видят все методы
 * с готовыми примерами, не заглядывая в документацию.
 */
@RestController
public class IndexController {

    @GetMapping({"/", "/api"})
    public Map<String, Object> index() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("service", "Пантограф — ИИ-прогноз загрузки трамвайных маршрутов");
        m.put("slogan", "Из данных — энергия, из энергии — прогноз");
        m.put("docs", "README.md в репозитории");
        m.put("errors", "RFC 9457 Problem Details, сообщения на русском");
        m.put("scenarioParams", "kWeather, kEvent, kSeason, kTraffic, kManual — корректирующие коэффициенты 0.3–3.0, по умолчанию 1");
        m.put("endpoints", List.of(
                ep("GET", "/api/meta", "Периоды факта и прогноза, версии моделей", "/api/meta"),
                ep("GET", "/api/routes", "Справочник маршрутов: номер, цвет, часы работы", "/api/routes"),
                ep("GET", "/api/geometry", "Трассы и остановки всех маршрутов (GeoJSON)", "/api/geometry"),
                ep("GET", "/api/dashboard", "Главный экран диспетчера одним запросом", "/api/dashboard?date=2025-11-08"),
                ep("GET", "/api/forecast", "Прогноз: route, stop, horizon=day|month|year, date или from/to, granularity",
                        "/api/forecast?route=17&horizon=month&date=2025-11-15"),
                ep("GET", "/api/forecast?stop=", "Прогноз по остановке (доля прогноза маршрута)",
                        "/api/forecast?route=17&stop=Усадьба%20Останкино&horizon=day&date=2025-11-10"),
                ep("GET", "/api/stops", "Остановки маршрута с долей посадок", "/api/stops?route=17"),
                ep("GET", "/api/attention", "Отклонения от обычного уровня в обе стороны", "/api/attention?date=2025-11-08"),
                ep("GET", "/api/coefficients", "Корректирующие коэффициенты: ручки сценария, измеренные эффекты источников, контракт ML-модели",
                        "/api/coefficients"),
                ep("GET", "/api/external", "Внешние источники: ссылки, время и статус загрузки; контекст суток (date=)",
                        "/api/external?date=2025-11-08"),
                ep("GET", "/api/export", "Выгрузка: format=csv|xlsx|submission, те же route/stop/k* что на экране",
                        "/api/export?format=xlsx&from=2025-11-01&to=2025-11-07&granularity=day"),
                ep("POST", "/api/forecast/runs", "Фоновый пересчёт годового прогноза", "/api/forecast/runs?horizon=year"),
                ep("GET", "/api/forecast/runs/{id}", "Статус прогона", "/api/forecast/runs/2"),
                ep("GET", "/actuator/health", "Проверка живости", "/actuator/health")));
        return m;
    }

    private static Map<String, String> ep(String method, String path, String what, String example) {
        return Map.of("method", method, "path", path, "description", what, "example", example);
    }
}
