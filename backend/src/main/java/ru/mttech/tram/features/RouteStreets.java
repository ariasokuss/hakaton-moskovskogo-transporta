package ru.mttech.tram.features;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import ru.mttech.tram.config.AppProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Геопривязка постов о пробках к маршрутам: место затруднения («Волоколамском шоссе по направлению в центр»)
 * относится к маршруту, если называет улицу вдоль его трассы. Улицы — OpenStreetMap, в пределах 35 м от путей
 * (data/geo/route_streets.json, tools/build_route_streets.py). Алгоритм и проверка качества —
 * tools/route_traffic_match.py: основы значимых слов с начала слова, совпадение типа улицы,
 * без кольцевых магистралей (трамваи их только пересекают) и без слишком общих названий.
 */
@Service
public class RouteStreets {

    private static final Logger log = LoggerFactory.getLogger(RouteStreets.class);

    private static final Set<String> TYPES = Set.of("улица", "проспект", "шоссе", "переулок", "проезд", "бульвар", "площадь",
            "набережная", "тупик", "аллея", "дублёр", "дублер", "тоннель", "мост", "путепровод", "эстакада", "вал", "линия",
            "просек", "кольцо");
    private static final Map<String, String> TYPE_STEMS = Map.ofEntries(Map.entry("улица", "улиц"), Map.entry("проспект", "проспект"),
            Map.entry("шоссе", "шоссе"), Map.entry("переулок", "переул"), Map.entry("проезд", "проезд"), Map.entry("бульвар", "бульвар"),
            Map.entry("площадь", "площад"), Map.entry("набережная", "набережн"), Map.entry("тупик", "тупик"), Map.entry("аллея", "алле"),
            Map.entry("тоннель", "тоннел"), Map.entry("мост", "мост"), Map.entry("путепровод", "путепровод"),
            Map.entry("эстакада", "эстакад"), Map.entry("вал", "вал"));
    private static final List<String> RINGS = List.of("кольцевая", "транспортное кольцо", "садовое кольцо", "бульварное кольцо");
    private static final Set<String> GENERIC = Set.of("внутренний", "внешний", "большой", "большая", "малый", "малая", "новый", "новая",
            "старый", "старая", "верхний", "верхняя", "нижний", "нижняя", "средний", "средняя", "северный", "южный", "западный",
            "восточный", "первый", "второй", "лесной", "лесная", "полевой", "полевая", "парковая", "садовая", "новослободская", "поперечный");

    /** Затруднение на самой кольцевой магистрали: улица в тексте — лишь ориентир развязки, трамвая там нет. */
    private static final Pattern ON_RING = Pattern.compile("(?<![а-я])(мкад|ттк|садов[а-я]* кольц|трет[а-я]* транспортн[а-я]* кольц)");

    record Street(String name, List<Pattern> words, Pattern kind) {}

    private final AppProperties props;
    private final ObjectMapper json;
    private volatile Map<Integer, List<Street>> streets = Map.of();

    public RouteStreets(AppProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void load() {
        Path p = Path.of(props.geometryFile()).resolveSibling("route_streets.json");
        if (!Files.exists(p)) {
            log.warn("Нет {}: пробки к маршрутам не привязываются", p);
            return;
        }
        Map<Integer, List<Street>> out = new TreeMap<>();
        JsonNode routes = json.readTree(p.toFile()).get("routes");
        for (var e : routes.properties()) {
            List<Street> list = new ArrayList<>();
            for (JsonNode n : e.getValue()) {
                Street s = street(n.asString());
                if (s != null) list.add(s);
            }
            out.put(Integer.parseInt(e.getKey()), List.copyOf(list));
        }
        streets = Map.copyOf(out);
        log.info("Улицы вдоль трасс загружены: {} маршрутов", out.size());
    }

    /** Маршруты, на трассе которых названо место затруднения: маршрут → улицы вдоль трассы, давшие совпадение. */
    public Map<Integer, List<String>> match(String spot) {
        String t = norm(spot);
        Map<Integer, List<String>> out = new TreeMap<>();
        if (ON_RING.matcher(t).find()) return out;
        streets.forEach((route, list) -> {
            Set<String> hit = new TreeSet<>();
            for (Street s : list) {
                if (s.words().stream().allMatch(w -> w.matcher(t).find()) && (s.kind() == null || s.kind().matcher(t).find()))
                    hit.add(s.name());
            }
            if (!hit.isEmpty()) out.put(route, List.copyOf(hit));
        });
        return out;
    }

    static Street street(String name) {
        String n = norm(name.replaceAll("\\(.*?\\)", ""));
        if (RINGS.stream().anyMatch(n::contains)) return null;
        String[] tokens = n.split("[\\s\\-–—,.«»]+");
        Pattern kind = null;
        List<String> words = new ArrayList<>();
        for (String w : tokens) {
            if (w.isEmpty()) continue;
            if (kind == null && TYPE_STEMS.containsKey(w)) kind = wordStart(TYPE_STEMS.get(w));
            if (!TYPES.contains(w) && !Character.isDigit(w.charAt(0)) && w.length() >= 4) words.add(w);
        }
        if (words.isEmpty() || GENERIC.containsAll(words) || (words.size() == 1 && words.get(0).length() < 7)) return null;
        return new Street(name, words.stream().map(w -> wordStart(w.substring(0, Math.max(4, w.length() - 2)))).toList(), kind);
    }

    private static Pattern wordStart(String stem) {
        return Pattern.compile("(?<![а-я])" + Pattern.quote(stem));
    }

    private static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replace('ё', 'е');
    }
}
