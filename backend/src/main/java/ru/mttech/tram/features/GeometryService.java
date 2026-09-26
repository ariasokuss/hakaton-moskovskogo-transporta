package ru.mttech.tram.features;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import ru.mttech.tram.config.AppProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Геопривязка: трассы и остановки всех 9 маршрутов.
 *
 * Источник — OpenStreetMap (ODbL), снимок в data/geo/routes_osm.json.
 * Справочник организаторов покрывает только 1, 7, 11, 12 из целевых, поэтому
 * геометрия берётся из одного внешнего источника для всех маршрутов — карта однородна.
 * Это внешние данные: в основную БД не пишутся, отдаются с указанием источника.
 */
@Service
public class GeometryService {

    private static final Logger log = LoggerFactory.getLogger(GeometryService.class);

    private final AppProperties props;
    private final ObjectMapper json;
    private volatile Map<String, Object> geoJson = Map.of("type", "FeatureCollection", "features", List.of());

    /**
     * Доля остановки в посадках маршрута. В валидациях остановки нет (place_id — депо), поэтому
     * остановочный прогноз — это распределение прогноза маршрута, а не наблюдение:
     * вес остановки = 1 + 0.5 × (число других трамвайных маршрутов на ней) — пересадочные узлы
     * собирают больше посадок; остановка на обоих направлениях считается дважды. Доли маршрута в сумме = 1.
     */
    public record StopShare(String name, double lat, double lon, int routesAtStop, double share) {}

    private volatile Map<Integer, List<StopShare>> stopShares = Map.of();

    public GeometryService(AppProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void load() {
        Path p = Path.of(props.geometryFile());
        if (!Files.exists(p)) {
            log.warn("Нет файла геометрии {}: карта будет без трасс", p);
            return;
        }
        JsonNode root = json.readTree(p.toFile());
        List<Object> features = new ArrayList<>();
        Map<String, Map<String, Object>> stops = new LinkedHashMap<>();   // остановка может обслуживать несколько маршрутов

        for (JsonNode r : root.get("routes")) {
            int routeId = r.get("route_id").asInt();
            List<Object> lines = json.convertValue(r.get("lines"), List.class);
            features.add(Map.of("type", "Feature",
                    "geometry", Map.of("type", "MultiLineString", "coordinates", lines),
                    "properties", Map.of("kind", "track", "routeId", routeId,
                            "direction", r.get("name").asString(), "osmRelation", r.get("osm_relation").asLong())));
            int seq = 0;
            for (JsonNode s : r.get("stops")) {
                seq++;
                String name = s.get("name").asString();
                double lat = s.get("lat").asDouble(), lon = s.get("lon").asDouble();
                // Остановки в пределах ~50 м с одним именем считаем одной точкой на карте.
                String key = stopKey(name, lat, lon);
                Map<String, Object> st = stops.computeIfAbsent(key, k -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", name);
                    m.put("lat", lat);
                    m.put("lon", lon);
                    m.put("routes", new ArrayList<Integer>());
                    return m;
                });
                @SuppressWarnings("unchecked") List<Integer> rs = (List<Integer>) st.get("routes");
                if (!rs.contains(routeId)) rs.add(routeId);
            }
        }
        Map<Integer, Map<String, Double>> weights = new LinkedHashMap<>();
        for (JsonNode r : root.get("routes")) {
            int routeId = r.get("route_id").asInt();
            for (JsonNode s : r.get("stops")) {
                String key = stopKey(s.get("name").asString(), s.get("lat").asDouble(), s.get("lon").asDouble());
                int k = ((List<?>) stops.get(key).get("routes")).size();
                weights.computeIfAbsent(routeId, x -> new LinkedHashMap<>()).merge(key, 1 + 0.5 * (k - 1), Double::sum);
            }
        }
        Map<Integer, List<StopShare>> shares = new LinkedHashMap<>();
        weights.forEach((routeId, w) -> {
            double total = w.values().stream().mapToDouble(Double::doubleValue).sum();
            List<StopShare> list = new ArrayList<>();
            w.forEach((key, v) -> {
                Map<String, Object> st = stops.get(key);
                list.add(new StopShare((String) st.get("name"), (Double) st.get("lat"), (Double) st.get("lon"),
                        ((List<?>) st.get("routes")).size(), v / total));
            });
            shares.put(routeId, List.copyOf(list));
        });
        stopShares = Map.copyOf(shares);

        for (Map<String, Object> st : stops.values()) {
            features.add(Map.of("type", "Feature",
                    "geometry", Map.of("type", "Point", "coordinates", List.of(st.get("lon"), st.get("lat"))),
                    "properties", Map.of("kind", "stop", "name", st.get("name"), "routes", st.get("routes"))));
        }
        geoJson = Map.of("type", "FeatureCollection", "features", features,
                "source", Map.of("name", root.get("source").asString(), "license", root.get("license").asString(),
                        "url", "https://www.openstreetmap.org", "fetchedAt", root.get("fetched_at").asString()));
        log.info("Геометрия загружена: {} направлений, {} остановок", root.get("routes").size(), stops.size());
    }

    public Map<String, Object> geoJson() {
        return geoJson;
    }

    /** Остановки маршрута с долями посадок (пусто, если геометрии нет). */
    public List<StopShare> stops(int routeId) {
        return stopShares.getOrDefault(routeId, List.of());
    }

    /** Остановки в пределах ~50 м с одним именем — одна точка. */
    static String stopKey(String name, double lat, double lon) {
        return name + "|" + Math.round(lat * 2000) + "|" + Math.round(lon * 1000);
    }
}
