package ru.mttech.tram.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ExternalCsvTest {

    @Test
    void тексПостаВКавычкахСТочкойСЗапятойИКавычками() {
        var v = ExternalDataService.split("1;2025-01-01;\"ЦОДД: работы; маршрут \"\"7\"\" укорочен\";https://t.me/x");
        assertThat(v).containsExactly("1", "2025-01-01", "ЦОДД: работы; маршрут \"7\" укорочен", "https://t.me/x");
    }

    @Test
    void пустыеПоляСохраняются() {
        assertThat(ExternalDataService.split("a;;c;")).containsExactly("a", "", "c", "");
    }
}
