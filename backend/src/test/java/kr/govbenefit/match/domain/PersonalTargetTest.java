package kr.govbenefit.match.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 2026-10-21 수집 데이터에 있는 사용자구분 14종으로 판정한다.
 */
class PersonalTargetTest {

    @ParameterizedTest
    @ValueSource(strings = {"개인", "가구", "개인||가구", "개인||법인/시설/단체", "개인||소상공인",
            "개인||가구||소상공인||법인/시설/단체", "개인||소상공인||법인/시설/단체", "개인||가구||법인/시설/단체",
            "가구||법인/시설/단체", "개인||가구||소상공인", "가구||소상공인"})
    void 개인이나_가구가_있으면_개인_대상이다(String userType) {
        assertThat(PersonalTarget.judge(userType))
                .isEqualTo(new ItemJudgement(ItemStatus.MET, MatchReason.PERSONAL_USER_TYPE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"법인/시설/단체", "소상공인", "소상공인||법인/시설/단체"})
    void 개인과_가구가_모두_없으면_개인_대상이_아니다(String userType) {
        assertThat(PersonalTarget.judge(userType))
                .isEqualTo(new ItemJudgement(ItemStatus.NOT_MET, MatchReason.NON_PERSONAL_USER_TYPE));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void 비어_있으면_확인_필요다(String userType) {
        assertThat(PersonalTarget.judge(userType))
                .isEqualTo(new ItemJudgement(ItemStatus.NEEDS_CHECK, MatchReason.USER_TYPE_MISSING));
    }

    @ParameterizedTest
    @ValueSource(strings = {"개인사업자", "가구원||법인/시설/단체"})
    void 값_전체가_같을_때만_개인_대상으로_본다(String userType) {
        assertThat(PersonalTarget.judge(userType).status()).isEqualTo(ItemStatus.NOT_MET);
    }
}
