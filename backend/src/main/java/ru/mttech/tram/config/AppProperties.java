package ru.mttech.tram.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Пути к данным и параметры модели. Датасет организаторов в git не лежит,
 * монтируется в контейнер read-only (см. docker-compose.yml).
 */
@ConfigurationProperties("app")
public record AppProperties(
        String datasetDir,
        String forecastFile,
        String forecastModelVersion,
        String geometryFile,
        String loadFile,
        List<Integer> loadCodes,
        String coefficientsFile,
        String mlContractFile,
        String yearFile,
        List<Integer> submissionRoutes,
        External external) {

    /**
     * Внешние источники (схема external.*). dir — снимок источников со ссылками,
     * enabled — фоновое онлайн-обновление, timeout — жёсткий предел на внешний запрос:
     * сеть не в критическом пути, при сбое сервис работает на снимке.
     */
    public record External(boolean enabled, String dir, Duration timeout, String yandexTrafficUrl) {}

    public List<Integer> submissionRoutes() {
        return submissionRoutes == null ? List.of() : submissionRoutes;
    }

    public External external() {
        return external == null ? new External(false, null, Duration.ofSeconds(2), null) : external;
    }
}
