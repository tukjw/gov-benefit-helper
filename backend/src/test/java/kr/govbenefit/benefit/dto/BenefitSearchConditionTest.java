package kr.govbenefit.benefit.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import kr.govbenefit.common.api.InvalidInputException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class BenefitSearchConditionTest {

    @Test
    void 키워드를_유니코드_공백으로_나눈다() {
        assertThat(keywords(" 청년\t월세　지원  ")).containsExactly("청년", "월세", "지원");
    }

    @Test
    void 중복_단어는_한_번만_쓴다() {
        assertThat(keywords("청년 월세 청년")).containsExactly("청년", "월세");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "　"})
    void 빈_키워드는_키워드_조건이_없다(String keyword) {
        assertThat(keywords(keyword)).isEmpty();
    }

    @Test
    void 단어는_10개까지다() {
        String ten = String.join(" ", Arrays.asList("가", "나", "다", "라", "마", "바", "사", "아", "자", "차"));

        assertThat(keywords(ten)).hasSize(10);
        assertThatThrownBy(() -> keywords(ten + " 카"))
                .isInstanceOf(InvalidInputException.class)
                .satisfies(e -> assertThat(((InvalidInputException) e).error().field()).isEqualTo("keyword"));
    }

    @Test
    void 필터는_빈_값과_중복만_빼고_원문_그대로_둔다() {
        BenefitSearchCondition condition = BenefitSearchCondition.of(null,
                Arrays.asList("보육·교육", "", " ", null, "보육·교육", " 생활안정"), null, null, null);

        assertThat(condition.fields()).containsExactly("보육·교육", " 생활안정");
        assertThat(condition.agencyTypes()).isEmpty();
    }

    @Test
    void 지역_코드는_빈_값이면_조건이_없다() {
        assertThat(BenefitSearchCondition.of(null, null, null, " ", "")).satisfies(condition -> {
            assertThat(condition.agencySidoCode()).isNull();
            assertThat(condition.agencySigunguCode()).isNull();
        });
        assertThat(BenefitSearchCondition.of(null, null, null, "6410000", "3740000").agencySigunguCode())
                .isEqualTo("3740000");
    }

    private static List<String> keywords(String keyword) {
        return BenefitSearchCondition.of(keyword, null, null, null, null).keywords();
    }
}
