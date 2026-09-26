package ru.mttech.tram.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.mttech.tram.config.AppProperties;
import tools.jackson.databind.ObjectMapper;

/**
 * Корректирующие коэффициенты: какие ручки есть в сценарии и что измерено моделью.
 *
 * Ручки сценария (kWeather, kEvent, kSeason, kTraffic, kManual) применяются на выдаче
 * поверх сохранённого прогноза — пересчёт мгновенный, модель не трогается (критерий 2в).
 * Измеренные эффекты, ссылки на источники и контракт модели приходят артефактами
 * ML-ноутбука (coefficients.json, ml_contract.json) — источники без ссылки жюри не засчитывает.
 */
@RestController
@RequestMapping("/api")
public class CoefficientsController {

    private final AppProperties props;
    private final ObjectMapper json;

    public CoefficientsController(AppProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json;
    }

    @GetMapping("/coefficients")
    public Map<String, Object> coefficients() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("formula", "прогноз на экране = прогноз модели × kWeather × kEvent × kSeason × kTraffic × kManual");
        out.put("scenarioParams", List.of(
                knob("kWeather", "Погода", "осадки, мороз"),
                knob("kEvent", "Событие", "мероприятие, перекрытие, сбой"),
                knob("kSeason", "Сезон", "каникулы, праздники"),
                knob("kTraffic", "Трафик", "пробки на дорогах"),
                knob("kManual", "Ручная", "решение диспетчера")));
        Object model = read(props.coefficientsFile());
        Object contract = read(props.mlContractFile());
        out.put("model", model);
        out.put("mlContract", contract);
        if (model == null) {
            out.put("note", "Артефакт коэффициентов ML-модели не подключён: положите coefficients.json в data/forecast/");
        }
        return out;
    }

    private static Map<String, Object> knob(String key, String label, String hint) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("label", label);
        m.put("hint", hint);
        m.put("min", 0.3);
        m.put("max", 3.0);
        m.put("default", 1.0);
        return m;
    }

    /** JSON-артефакт или null, если файла нет. Битый файл — понятная ошибка, а не стектрейс. */
    private Object read(String file) {
        if (file == null) return null;
        Path p = Path.of(file);
        if (!Files.exists(p)) return null;
        try {
            return json.readValue(Files.readAllBytes(p), Map.class);
        } catch (IOException | RuntimeException e) {
            return Map.of("error", "Не удалось прочитать " + p.getFileName() + ": " + e.getMessage());
        }
    }
}
