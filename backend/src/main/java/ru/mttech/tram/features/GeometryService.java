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
                String key = name + "|" + Math.round(lat * 2000) + "|" + Math.round(lon * 1000);
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
}
