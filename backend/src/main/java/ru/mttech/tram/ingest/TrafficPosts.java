package ru.mttech.tram.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Адаптация постов ЦОДД о пробках для диспетчера: текст без декоративных эмодзи, пункты списка
 * и отдельными полями — средняя скорость потока, прогноз балла на вечер, места затруднений.
 */
public final class TrafficPosts {
    private TrafficPosts() {}

    public record Parsed(String text, Integer speedKmh, Integer forecastScore, List<String> spots) {}

    /** Цифры-«клавиши» 1️⃣2️⃣… в начале постов — оформление канала, не содержание. */
    private static final Pattern KEYCAP = Pattern.compile("[0-9#*]\\uFE0F?\\u20E3");
    private static final Pattern BULLET = Pattern.compile("(?m)^\\s*(?:\\x{1F539}|\\x{1F538}|\\x{25AA}|\\x{2022}|\\x{25CF}|[-\\u2013\\u2014])+\\s*");
    private static final Pattern EMOJI = Pattern.compile("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{2B00}-\\x{2BFF}\\uFE0F\\u200D]");
    private static final Pattern SPEED = Pattern.compile("(?iu)скорост[^.\\n]{0,40}?(\\d{1,3})\\s*км/ч");
    // «Вечером ожидается до 8 баллов», «По прогнозу ЦОДД, в 15:00 на дорогах ожидается 6 баллов»
    private static final Pattern FORECAST = Pattern.compile("(?iu)(?:вечер|ожидает|прогноз)[^.\\n]{0,70}?(?:до\\s*)?(\\d{1,2})\\s*балл");
    private static final Pattern PLACE = Pattern.compile(
            "(?iu).*(улиц|шоссе|проспект|набережн|ТТК|МКАД|Садов|бульвар|мост|путепровод|проезд|площад|переул|эстакад|СВХ|МСД|кольц).*");

    public static Parsed parse(String raw) {
        if (raw == null || raw.isBlank()) return new Parsed(null, null, null, List.of());
        String t = KEYCAP.matcher(raw).replaceAll("");
        t = BULLET.matcher(t).replaceAll("• ");
        t = EMOJI.matcher(t).replaceAll("");
        t = t.replaceAll("[ \\t]+", " ").replaceAll(" ?\\n ?", "\n").replaceAll("\\n{3,}", "\n\n").strip();

        Integer speed = null, forecast = null;
        Matcher m = SPEED.matcher(t);
        if (m.find()) speed = Integer.parseInt(m.group(1));
        m = FORECAST.matcher(t);
        if (m.find()) forecast = Integer.parseInt(m.group(1));
        List<String> spots = new ArrayList<>();
        for (String line : t.split("\\n")) {
            if (!line.startsWith("• ")) continue;
            String s = line.substring(2).strip().replaceAll("[;.,]+$", "");
            if (PLACE.matcher(s).matches()) spots.add(s);
        }
        return new Parsed(t, speed, forecast, List.copyOf(spots));
    }
}
