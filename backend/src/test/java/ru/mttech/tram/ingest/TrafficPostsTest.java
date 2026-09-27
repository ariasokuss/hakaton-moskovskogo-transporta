package ru.mttech.tram.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TrafficPostsTest {

    private static final String POST = """
            1️⃣1️⃣2️⃣3️⃣ По данным ЦОДД, движение оценивается в 7 баллов. Для поездок лучше использовать городской транспорт

            Средняя скорость потока — 28 км/ч. Интенсивное движение сейчас на:

            🔹 внутренней стороне ТТК в районе Киевского путепровода. Объезд возможен через МКАД.
            🔹 Волгоградском проспекте в направлении области;
            🔹 рост числа поездок и автомобилей;

            Вечером на дорогах ожидается до 8 баллов.""";

    @Test
    void убираетОформлениеКаналаИДелаетСписок() {
        var p = TrafficPosts.parse(POST);
        assertThat(p.text()).startsWith("По данным ЦОДД").doesNotContain("1️⃣").doesNotContain("🔹").contains("• Волгоградском проспекте");
    }

    @Test
    void скоростьПрогнозИМестаЗатруднений() {
        var p = TrafficPosts.parse(POST);
        assertThat(p.speedKmh()).isEqualTo(28);
        assertThat(p.forecastScore()).isEqualTo(8);
        // «рост числа поездок» — причина, а не место: в список мест не попадает
        assertThat(p.spots()).containsExactly(
                "внутренней стороне ТТК в районе Киевского путепровода. Объезд возможен через МКАД",
                "Волгоградском проспекте в направлении области");
    }

    @Test
    void прогнозНаКонкретныйЧас() {
        assertThat(TrafficPosts.parse("По прогнозу ЦОДД, в 15:00 на дорогах ожидается 6 баллов.").forecastScore()).isEqualTo(6);
    }

    @Test
    void пустойПост() {
        assertThat(TrafficPosts.parse(null).spots()).isEmpty();
    }
}
