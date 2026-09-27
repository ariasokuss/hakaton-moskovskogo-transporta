package ru.mttech.tram.export;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfTemplate;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import ru.mttech.tram.forecast.ForecastQueryService.Attention;
import ru.mttech.tram.forecast.ForecastQueryService.Point;
import ru.mttech.tram.forecast.ForecastQueryService.RouteSeries;
import ru.mttech.tram.forecast.ForecastQueryService.Scenario;
import ru.mttech.tram.forecast.Model.Regime;
import ru.mttech.tram.ingest.ExternalDataService.Day;
import ru.mttech.tram.ingest.ExternalDataService.Event;
import ru.mttech.tram.ingest.ExternalDataService.TrafficHour;

/**
 * PDF-отчёт диспетчера: то же, что на экране (горизонт, дата, маршрут, остановка, поправки), на двух страницах A4 —
 * ключевые цифры, график прогноза против обычного уровня, рекомендация по выпуску, прогноз по маршрутам,
 * зоны внимания с причинами, внешние факторы суток, применённые поправки и подробная таблица.
 * Графики рисуются векторно (PdfTemplate) — чёткие при печати. Шрифт PT Sans (OFL) встроен в файл.
 */
final class PdfReport {

    /** Всё, что нужно отчёту; собирается контроллером из тех же сервисов, что и экран. */
    record Input(String horizon, LocalDate from, LocalDate to, LocalDate dashDate, String scopeTitle, String scopeColor,
                 List<Point> points, List<RouteSeries> routeDay, List<Attention> attention, List<Regime> regimes,
                 Day day, List<Event> events, List<TrafficHour> traffic, Scenario scenario, String modelVersion,
                 Map<Integer, String> routeNames, List<RouteSeries> periodRoutes, Map<String, Object> periodCtx) {
        /** Месяц и год: маршруты, зоны внимания и внешние факторы — за весь период, а не за первые сутки. */
        boolean isPeriod() { return !"day".equals(horizon); }
    }

    private static final Color INK = new Color(0x1c2430), MUTED = new Color(0x6b7686), LINE = new Color(0xdde2ea),
            NAVY = new Color(0x0B2545), YELLOW = new Color(0xFFB703), SOFT = new Color(0xf3f6fb), UP = new Color(0xc2410c),
            DOWN = new Color(0x1d4ed8), SOFT_UP = new Color(0xfff4ec), SOFT_DOWN = new Color(0xeef3ff), SOFT_WARN = new Color(0xfff8eb);
    private static final String[] MONTHS_GEN = {"января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа",
            "сентября", "октября", "ноября", "декабря"};
    private static final String[] MONTHS = {"январь", "февраль", "март", "апрель", "май", "июнь", "июль", "август",
            "сентябрь", "октябрь", "ноябрь", "декабрь"};
    private static final String[] MONTHS_SHORT = {"янв", "фев", "мар", "апр", "май", "июн", "июл", "авг", "сен", "окт", "ноя", "дек"};
    private static final String[] DOW = {"", "понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье"};

    private static final BaseFont REGULAR = font("fonts/PT_Sans-Web-Regular.ttf"), BOLD = font("fonts/PT_Sans-Web-Bold.ttf");

    private PdfReport() {}

