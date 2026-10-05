package kr.govbenefit.benefit.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BenefitSpecificationsTest {

    @Test
    void LIKE_특수_문자를_이스케이프한다() {
        assertThat(BenefitSpecifications.escapeLike("100%")).isEqualTo("100\\%");
        assertThat(BenefitSpecifications.escapeLike("지방출자_출연기관")).isEqualTo("지방출자\\_출연기관");
        assertThat(BenefitSpecifications.escapeLike("a\\b")).isEqualTo("a\\\\b");
        assertThat(BenefitSpecifications.escapeLike("청년")).isEqualTo("청년");
    }
}
