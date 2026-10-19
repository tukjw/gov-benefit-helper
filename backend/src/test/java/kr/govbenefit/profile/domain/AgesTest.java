package kr.govbenefit.profile.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class AgesTest {

    @Test
    void 생일_당일에_한_살_오른다() {
        LocalDate birthDate = LocalDate.of(2000, 5, 17);

        assertThat(Ages.fullAge(birthDate, LocalDate.of(2026, 5, 16))).isEqualTo(25);
        assertThat(Ages.fullAge(birthDate, LocalDate.of(2026, 5, 17))).isEqualTo(26);
        assertThat(Ages.fullAge(birthDate, LocalDate.of(2026, 5, 18))).isEqualTo(26);
    }

    @Test
    void 이월_이십구일생은_평년에_삼월_일일부터_오른다() {
        LocalDate birthDate = LocalDate.of(2000, 2, 29);

        assertThat(Ages.fullAge(birthDate, LocalDate.of(2025, 2, 28))).isEqualTo(24);
        assertThat(Ages.fullAge(birthDate, LocalDate.of(2025, 3, 1))).isEqualTo(25);
        assertThat(Ages.fullAge(birthDate, LocalDate.of(2028, 2, 29))).isEqualTo(28);
    }

    @Test
    void 태어난_날은_영_세다() {
        assertThat(Ages.fullAge(LocalDate.of(2026, 10, 17), LocalDate.of(2026, 10, 17))).isZero();
    }
}