    static byte[] render(Input in) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 36, 36, 40, 50);
        PdfWriter w = PdfWriter.getInstance(doc, out);
        w.setPageEvent(new Footer(in.modelVersion()));
        doc.addTitle("Пантограф — отчёт диспетчера " + period(in));
        doc.addCreator("Пантограф · ИИ-прогноз загрузки трамвайных маршрутов");
        doc.open();

        header(doc, in);
        kpis(doc, in);
        section(doc, chartTitle(in));
        doc.add(chart(w, in.points(), in.horizon(), in.scopeColor(), doc.getPageSize().getWidth() - 72, 175));
        legend(doc);
        advice(doc, in);
        if (!in.routeDay().isEmpty()) {
            section(doc, "Прогноз по маршрутам · " + when(in) + " · полоса — прогноз, риска — обычный уровень");
            doc.add(routesChart(w, in.routeDay(), doc.getPageSize().getWidth() - 72));
        }

        doc.newPage();
        attention(doc, in);
        if (!in.routeDay().isEmpty()) {
            section(doc, "Маршруты · " + when(in));
            routesTable(doc, in);
        }
        external(doc, in);
        scenario(doc, in);
        detailTable(doc, in);
        doc.close();
        return out.toByteArray();
    }

    // ---------- шапка и сводка ----------

    private static void header(Document doc, Input in) {
        PdfPTable t = new PdfPTable(new float[]{3, 2});
        t.setWidthPercentage(100);
        PdfPCell l = cell(new Phrase(), NAVY, 14);
        Paragraph brand = new Paragraph();
        brand.add(new Chunk("ПАНТОГРАФ", new Font(BOLD, 20, Font.NORMAL, Color.WHITE)));
        brand.add(new Chunk("   отчёт диспетчера", new Font(REGULAR, 12, Font.NORMAL, new Color(0xc9d3e0))));
        l.addElement(brand);
        l.addElement(new Paragraph("Из данных — энергия, из энергии — прогноз", new Font(REGULAR, 9.5f, Font.NORMAL, YELLOW)));
        PdfPCell r = cell(new Phrase(), NAVY, 14);
        Paragraph p = new Paragraph(period(in), new Font(BOLD, 14, Font.NORMAL, Color.WHITE));
        p.setAlignment(Element.ALIGN_RIGHT);
        r.addElement(p);
        Paragraph s = new Paragraph(in.scopeTitle(), new Font(REGULAR, 10.5f, Font.NORMAL, new Color(0xc9d3e0)));
        s.setAlignment(Element.ALIGN_RIGHT);
        r.addElement(s);
        t.addCell(l);
        t.addCell(r);
        doc.add(t);
        doc.add(new Paragraph("Сформирован " + ZonedDateTime.now(ZoneId.of("Europe/Moscow")).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
                + " МСК · прогноз модели " + in.modelVersion() + (in.scenario().total() != 1 ? " · с поправками диспетчера" : ""),
                new Font(REGULAR, 8.5f, Font.NORMAL, MUTED)));
    }

    private static void kpis(Document doc, Input in) {
        double f = sum(in.points(), 0), b = sum(in.points(), 1), l = sum(in.points(), 2);
        int peak = peakIndex(in.points());
        PdfPTable t = new PdfPTable(4);
        t.setWidthPercentage(100);
        t.setSpacingBefore(10);
        t.addCell(kpi("Прогноз посадок", fmt(f), unit(in.horizon())));
        t.addCell(kpi("Обычный уровень", fmt(b), "медиана таких же дней"));
        double dev = b > 0 ? (f - b) / b * 100 : 0;
        t.addCell(kpi("Отклонение", (dev >= 0 ? "+" : "−") + String.format(Locale.ROOT, "%.1f", Math.abs(dev)).replace('.', ',') + "%",
                Math.abs(dev) < 10 ? "в пределах ±10%" : dev > 0 ? "выше обычного" : "ниже обычного", dev >= 10 ? UP : dev <= -10 ? DOWN : INK));
        t.addCell(kpi("Пик", peak < 0 ? "—" : peakLabel(in.points().get(peak).t(), in.horizon()),
                peak < 0 ? "" : fmt(in.points().get(peak).forecast()) + " посадок" + (l > f * 1.001 ? " · с пересадками " + fmt(l) : "")));
        doc.add(t);
    }

    private static PdfPCell kpi(String k, String v, String sub) { return kpi(k, v, sub, INK); }

    private static PdfPCell kpi(String k, String v, String sub, Color vc) {
        PdfPCell c = cell(new Phrase(), SOFT, 9);
        c.setBorderColor(Color.WHITE);
        c.setBorderWidth(3);
        c.addElement(new Paragraph(k.toUpperCase(Locale.ROOT), new Font(BOLD, 7.5f, Font.NORMAL, MUTED)));
        c.addElement(new Paragraph(v, new Font(BOLD, 17, Font.NORMAL, vc)));
        c.addElement(new Paragraph(sub, new Font(REGULAR, 8, Font.NORMAL, MUTED)));
        return c;
    }

    private static void advice(Document doc, Input in) {
        if (!"day".equals(in.horizon()) || in.points().isEmpty()) return;
        int i = peakIndex(in.points());
        Point p = in.points().get(i);
        if (p.baseline() <= 0) return;
        double pct = (p.forecast() - p.baseline()) / p.baseline() * 100;
        String text = "Пик в " + i + ":00 — " + fmt(p.forecast()) + " посадок в час (с пересадками " + fmt(p.load()) + "), обычно "
                + fmt(p.baseline()) + ". " + (pct >= 10 ? "Чтобы наполнение не выросло, провозную способность в этот час нужно поднять на "
                + Math.round(pct) + "%." : pct <= -10 ? "Спрос ниже обычного на " + Math.round(-pct) + "% — выпуск можно сократить или перераспределить."
                : "В пределах ±10% — выпуск по обычному расписанию.");
        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);
        t.setSpacingBefore(8);
        PdfPCell c = cell(new Phrase(text, new Font(REGULAR, 10, Font.NORMAL, INK)), pct >= 10 ? SOFT_UP : pct <= -10 ? SOFT_DOWN : SOFT, 9);
        c.setBorderWidthLeft(4);
        c.setBorderColorLeft(pct >= 10 ? UP : pct <= -10 ? DOWN : MUTED);
        c.setBorder(Rectangle.LEFT);
        t.addCell(c);
        doc.add(new Paragraph("Решение по выпуску", new Font(BOLD, 9, Font.NORMAL, MUTED)));
        doc.add(t);
    }

    // ---------- графики ----------

    /** Столбцы — прогноз, пунктир — обычный уровень, точки — факт (если есть), подписи осей и пика. */
    private static Image chart(PdfWriter w, List<Point> pts, String horizon, String hex, float width, float height) {
        PdfTemplate g = w.getDirectContent().createTemplate(width, height);
        float padL = 44, padB = 18, padT = 14, n = Math.max(1, pts.size());
        double max = 1;
        for (Point p : pts) max = Math.max(max, Math.max(p.forecast(), Math.max(p.baseline(), p.actual() == null ? 0 : p.actual())));
        double top = nice(max);
        float bw = (width - padL) / n;
        // сетка и шкала
        g.setLineWidth(0.4f);
        for (int k = 0; k <= 4; k++) {
            float y = padB + (height - padB - padT) * k / 4f;
            g.setColorStroke(LINE);
            g.moveTo(padL, y);
            g.lineTo(width, y);
            g.stroke();
            text(g, fmt(top * k / 4), padL - 4, y - 3, 7, MUTED, Element.ALIGN_RIGHT);
        }
        Color c = color(hex);
        int peak = peakIndex(pts);
        for (int i = 0; i < pts.size(); i++) {
            Point p = pts.get(i);
            float h = (float) (p.forecast() / top * (height - padB - padT));
            float x = padL + i * bw + Math.min(1.5f, bw * .12f);
            g.setColorFill(i == peak ? c : blend(c, Color.WHITE, 0.3f));
            g.rectangle(x, padB, Math.max(0.8f, bw - Math.min(3, bw * .24f)), h);
            g.fill();
            int step = "day".equals(horizon) ? 3 : "month".equals(horizon) ? 5 : 1;
            if (i % step == 0) text(g, label(p.t(), horizon), padL + i * bw + bw / 2, 5, 7, MUTED, Element.ALIGN_CENTER);
        }
        // обычный уровень
        g.setColorStroke(INK);
        g.setLineWidth(1.1f);
        g.setLineDash(3.5f, 2.5f, 0);
        for (int i = 0; i < pts.size(); i++) {
            float x = padL + i * bw + bw / 2, y = padB + (float) (pts.get(i).baseline() / top * (height - padB - padT));
            if (i == 0) g.moveTo(x, y); else g.lineTo(x, y);
        }
        g.stroke();
        g.setLineDash(0);
        // факт
        g.setColorFill(INK);
        for (int i = 0; i < pts.size(); i++) {
            Double a = pts.get(i).actual();
            if (a == null) continue;
            g.circle(padL + i * bw + bw / 2, padB + (float) (a / top * (height - padB - padT)), Math.min(2.2f, bw / 3));
            g.fill();
        }
        if (peak >= 0) {
            Point p = pts.get(peak);
            text(g, fmt(p.forecast()), padL + peak * bw + bw / 2, padB + (float) (p.forecast() / top * (height - padB - padT)) + 3, 8, INK,
                    Element.ALIGN_CENTER, BOLD);
        }
        return image(g);
    }

    /** Горизонтальные полосы по маршрутам: прогноз цветом маршрута, риска — обычный уровень, справа отклонение. */
    private static Image routesChart(PdfWriter w, List<RouteSeries> rs, float width) {
        float row = 17, height = rs.size() * row + 6, padL = 34, padR = 64;
        PdfTemplate g = w.getDirectContent().createTemplate(width, height);
        double max = 1;
        for (RouteSeries r : rs) max = Math.max(max, Math.max(r.forecastTotal(), r.baselineTotal()));
        float span = width - padL - padR;
        for (int i = 0; i < rs.size(); i++) {
            RouteSeries r = rs.get(i);
            float y = height - (i + 1) * row;
            Color c = color(r.color());
            g.setColorFill(c);
            g.roundRectangle(0, y + 2, 26, row - 5, 3);
            g.fill();
            text(g, r.shortName(), 13, y + 6, 8.5f, Color.WHITE, Element.ALIGN_CENTER, BOLD);
            float fw = (float) (r.forecastTotal() / max * span);
            g.setColorFill(blend(c, Color.WHITE, 0.15f));
            g.rectangle(padL, y + 4, fw, row - 8);
            g.fill();
            float bx = padL + (float) (r.baselineTotal() / max * span);
            g.setColorStroke(INK);
            g.setLineWidth(1.4f);
            g.moveTo(bx, y + 2);
            g.lineTo(bx, y + row - 3);
            g.stroke();
            text(g, fmt(r.forecastTotal()), padL + fw + 4, y + 6, 7.5f, INK, Element.ALIGN_LEFT);
            Double d = r.deviationPct();
            if (d != null) text(g, (d >= 0 ? "+" : "−") + Math.round(Math.abs(d)) + "%", width, y + 6, 8.5f,
                    d >= 10 ? UP : d <= -10 ? DOWN : MUTED, Element.ALIGN_RIGHT, BOLD);
        }
        return image(g);
    }

    private static void legend(Document doc) {
        doc.add(new Paragraph("■ столбцы — прогноз модели с поправками   ┅ пунктир — обычный уровень (медиана 8 таких же дней до 31.10)   ● точки — факт, если есть",
                new Font(REGULAR, 7.5f, Font.NORMAL, MUTED)));
    }

    // ---------- таблицы ----------

    private static void routesTable(Document doc, Input in) {
        PdfPTable t = table(new float[]{0.8f, 3.6f, 1.3f, 1.3f, 1, 1.3f}, "Маршрут", "Направление", "Прогноз, " + unit(in.horizon()).replace("за ", ""), "Обычно", "Δ", "Нагрузка с пересадками");
        for (RouteSeries r : in.routeDay()) {
            PdfPCell b = cell(new Phrase(r.shortName(), new Font(BOLD, 9.5f, Font.NORMAL, Color.WHITE)), color(r.color()), 4);
            b.setHorizontalAlignment(Element.ALIGN_CENTER);
            t.addCell(b);
            t.addCell(body(in.routeNames().getOrDefault(r.routeId(), "Маршрут " + r.shortName())));
            t.addCell(num(fmt(r.forecastTotal())));
            t.addCell(num(fmt(r.baselineTotal())));
            Double d = r.deviationPct();
            t.addCell(num(d == null ? "—" : (d >= 0 ? "+" : "−") + Math.round(Math.abs(d)) + "%", d == null ? INK : d >= 10 ? UP : d <= -10 ? DOWN : INK));
            t.addCell(num(fmt(r.loadTotal())));
        }
        doc.add(t);
    }

    private static String when(Input in) { return in.isPeriod() ? period(in) : human(in.dashDate()); }

    private static void periodAttention(Document doc, Input in) {
        section(doc, "Требует внимания · " + period(in) + " · отклонение " + unit(in.horizon()) + " больше ±10% от обычного уровня");
        List<RouteSeries> hit = in.periodRoutes().stream()
                .filter(r -> r.deviationPct() != null && Math.abs(r.deviationPct()) > 10)
                .sorted((a, b) -> Double.compare(Math.abs(b.deviationPct()), Math.abs(a.deviationPct()))).toList();
        if (hit.isEmpty()) {
            doc.add(new Paragraph("Все маршруты в пределах ±10% от обычного уровня " + unit(in.horizon()) + ".", new Font(REGULAR, 10, Font.NORMAL, MUTED)));
            return;
        }
        PdfPTable t = table(new float[]{0.8f, 1.6f, 1.3f, 1.3f, 1.4f, 3.6f}, "Маршрут", "Что", "Прогноз", "Обычно", "Пик", "Что проверить");
        for (RouteSeries r : hit) {
            PdfPCell b = cell(new Phrase(r.shortName(), new Font(BOLD, 9.5f, Font.NORMAL, Color.WHITE)), color(r.color()), 4);
            b.setHorizontalAlignment(Element.ALIGN_CENTER);
            t.addCell(b);
            boolean up = r.deviationPct() > 0;
            t.addCell(body((up ? "Превышение +" : "Провал −") + Math.round(Math.abs(r.deviationPct())) + "%", up ? UP : DOWN, BOLD));
            t.addCell(num(fmt(r.forecastTotal())));
            t.addCell(num(fmt(r.baselineTotal())));
            Point pk = r.points().stream().max((x, y) -> Double.compare(x.forecast(), y.forecast())).orElse(null);
            t.addCell(num(pk == null ? "—" : peakLabel(pk.t(), in.horizon())));
            t.addCell(body(up ? "Устойчивый рост — пересмотрите плановый выпуск на период" : "Устойчивое снижение — проверьте режимы и работы на путях"));
        }
        doc.add(t);
    }

    private static void attention(Document doc, Input in) {
        if (in.isPeriod()) { periodAttention(doc, in); return; }
        section(doc, "Требует внимания · " + human(in.dashDate()) + " · отклонение больше ±10% от обычного уровня");
        if (in.attention().isEmpty()) {
            doc.add(new Paragraph("Все маршруты в пределах ±10% от обычного уровня.", new Font(REGULAR, 10, Font.NORMAL, MUTED)));
            return;
        }
        PdfPTable t = table(new float[]{0.8f, 1.6f, 1.2f, 1.2f, 1, 4.2f}, "Маршрут", "Что", "Прогноз", "Обычно", "Пик", "Причина");
        for (Attention a : in.attention()) {
            PdfPCell b = cell(new Phrase(a.shortName(), new Font(BOLD, 9.5f, Font.NORMAL, Color.WHITE)), color(a.color()), 4);
            b.setHorizontalAlignment(Element.ALIGN_CENTER);
            t.addCell(b);
            boolean up = "above".equals(a.direction());
            t.addCell(body((up ? "Превышение +" : "Провал −") + Math.round(Math.abs(a.deviationPct())) + "%", up ? UP : DOWN, BOLD));
            t.addCell(num(fmt(a.forecast())));
            t.addCell(num(fmt(a.baseline())));
            t.addCell(num(a.peakHour() + ":00"));
            t.addCell(body(a.reason() == null ? (up ? "Риск переполнения — проверьте выпуск в пик" : "Проверьте выпуск и сбои на маршруте") : a.reason()));
        }
        doc.add(t);
    }

    @SuppressWarnings("unchecked")
    private static void periodExternal(Document doc, Input in) {
        Map<String, Object> p = in.periodCtx();
        section(doc, "Внешние факторы · " + period(in));
        PdfPTable t = new PdfPTable(new float[]{1.3f, 5});
        t.setWidthPercentage(100);
        int days = ((Number) p.get("days")).intValue();
        Map<String, Object> c = (Map<String, Object>) p.get("calendar");
        int cd = ((Number) c.get("coveredDays")).intValue();
        t.addCell(key("Календарь"));
        t.addCell(body(cd == 0 ? "календаря на эти даты ещё нет" : c.get("workdays") + " рабочих · " + c.get("daysOff") + " выходных"
                + (((Number) c.get("schoolHolidayDays")).intValue() > 0 ? " · каникулы " + c.get("schoolHolidayDays") + " дн." : "")
                + (cd < days ? " (календарь на " + cd + " из " + days + " дн.)" : "") + "  (xmlcalendar)"));
        List<Map<String, Object>> hol = (List<Map<String, Object>>) c.get("holidays");
        List<LocalDate> ww = (List<LocalDate>) c.get("workingWeekends");
        if (!hol.isEmpty() || !ww.isEmpty()) {
            List<String> items = new ArrayList<>();
            hol.forEach(h -> items.add(dm((LocalDate) h.get("date")) + " — " + h.get("name")));
            ww.forEach(d -> items.add(dm(d) + " — рабочая суббота"));
            t.addCell(key("Праздники, переносы"));
            t.addCell(body(String.join(" · ", items)));
        }
        Map<String, Object> w = (Map<String, Object>) p.get("weather");
        int wd = ((Number) w.get("coveredDays")).intValue();
        t.addCell(key("Погода, прогноз"));
        t.addCell(body(wd == 0 ? "прогноза погоды на эти даты ещё нет" : w.get("tempMin") + "…" + w.get("tempMax") + " °C · осадки " + w.get("precipitationMm")
                + " мм, дней от 1 мм — " + w.get("rainyDays") + ", ливней — " + w.get("heavyDays") + ", со снегом — " + w.get("snowDays")
                + ", мороз ниже −10 °C — " + w.get("frostDays") + (wd < days ? " (есть на " + wd + " из " + days + " дн.)" : "") + "  (Open-Meteo)"));
        Map<String, Object> tr = (Map<String, Object>) p.get("traffic");
        int td = ((Number) tr.get("coveredDays")).intValue();
        t.addCell(key("Пробки, ЦОДД"));
        t.addCell(body(td == 0 ? "постов ЦОДД за эти даты нет" : tr.get("jamDays") + " дн. с баллом 7+ из " + td + " с постами"
                + (tr.get("maxScoreDay") != null ? "; максимум " + tr.get("maxScore") + " б. — " + dm((LocalDate) tr.get("maxScoreDay")) : "")
                + ". Балл по городу, в прогноз не входит."));
        Map<String, Long> counts = (Map<String, Long>) p.get("eventCounts");
        long incidents = counts.getOrDefault("tram_incident", 0L);
        int n = 0;
        for (Event e : (List<Event>) p.get("events")) {
            if ("tram_incident".equals(e.category()) || n++ >= 8) continue;
            t.addCell(key(category(e.category())));
            t.addCell(body(dm(e.from()) + (e.to() != null && !e.to().equals(e.from()) ? " – " + dm(e.to()) : "") + " · "
                    + (e.routes().isEmpty() ? "" : "маршруты " + String.join(", ", e.routes().stream().map(String::valueOf).toList()) + " — ")
                    + (e.title() == null ? "" : abbreviate(e.title(), 160)) + "  " + e.url()));
        }
        if (incidents > 0) {
            t.addCell(key("Сбои движения"));
            t.addCell(body(incidents + " за период — по суткам в отчёте на день"));
        }
        doc.add(t);
    }

    private static String dm(LocalDate d) { return d.getDayOfMonth() + " " + MONTHS_GEN[d.getMonthValue() - 1]; }

    private static void external(Document doc, Input in) {
        if (in.isPeriod() && in.periodCtx() != null) { periodExternal(doc, in); return; }
        section(doc, "Внешние факторы · " + human(in.dashDate()));
        PdfPTable t = new PdfPTable(new float[]{1.3f, 5});
        t.setWidthPercentage(100);
        Day d = in.day();
        if (d != null) {
            t.addCell(key("Календарь"));
            t.addCell(body(dayType(d.dayType()) + (d.holidayName() != null ? " · " + d.holidayName() : "") + (d.schoolHoliday() ? " · школьные каникулы" : "")
                    + "  (xmlcalendar)"));
            if (d.tempMin() != null) {
                t.addCell(key("Погода, прогноз"));
                t.addCell(body(signed(d.tempMin()) + "…" + signed(d.tempMax()) + " °C · " + d.weatherKind()
                        + (d.precipitationMm() != null && d.precipitationMm() > 0 ? ", " + d.precipitationMm() + " мм" : "") + "  (Open-Meteo)"));
            }
        }
        t.addCell(key("Пробки, ЦОДД"));
        if (in.traffic().isEmpty()) t.addCell(body("баллов за эти сутки нет"));
        else {
            StringBuilder sb = new StringBuilder();
            int max = 0;
            for (TrafficHour h : in.traffic()) {
                if (!sb.isEmpty()) sb.append(" · ");
                sb.append(h.hour()).append(":00 — ").append(h.score()).append(" б.");
                max = Math.max(max, h.score());
            }
            t.addCell(body("максимум " + max + " баллов (" + (max <= 3 ? "свободно" : max <= 5 ? "местами затруднено" : max <= 7 ? "плотно" : "пробки")
                    + "): " + sb + ". Балл по городу, в прогноз не входит — контекст для решения."));
        }
        for (Regime r : in.regimes()) {
            t.addCell(key("Режим маршрута " + r.routeId()));
            t.addCell(body(r.note() + (r.sourceUrl() != null ? "  " + r.sourceUrl() : "")));
        }
        int n = 0;
        for (Event e : in.events()) {
            if (n++ == 6) break;
            t.addCell(key(category(e.category())));
            t.addCell(body((e.routes().isEmpty() ? "" : "маршруты " + e.routes() .toString().replaceAll("[\\[\\]]", "") + " — ")
                    + (e.title() == null ? "" : abbreviate(e.title(), 180)) + "  " + e.url()));
        }
        doc.add(t);
    }

    private static void scenario(Document doc, Input in) {
        Scenario s = in.scenario();
        section(doc, "Поправки диспетчера");
        if (s.total() == 1) {
            doc.add(new Paragraph("Не заданы — прогноз модели без изменений.", new Font(REGULAR, 10, Font.NORMAL, MUTED)));
            return;
        }
        PdfPTable t = new PdfPTable(new float[]{1.3f, 1, 4});
        t.setWidthPercentage(100);
        addK(t, "Погода", s.kWeather(), "осадки, мороз");
        addK(t, "Событие", s.kEvent(), "сбой, мероприятие, перекрытие");
        addK(t, "День / сезон", s.kSeason(), "праздник, выходной, перенос");
        addK(t, "Пробки", s.kTraffic(), "балл ЦОДД / Яндекса");
        addK(t, "Своя поправка", s.kManual(), "решение диспетчера");
        t.addCell(key("Итого"));
        t.addCell(num(pct(s.total()), s.total() > 1 ? UP : DOWN));
        t.addCell(body("множитель к прогнозу модели; модель не меняется"));
        doc.add(t);
    }

    private static void addK(PdfPTable t, String name, double k, String what) {
        if (k == 1) return;
        t.addCell(key(name));
        t.addCell(num(pct(k)));
        t.addCell(body(what));
    }

    private static void detailTable(Document doc, Input in) {
        String unit = "day".equals(in.horizon()) ? "Час" : "month".equals(in.horizon()) ? "День" : "Месяц";
        section(doc, "Подробно: " + in.scopeTitle() + " · " + period(in));
        PdfPTable t = table(new float[]{1.4f, 1.3f, 1.4f, 1.3f, 1}, unit, "Прогноз", "С пересадками", "Обычно", "Δ");
        t.setHeaderRows(1);
        for (Point p : in.points()) {
            t.addCell(body(detailLabel(p.t(), in.horizon())));
            t.addCell(num(fmt(p.forecast())));
            t.addCell(num(fmt(p.load())));
            t.addCell(num(fmt(p.baseline())));
            Double d = p.baseline() > 0 ? (p.forecast() - p.baseline()) / p.baseline() * 100 : null;
            t.addCell(num(d == null ? "—" : (d >= 0 ? "+" : "−") + Math.round(Math.abs(d)) + "%", d == null ? INK : d >= 10 ? UP : d <= -10 ? DOWN : INK));
        }
        doc.add(t);
        doc.add(new Paragraph("Прогноз — ансамбль LightGBM и сезонного профиля (лидерборд WAPE-score 0,892); день, месяц и год — сумма почасовых значений. "
                + "Остановка — доля прогноза маршрута (в валидациях остановки нет). Округление к целому — только в отчёте.",
                new Font(REGULAR, 7.5f, Font.NORMAL, MUTED)));
    }

    // ---------- утилиты оформления ----------

    private static void section(Document doc, String title) {
        Paragraph p = new Paragraph(title.toUpperCase(Locale.ROOT), new Font(BOLD, 9, Font.NORMAL, MUTED));
        p.setSpacingBefore(14);
        p.setSpacingAfter(5);
        doc.add(p);
    }

    private static PdfPTable table(float[] widths, String... head) {
        PdfPTable t = new PdfPTable(widths);
        t.setWidthPercentage(100);
        for (String h : head) {
            PdfPCell c = cell(new Phrase(h, new Font(BOLD, 8, Font.NORMAL, MUTED)), Color.WHITE, 4);
            c.setBorder(Rectangle.BOTTOM);
            c.setBorderColor(INK);
            c.setBorderWidth(0.8f);
            t.addCell(c);
        }
        return t;
    }

    private static PdfPCell cell(Phrase p, Color bg, float pad) {
        PdfPCell c = new PdfPCell(p);
        c.setBackgroundColor(bg);
        c.setPadding(pad);
        c.setBorder(Rectangle.NO_BORDER);
        c.setVerticalAlignment(Element.ALIGN_MIDDLE);
        return c;
    }

    private static PdfPCell body(String s) { return body(s, INK, REGULAR); }

    private static PdfPCell body(String s, Color c, BaseFont f) {
        PdfPCell cell = cell(new Phrase(s, new Font(f, 9, Font.NORMAL, c)), Color.WHITE, 4);
        cell.setBorder(Rectangle.BOTTOM);
        cell.setBorderColor(LINE);
        return cell;
    }

    private static PdfPCell key(String s) {
        PdfPCell c = body(s, MUTED, BOLD);
        c.setVerticalAlignment(Element.ALIGN_TOP);
        return c;
    }

    private static PdfPCell num(String s) { return num(s, INK); }

    private static PdfPCell num(String s, Color c) {
        PdfPCell cell = body(s, c, REGULAR);
        cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        return cell;
    }

    private static void text(PdfTemplate g, String s, float x, float y, float size, Color c, int align) { text(g, s, x, y, size, c, align, REGULAR); }

    private static void text(PdfTemplate g, String s, float x, float y, float size, Color c, int align, BaseFont f) {
        g.beginText();
        g.setFontAndSize(f, size);
        g.setColorFill(c);
        g.showTextAligned(align, s, x, y, 0);
        g.endText();
    }

    private static Image image(PdfTemplate g) {
        try {
            Image i = Image.getInstance(g);
            i.setSpacingBefore(2);
            return i;
        } catch (Exception e) {
            throw new IllegalStateException("График не построен: " + e.getMessage(), e);
        }
    }

    static final class Footer extends PdfPageEventHelper {
        private final String model;
        Footer(String model) { this.model = model; }

        @Override
        public void onEndPage(PdfWriter w, Document d) {
            PdfContentByte cb = w.getDirectContent();
            cb.setColorStroke(LINE);
            cb.setLineWidth(0.5f);
            cb.moveTo(36, 36);
            cb.lineTo(d.getPageSize().getWidth() - 36, 36);
            cb.stroke();
            cb.beginText();
            cb.setFontAndSize(REGULAR, 7.5f);
            cb.setColorFill(MUTED);
            cb.showTextAligned(Element.ALIGN_LEFT, "Пантограф · команда «майнкрафт абманка», Университет ИТМО · модель " + model, 36, 24, 0);
            cb.showTextAligned(Element.ALIGN_RIGHT, "стр. " + w.getPageNumber(), d.getPageSize().getWidth() - 36, 24, 0);
            cb.endText();
        }
    }

    private static BaseFont font(String path) {
        try (InputStream is = PdfReport.class.getClassLoader().getResourceAsStream(path)) {
            if (is == null) throw new IllegalStateException("нет шрифта " + path);
            return BaseFont.createFont(path, BaseFont.IDENTITY_H, BaseFont.EMBEDDED, true, is.readAllBytes(), null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------- форматирование ----------

    static String period(Input in) {
        return switch (in.horizon()) {
            case "day" -> human(in.from()) + ", " + DOW[in.from().getDayOfWeek().getValue()];
            case "month" -> MONTHS[in.from().getMonthValue() - 1] + " " + in.from().getYear();
            default -> MONTHS_SHORT[in.from().getMonthValue() - 1] + " " + in.from().getYear() + " — "
                    + MONTHS_SHORT[in.to().getMonthValue() - 1] + " " + in.to().getYear();
        };
    }

    private static String chartTitle(Input in) {
        return switch (in.horizon()) {
            case "day" -> "Прогноз по часам · " + in.scopeTitle();
            case "month" -> "Прогноз по дням · " + in.scopeTitle();
            default -> "Прогноз по месяцам (качественный сценарий) · " + in.scopeTitle();
        };
    }

    private static String unit(String horizon) {
        return switch (horizon) { case "day" -> "за сутки"; case "month" -> "за месяц"; default -> "за период"; };
    }

    static String human(LocalDate d) { return d.getDayOfMonth() + " " + MONTHS_GEN[d.getMonthValue() - 1] + " " + d.getYear(); }

    private static String label(String t, String horizon) {
        return switch (horizon) {
            case "day" -> String.valueOf(Integer.parseInt(t.substring(11, 13)));
            case "month" -> String.valueOf(Integer.parseInt(t.substring(8, 10)));
            default -> MONTHS_SHORT[Integer.parseInt(t.substring(5, 7)) - 1];
        };
    }

    private static String peakLabel(String t, String horizon) {
        return switch (horizon) {
            case "day" -> Integer.parseInt(t.substring(11, 13)) + ":00";
            case "month" -> Integer.parseInt(t.substring(8, 10)) + " " + MONTHS_GEN[Integer.parseInt(t.substring(5, 7)) - 1];
            default -> MONTHS[Integer.parseInt(t.substring(5, 7)) - 1] + " " + t.substring(0, 4);
        };
    }

    private static String detailLabel(String t, String horizon) {
        return switch (horizon) {
            case "day" -> t.substring(11, 13) + ":00–" + String.format("%02d", (Integer.parseInt(t.substring(11, 13)) + 1) % 24) + ":00";
            case "month" -> {
                LocalDate d = LocalDate.parse(t.substring(0, 10));
                yield d.getDayOfMonth() + " " + MONTHS_GEN[d.getMonthValue() - 1] + ", " + DOW[d.getDayOfWeek().getValue()].substring(0, 2);
            }
            default -> MONTHS[Integer.parseInt(t.substring(5, 7)) - 1] + " " + t.substring(0, 4);
        };
    }

    private static int peakIndex(List<Point> pts) {
        int best = -1;
        for (int i = 0; i < pts.size(); i++) if (best < 0 || pts.get(i).forecast() > pts.get(best).forecast()) best = i;
        return best;
    }

    private static double sum(List<Point> pts, int what) {
        double s = 0;
        for (Point p : pts) s += what == 0 ? p.forecast() : what == 1 ? p.baseline() : p.load();
        return s;
    }

    private static double nice(double max) {
        double pow = Math.pow(10, Math.floor(Math.log10(max)));
        for (double m : new double[]{1, 1.2, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10}) if (m * pow >= max) return m * pow;
        return 10 * pow;
    }

    static String fmt(double v) {
        return String.format(Locale.ROOT, "%,d", Math.round(v)).replace(',', ' ');   // в PT Sans нет узкого пробела
    }

    private static String pct(double k) {
        double p = (k - 1) * 100;
        return (p > 0 ? "+" : p < 0 ? "−" : "") + String.format(Locale.ROOT, Math.abs(p) < 10 ? "%.1f" : "%.0f", Math.abs(p)).replace('.', ',') + "%";
    }

    private static String signed(Double v) { return v == null ? "—" : (v > 0 ? "+" : "") + Math.round(v); }

    private static String dayType(String t) {
        if (t == null) return "—";
        return switch (t) {
            case "workday" -> "рабочий день"; case "saturday" -> "суббота"; case "sunday" -> "воскресенье";
            case "holiday" -> "праздник"; case "short_workday" -> "сокращённый рабочий день"; case "working_weekend" -> "рабочая суббота (перенос)";
            default -> t;
        };
    }

    private static String category(String c) {
        return switch (c) {
            case "tram_works" -> "Работы на путях"; case "tram_route_change" -> "Изменение маршрута"; case "closure" -> "Перекрытие";
            case "mass_event" -> "Мероприятие"; case "new_line" -> "Новая линия"; case "tram_incident" -> "Сбой на маршруте";
            default -> "Событие";
        };
    }

    private static String abbreviate(String s, int n) { return s.length() <= n ? s : s.substring(0, n - 1) + "…"; }

    private static Color color(String hex) {
        try { return new Color(Integer.parseInt(hex.replace("#", ""), 16)); } catch (RuntimeException e) { return new Color(0x3f7cac); }
    }

    private static Color blend(Color a, Color b, float t) {
        return new Color(Math.round(a.getRed() * (1 - t) + b.getRed() * t), Math.round(a.getGreen() * (1 - t) + b.getGreen() * t),
                Math.round(a.getBlue() * (1 - t) + b.getBlue() * t));
    }

    static List<Point> sumSeries(List<RouteSeries> series) {
        List<Point> out = new ArrayList<>();
        if (series.isEmpty()) return out;
        for (int i = 0; i < series.get(0).points().size(); i++) {
            double f = 0, l = 0, b = 0, a = 0;
            boolean hasA = false;
            for (RouteSeries s : series) {
                if (i >= s.points().size()) continue;
                Point p = s.points().get(i);
                f += p.forecast(); l += p.load(); b += p.baseline();
                if (p.actual() != null) { a += p.actual(); hasA = true; }
            }
            out.add(new Point(series.get(0).points().get(i).t(), f, l, b, hasA ? a : null, null));
        }
        return out;
    }
}
