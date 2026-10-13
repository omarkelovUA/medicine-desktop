package ua.medicine.i18n;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class UkrainianPluralTest {

    @ParameterizedTest
    @CsvSource({"1,день", "2,дні", "4,дні", "5,днів", "11,днів", "12,днів", "21,день", "22,дні", "25,днів", "111,днів", "0,днів"})
    void plural(long n, String expected) {
        assertThat(UkrainianPlural.choose(n, "день", "дні", "днів")).isEqualTo(expected);
    }
}
