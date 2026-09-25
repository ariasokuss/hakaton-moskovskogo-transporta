package ru.mttech.tram.config;

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
        List<Integer> loadCodes) {
}
