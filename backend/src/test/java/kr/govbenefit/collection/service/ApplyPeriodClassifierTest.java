package kr.govbenefit.collection.service;

import static kr.govbenefit.benefit.domain.ApplyPeriodRule.ALWAYS;
import static kr.govbenefit.benefit.domain.ApplyPeriodRule.BUDGET_OR_FIRST_COME;
import static kr.govbenefit.benefit.domain.ApplyPeriodRule.DATE;
import static kr.govbenefit.benefit.domain.ApplyPeriodRule.DATE_WITH_EARLY_CLOSE;
import static kr.govbenefit.benefit.domain.ApplyPeriodRule.EVENT_BASED;
import static kr.govbenefit.benefit.domain.ApplyPeriodRule.MULTIPLE_PERIODS;
import static kr.govbenefit.benefit.domain.ApplyPeriodRule.NONE_STATED;
import static kr.govbenefit.benefit.domain.ApplyPeriodRule.NOT_REQUIRED;
import static kr.govbenefit.benefit.domain.ApplyPeriodRule.NO_VALUE;
import static kr.govbenefit.benefit.domain.ApplyPeriodRule.OTHER;
import static kr.govbenefit.benefit.domain.ApplyPeriodRule.RECURRING;
import static kr.govbenefit.benefit.domain.ApplyPeriodRule.SEE_NOTICE;
import static kr.govbenefit.benefit.domain.ApplyPeriodRule.VARIES_BY_AGENCY;
import static kr.govbenefit.benefit.domain.ApplyPeriodRule.YEAR_MONTH_ONLY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.Arrays;
import java.util.stream.Stream;
import kr.govbenefit.benefit.domain.ApplyPeriodRule;
import kr.govbenefit.benefit.domain.ApplyPeriodType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class ApplyPeriodClassifierTest {

    /**
     * #5 신청기한 분류 2차 검증 표본(분류별 10건). 원문과 기대 세부 규칙은
     * docs/research/gov24-api-data/results/20260928T163852.md의 "신청기한 분류 검증 표본"에서 가져왔다.
     */
    static Stream<Arguments> verifiedSamples() {
        return Stream.of(
                // 명시 날짜
                arguments("627000000728", "2025.01.20~2026.12.31", DATE),
                arguments("497000000643", "2026. 4. 1.~ 예산 소진 시", DATE_WITH_EARLY_CLOSE),
                arguments("319000000167", "2025-07-14 ~ 예산소진 시까지", DATE_WITH_EARLY_CLOSE),
                arguments("344000000116", "2026.02.02~2026.11.30", DATE),
                arguments("B55145800002", "2023.07.01~2023.07.14", DATE),
                arguments("459000000135", "2025. 6. 30. ~ 2025. 7. 25.", DATE),
                arguments("374000000582", "2026.03.03.~선착순마감", DATE_WITH_EARLY_CLOSE),
                arguments("O00408400005", "2026. 5. 4.(월) 10:00 ~ 5. 26.(화) 18:00", DATE),
                arguments("O00378400006", "2026.06.10~2026.06.23", DATE),
                arguments("641000000853", "2026.3.25.~2026.4.9.", DATE),
                // 미확인
                arguments("630000000677", "온라인신청(추후공지)", SEE_NOTICE),
                arguments("650000000231", "제주도청 홈페이지에 참여기업 및 근로자 모집 공고", SEE_NOTICE),
                arguments("414000000450", "사업시행 전년도 8월(8.1.~8.30.)", RECURRING),
                arguments("506000000131", " - 주민센터 : 1월~2월경 사업장소재지 읍면동 신청", RECURRING),
                arguments("145000000043", "접수기관 별 상이", VARIES_BY_AGENCY),
                arguments("481000000381", "2025년 신청의 경우, 2025년 공고문 확인 (2025년 10월 시누리집)", YEAR_MONTH_ONLY),
                arguments("570000000112", "10월 말~ 11월 중순", RECURRING),
                arguments("417000000110", "1~2월", RECURRING),
                arguments("303000000325", "모집 시 성동구 홈페이지 공고", SEE_NOTICE),
                arguments("547000000109", "매년 1월 신청접수", RECURRING),
                // 상시
                arguments("408000000543", "상시신청", ALWAYS),
                arguments("462000000300", "상시신청", ALWAYS),
                arguments("724000000016", "상시신청", ALWAYS),
                arguments("O00022900006", "상시신청", ALWAYS),
                arguments("526000000105", "상시신청", ALWAYS),
                arguments("509000000124", "연초 및 상시신청", ALWAYS),
                arguments("364000000449", "상시신청", ALWAYS),
                arguments("390000000429", "상시신청", ALWAYS),
                arguments("351050000114", "상시신청", ALWAYS),
                arguments("444000000106", "상시신청", ALWAYS),
                // 신청 불필요
                arguments("715000000205", "별도의 신청절차가 없음", NOT_REQUIRED),
                arguments("748000000207", "별도의 신청절차가 없음", NOT_REQUIRED),
                arguments("O00021200002", "별도의 신청절차가 없음", NOT_REQUIRED),
                arguments("748000000205", "별도의 신청절차가 없음", NOT_REQUIRED),
                arguments("724000000030", "별도의 신청절차가 없음", NOT_REQUIRED),
                arguments("408000000534", "신청불요", NOT_REQUIRED),
                arguments("468100000020", "신청불요", NOT_REQUIRED),
                arguments("323000000119", "개인 신청절차 없음", NOT_REQUIRED),
                arguments("875000000013", "별도의 신청절차가 없음", NOT_REQUIRED),
                arguments("431000000117", "신청 불필요", NOT_REQUIRED),
                // 조건부
                arguments("374000000125", "전입일로부터 1개월 이내", EVENT_BASED),
                arguments("627000000111", "연중(예산 한도내)", BUDGET_OR_FIRST_COME),
                arguments("447000000126", "서비스 종료 후 3개월 이내", EVENT_BASED),
                arguments("149200000034", "근로자의 근로시간 단축 개시일 후 12개월 이내 관할 고용센터 기업지원부서 혹은 "
                        + "고용보험 홈페이지(www.work24.go.kr)로 신청", EVENT_BASED),
                arguments("405000000679", "시술 종료 후 3개월 이내 ", EVENT_BASED),
                arguments("414000000118", "전입일로부터 1년 이내", EVENT_BASED),
                arguments("467100000007", "상시신청(예산 소진 시 사업종료)", BUDGET_OR_FIRST_COME),
                arguments("347000000106", "출산예정일 40일 전부터 출산 후 60일 이내까지 ", EVENT_BASED),
                arguments("489000000115", "할인예산 소진시까지", BUDGET_OR_FIRST_COME),
                arguments("346000000116", "상시신청(예산 소진시까지)", BUDGET_OR_FIRST_COME));
    }

    @ParameterizedTest(name = "{0} {1} → {2}")
    @MethodSource("verifiedSamples")
    void 검증_표본의_신청기한을_세부_규칙으로_분류한다(String serviceId, String text, ApplyPeriodRule expected) {
        assertThat(ApplyPeriodClassifier.classify(text)).isEqualTo(expected);
    }

    @Test
    void 검증_표본은_다섯_분류마다_예시가_있다() {
        assertThat(verifiedSamples().map(sample -> ((ApplyPeriodRule) sample.get()[2]).type()).distinct())
                .containsExactlyInAnyOrder(ApplyPeriodType.values());
    }

    /**
     * #5 1차 검증에서 다른 분류로 잘못 갔던 원문. 고친 규칙에서는 기한을 알 수 없는 미확인이다.
     */
    @ParameterizedTest
    @ValueSource(strings = {"공고문에 접수시기 안내(매년 1월~2월)", "가축방역사업지침 시행일로부터 지정기간"})
    void 일차_검증의_오분류는_미확인으로_분류한다(String text) {
        assertThat(ApplyPeriodClassifier.classify(text).type()).isEqualTo(ApplyPeriodType.UNKNOWN);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "1차 2026.3.2.~3.13., 2차 2026.9.1.~9.12.",
            "상반기 2026.03.02~2026.03.13 / 하반기 2026.09.01~2026.09.12",
            "2026.1.5., 2026.4.6., 2026.7.6. 각 1일간",
            "1차: 2026. 2. 2.∼2. 13. 2차: 2026. 8. 3.∼8. 14.(예산 소진 시 조기 마감)"
    })
    void 모집_기간이_여럿이면_복수_기간으로_확인_필요다(String text) {
        ApplyPeriodRule rule = ApplyPeriodClassifier.classify(text);

        assertThat(rule).isEqualTo(MULTIPLE_PERIODS);
        assertThat(rule.type()).isEqualTo(ApplyPeriodType.FIXED_DATE);
        assertThat(rule.needsReview()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "2026.01.19~2026.12.18",
            "2026. 3. 23.(월) 09:00 ~ 12. 11.(금) 18:00",
            "2026.05.01."
    })
    void 기간이_하나면_명시_날짜다(String text) {
        assertThat(ApplyPeriodClassifier.classify(text)).isEqualTo(DATE);
    }

    @Test
    void 날짜와_조기_마감이_섞이거나_규칙에_맞지_않는_문구는_확인_필요다() {
        assertThat(ApplyPeriodClassifier.classify("2026. 4. 1.~ 예산 소진 시").needsReview()).isTrue();
        assertThat(ApplyPeriodClassifier.classify("무인민원발급기 운영시간")).isEqualTo(OTHER);
        assertThat(OTHER.needsReview()).isTrue();
    }

    @Test
    void 확인_필요는_복수_기간_조기_마감_기타에만_있다() {
        assertThat(Arrays.stream(ApplyPeriodRule.values()).filter(ApplyPeriodRule::needsReview))
                .containsExactlyInAnyOrder(MULTIPLE_PERIODS, DATE_WITH_EARLY_CLOSE, OTHER);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void 값이_없으면_미확인이다(String text) {
        assertThat(ApplyPeriodClassifier.classify(text)).isEqualTo(NO_VALUE);
        assertThat(NO_VALUE.type()).isEqualTo(ApplyPeriodType.UNKNOWN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"해당없음", "-", " 없음 "})
    void 없음만_적혀_있으면_미확인이다(String text) {
        assertThat(ApplyPeriodClassifier.classify(text)).isEqualTo(NONE_STATED);
    }
}
