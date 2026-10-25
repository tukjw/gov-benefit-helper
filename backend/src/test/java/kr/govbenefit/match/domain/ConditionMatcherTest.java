package kr.govbenefit.match.domain;

import static kr.govbenefit.match.domain.ItemStatus.MET;
import static kr.govbenefit.match.domain.ItemStatus.NEEDS_CHECK;
import static kr.govbenefit.match.domain.ItemStatus.NOT_EVALUATED;
import static kr.govbenefit.match.domain.MatchReason.AGE_ON_BOUNDARY;
import static kr.govbenefit.match.domain.MatchReason.AGE_RANGE_INVALID;
import static kr.govbenefit.match.domain.MatchReason.CODE_EXCLUDES;
import static kr.govbenefit.match.domain.MatchReason.CODE_INCLUDES;
import static kr.govbenefit.match.domain.MatchReason.CODE_MISSING;
import static kr.govbenefit.match.domain.MatchReason.CONDITIONS_NOT_COLLECTED;
import static kr.govbenefit.match.domain.MatchReason.NOT_PERSONAL_TARGET;
import static kr.govbenefit.match.domain.MatchReason.NO_RESTRICTION;
import static kr.govbenefit.match.domain.MatchReason.PROFILE_MISSING;
import static kr.govbenefit.match.domain.MatchReason.REGION_INCLUDES;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import kr.govbenefit.profile.domain.Gender;
import kr.govbenefit.profile.domain.HouseholdAttribute;
import kr.govbenefit.profile.domain.LifeAttribute;
import kr.govbenefit.profile.domain.ProfileAttribute;
import kr.govbenefit.region.domain.AgencyRegion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 조건 비교 규칙(DEC-MATCH)을 DB 없이 검증한다. 코드 값은 데이터 검증 보고서 §5의 형식이다.
 */
class ConditionMatcherTest {

    private static final String PERSONAL = "개인";
    private static final String GYEONGGI = "6410000";
    private static final String UIWANG = "4030000";
    private static final String SUWON = "3740000";
    private static final String BUSAN = "6260000";
    private static final AgencyRegion NO_REGION = AgencyRegion.NONE;
    private static final MatchSubject FULL = new MatchSubject(30, Gender.FEMALE,
            List.of(LifeAttribute.EMPLOYEE), List.of(HouseholdAttribute.SINGLE_PERSON_HOUSEHOLD), GYEONGGI, UIWANG);
    private static final MatchSubject EMPTY = new MatchSubject(null, null, null, null, null, null);

    @Test
    void 비교_항목을_코드상_모두_충족하면_부합이다() {
        SupportConditions conditions = conditions(19, 39, "JA0102", "JA0326", "JA0404");

        MatchOutcome outcome = ConditionMatcher.match(FULL, conditions, PERSONAL, NO_REGION);

        assertThat(outcome.result()).isEqualTo(MatchResult.MATCH);
        assertThat(Stream.of(outcome.personalTarget(), outcome.age(), outcome.gender(), outcome.lifeAttributes(),
                outcome.householdAttributes(), outcome.agencyRegion()).map(ItemJudgement::status)).containsOnly(MET);
        assertThat(outcome.age().reason()).isEqualTo(CODE_INCLUDES);
    }

    @Test
    void 제한이_없는_혜택은_프로필을_모두_입력하면_부합이다() {
        MatchOutcome outcome = ConditionMatcher.match(FULL, unrestricted(), PERSONAL, NO_REGION);

        assertThat(outcome.result()).isEqualTo(MatchResult.MATCH);
        assertThat(Stream.of(outcome.age(), outcome.gender(), outcome.lifeAttributes(),
                outcome.householdAttributes()).map(ItemJudgement::reason)).containsOnly(NO_RESTRICTION);
    }

    @ParameterizedTest(name = "{0}~{1}세 코드, 만 {2}세 → {3} {4}")
    @CsvSource({
            "19, 39, 25, MET, CODE_INCLUDES",
            "19, 39, 19, NEEDS_CHECK, AGE_ON_BOUNDARY",
            "19, 39, 39, NEEDS_CHECK, AGE_ON_BOUNDARY",
            "19, 39, 18, NEEDS_CHECK, CODE_EXCLUDES",
            "19, 39, 40, NEEDS_CHECK, CODE_EXCLUDES",
            "0, 120, 0, MET, NO_RESTRICTION",
            "0, 120, 120, MET, NO_RESTRICTION",
            // 0과 120은 제한 없음이라 경계값으로 치지 않는다.
            "0, 15, 0, MET, CODE_INCLUDES",
            "0, 15, 15, NEEDS_CHECK, AGE_ON_BOUNDARY",
            "0, 15, 16, NEEDS_CHECK, CODE_EXCLUDES",
            "65, 120, 120, MET, CODE_INCLUDES",
            "65, 120, 65, NEEDS_CHECK, AGE_ON_BOUNDARY",
            "65, 120, 64, NEEDS_CHECK, CODE_EXCLUDES",
            "20, 20, 20, NEEDS_CHECK, AGE_ON_BOUNDARY",
    })
    void 연령(int start, int end, int age, ItemStatus status, MatchReason reason) {
        assertThat(ConditionMatcher.age(age, new SupportConditions(start, end, Set.of())))
                .isEqualTo(new ItemJudgement(status, reason));
    }

    @Test
    void 연령_코드가_없거나_해석할_수_없으면_확인_필요다() {
        assertThat(ConditionMatcher.age(30, new SupportConditions(null, null, Set.of())).reason())
                .isEqualTo(CODE_MISSING);
        assertThat(ConditionMatcher.age(30, new SupportConditions(19, null, Set.of())).reason())
                .isEqualTo(AGE_RANGE_INVALID);
        assertThat(ConditionMatcher.age(30, new SupportConditions(null, 39, Set.of())).reason())
                .isEqualTo(AGE_RANGE_INVALID);
        assertThat(ConditionMatcher.age(30, new SupportConditions(40, 39, Set.of())).reason())
                .isEqualTo(AGE_RANGE_INVALID);
        // 코드를 해석할 수 없으면 프로필 미입력보다 먼저 알린다.
        assertThat(ConditionMatcher.age(null, new SupportConditions(40, 39, Set.of())).reason())
                .isEqualTo(AGE_RANGE_INVALID);
    }

    @ParameterizedTest(name = "코드 {0}, 프로필 {1} → {2} {3}")
    @CsvSource({
            "'JA0101,JA0102', MALE, MET, NO_RESTRICTION",
            "JA0102, FEMALE, MET, CODE_INCLUDES",
            "JA0102, MALE, NEEDS_CHECK, CODE_EXCLUDES",
            "JA0101, FEMALE, NEEDS_CHECK, CODE_EXCLUDES",
            "'', FEMALE, NEEDS_CHECK, CODE_MISSING",
    })
    void 성별(String codes, Gender gender, ItemStatus status, MatchReason reason) {
        assertThat(ConditionMatcher.gender(gender, new SupportConditions(null, null, codes(codes))))
                .isEqualTo(new ItemJudgement(status, reason));
    }

    @ParameterizedTest(name = "코드 {0}, 프로필 {1} → {2} {3}")
    @CsvSource({
            // 하나라도 Y이면 충족이다(그룹 안 OR).
            "'JA0302,JA0328', 'PREGNANT,EMPLOYEE', MET, CODE_INCLUDES",
            "'JA0302,JA0328', EMPLOYEE, NEEDS_CHECK, CODE_EXCLUDES",
            // 해당사항없음만 Y이면 제한이 없다(보고서 §5).
            "JA0322, DISABLED, MET, NO_RESTRICTION",
            "JA0322, NONE, MET, NO_RESTRICTION",
            // 해당 없음은 해당사항없음 코드로 비교한다.
            "'JA0328,JA0322', NONE, MET, CODE_INCLUDES",
            "JA0328, NONE, NEEDS_CHECK, CODE_EXCLUDES",
            "'', NONE, NEEDS_CHECK, CODE_MISSING",
    })
    void 생애_속성(String codes, String attributes, ItemStatus status, MatchReason reason) {
        List<LifeAttribute> profile = Stream.of(attributes.split(",")).map(LifeAttribute::valueOf).toList();

        assertThat(lifeGroup(profile, new SupportConditions(null, null, codes(codes))))
                .isEqualTo(new ItemJudgement(status, reason));
    }

    @Test
    void 그룹_전부_Y이면_제한이_없다() {
        SupportConditions all = new SupportConditions(null, null, codes(HouseholdAttribute.values()));

        assertThat(householdGroup(List.of(HouseholdAttribute.NONE), all))
                .isEqualTo(new ItemJudgement(MET, NO_RESTRICTION));
        assertThat(householdGroup(List.of(HouseholdAttribute.NEW_RESIDENT), all))
                .isEqualTo(new ItemJudgement(MET, NO_RESTRICTION));
    }

    @Test
    void 해당_없음과_미입력은_다르게_판정된다() {
        SupportConditions conditions = new SupportConditions(null, null, Set.of("JA0403", "JA0410"));

        assertThat(householdGroup(List.of(HouseholdAttribute.NONE), conditions))
                .isEqualTo(new ItemJudgement(MET, CODE_INCLUDES));
        assertThat(householdGroup(null, conditions)).isEqualTo(new ItemJudgement(NEEDS_CHECK, PROFILE_MISSING));
    }

    @Test
    void 제한이_없어도_프로필이_미입력이면_확인_필요다() {
        MatchOutcome outcome = ConditionMatcher.match(EMPTY, unrestricted(), PERSONAL, NO_REGION);

        assertThat(outcome.result()).isEqualTo(MatchResult.NEEDS_CHECK);
        assertThat(outcome.personalTarget().status()).isEqualTo(MET);
        assertThat(Stream.of(outcome.age(), outcome.gender(), outcome.lifeAttributes(),
                outcome.householdAttributes())).containsOnly(new ItemJudgement(NEEDS_CHECK, PROFILE_MISSING));
    }

    @Test
    void 그룹_하나라도_충족하지_않으면_부합이_아니다() {
        Set<String> codes = new HashSet<>(unrestricted().yesCodes());
        codes.remove("JA0404");
        codes.remove("JA0410");

        MatchOutcome outcome = ConditionMatcher.match(FULL, new SupportConditions(0, 120, codes), PERSONAL, NO_REGION);

        assertThat(outcome.result()).isEqualTo(MatchResult.NEEDS_CHECK);
        assertThat(outcome.householdAttributes()).isEqualTo(new ItemJudgement(NEEDS_CHECK, CODE_EXCLUDES));
        assertThat(Stream.of(outcome.age(), outcome.gender(), outcome.lifeAttributes()).map(ItemJudgement::status))
                .containsOnly(MET);
    }

    @Test
    void 지원조건을_수집하지_못했으면_확인_필요이고_불일치가_아니다() {
        MatchOutcome outcome = ConditionMatcher.match(FULL, null, PERSONAL, NO_REGION);

        assertThat(outcome.result()).isEqualTo(MatchResult.NEEDS_CHECK);
        assertThat(outcome.personalTarget().status()).isEqualTo(MET);
        assertThat(Stream.of(outcome.age(), outcome.gender(), outcome.lifeAttributes(),
                outcome.householdAttributes())).containsOnly(new ItemJudgement(NEEDS_CHECK, CONDITIONS_NOT_COLLECTED));
    }

    @Test
    void 개인_코드가_전부_없어도_개인_대상이면_확인_필요다() {
        MatchOutcome outcome = ConditionMatcher.match(FULL, new SupportConditions(null, null, Set.of()), PERSONAL, NO_REGION);

        assertThat(outcome.result()).isEqualTo(MatchResult.NEEDS_CHECK);
        assertThat(Stream.of(outcome.age(), outcome.gender(), outcome.lifeAttributes(),
                outcome.householdAttributes())).containsOnly(new ItemJudgement(NEEDS_CHECK, CODE_MISSING));
    }

    @Test
    void 개인_대상이_아니면_불일치이고_나머지_항목은_판정하지_않는다() {
        for (SupportConditions conditions : Arrays.asList(unrestricted(), null)) {
            MatchOutcome outcome = ConditionMatcher.match(FULL, conditions, "법인/시설/단체", NO_REGION);

            assertThat(outcome.result()).isEqualTo(MatchResult.MISMATCH);
            assertThat(outcome.personalTarget().status()).isEqualTo(ItemStatus.NOT_MET);
            assertThat(Stream.of(outcome.age(), outcome.gender(), outcome.lifeAttributes(),
                    outcome.householdAttributes(), outcome.agencyRegion()))
                    .containsOnly(new ItemJudgement(NOT_EVALUATED, NOT_PERSONAL_TARGET));
        }
    }

    @Test
    void 개인_대상_혜택은_어떤_코드와_프로필에도_불일치가_아니다() {
        List<SupportConditions> conditionsCases = Arrays.asList(null, unrestricted(),
                new SupportConditions(null, null, Set.of()), new SupportConditions(65, 120, Set.of("JA0101", "JA0328")),
                new SupportConditions(40, 39, Set.of("JA0402")));
        List<MatchSubject> subjects = List.of(FULL, EMPTY, new MatchSubject(70, Gender.MALE,
                List.of(LifeAttribute.NONE), List.of(HouseholdAttribute.NONE), BUSAN, null));
        List<AgencyRegion> regions = List.of(NO_REGION, AgencyRegion.UNKNOWN, AgencyRegion.sido(BUSAN),
                AgencyRegion.sigungu(GYEONGGI, SUWON));
        for (String userType : List.of("개인", "가구||법인/시설/단체", "")) {
            for (SupportConditions conditions : conditionsCases) {
                for (MatchSubject subject : subjects) {
                    for (AgencyRegion region : regions) {
                        assertThat(ConditionMatcher.match(subject, conditions, userType, region).result())
                                .isNotEqualTo(MatchResult.MISMATCH);
                    }
                }
            }
        }
    }

    @Test
    void 사용자구분이_비어_있으면_확인_필요다() {
        MatchOutcome outcome = ConditionMatcher.match(FULL, unrestricted(), null, NO_REGION);

        assertThat(outcome.result()).isEqualTo(MatchResult.NEEDS_CHECK);
        assertThat(outcome.personalTarget().status()).isEqualTo(NEEDS_CHECK);
        assertThat(outcome.age()).isEqualTo(new ItemJudgement(MET, NO_RESTRICTION));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(nullValues = "null", textBlock = """
            중앙·공공기관,          NONE,    null,    null,    6410000, 4030000, MET,         NO_RESTRICTION
            중앙·공공기관_거주지_미입력, NONE,    null,    null,    null,    null,    MET,         NO_RESTRICTION
            같은_시도,              LOCATED, 6410000, null,    6410000, 4030000, MET,         REGION_INCLUDES
            시도만_입력_같은_시도,     LOCATED, 6410000, null,    6410000, null,    MET,         REGION_INCLUDES
            같은_시군구,            LOCATED, 6410000, 4030000, 6410000, 4030000, MET,         REGION_INCLUDES
            다른_시군구,            LOCATED, 6410000, 3740000, 6410000, 4030000, NEEDS_CHECK, REGION_EXCLUDES
            다른_시도,              LOCATED, 6260000, null,    6410000, 4030000, NEEDS_CHECK, REGION_EXCLUDES
            다른_시도의_시군구,       LOCATED, 6260000, 3380000, 6410000, 4030000, NEEDS_CHECK, REGION_EXCLUDES
            시도만_입력_다른_시도의_시군구, LOCATED, 6260000, 3380000, 6410000, null, NEEDS_CHECK, REGION_EXCLUDES
            시도만_입력_도내_시군구,   LOCATED, 6410000, 4030000, 6410000, null,    NEEDS_CHECK, PROFILE_MISSING
            거주지_미입력_시도_기관,   LOCATED, 6410000, null,    null,    null,    NEEDS_CHECK, PROFILE_MISSING
            거주지_미입력_시군구_기관, LOCATED, 6410000, 4030000, null,    null,    NEEDS_CHECK, PROFILE_MISSING
            지역_모름,              UNKNOWN, null,    null,    6410000, 4030000, NEEDS_CHECK, AGENCY_REGION_UNKNOWN
            지역_모름_거주지_미입력,   UNKNOWN, null,    null,    null,    null,    NEEDS_CHECK, AGENCY_REGION_UNKNOWN
            """)
    void 운영_기관_지역은_기관_소재지와_거주지로_판정한다(String name, AgencyRegion.Kind kind, String agencySido,
                                           String agencySigungu, String sido, String sigungu,
                                           ItemStatus status, MatchReason reason) {
        AgencyRegion agency = new AgencyRegion(kind, agencySido, agencySigungu);
        MatchSubject subject = new MatchSubject(30, Gender.FEMALE, List.of(LifeAttribute.EMPLOYEE),
                List.of(HouseholdAttribute.SINGLE_PERSON_HOUSEHOLD), sido, sigungu);

        assertThat(ConditionMatcher.agencyRegion(subject, agency)).isEqualTo(new ItemJudgement(status, reason));
    }

    @Test
    void 다른_항목을_모두_충족해도_지역이_확인_필요면_확인_필요다() {
        SupportConditions conditions = conditions(19, 39, "JA0102", "JA0326", "JA0404");

        for (AgencyRegion region : List.of(AgencyRegion.sido(BUSAN), AgencyRegion.UNKNOWN)) {
            MatchOutcome outcome = ConditionMatcher.match(FULL, conditions, PERSONAL, region);

            assertThat(outcome.result()).isEqualTo(MatchResult.NEEDS_CHECK);
            assertThat(Stream.of(outcome.age(), outcome.gender(), outcome.lifeAttributes(),
                    outcome.householdAttributes()).map(ItemJudgement::status)).containsOnly(MET);
            assertThat(outcome.agencyRegion().status()).isEqualTo(NEEDS_CHECK);
        }
        assertThat(ConditionMatcher.match(FULL, conditions, PERSONAL, AgencyRegion.sigungu(GYEONGGI, UIWANG))
                .result()).isEqualTo(MatchResult.MATCH);
    }

    @Test
    void 운영_기관_지역은_지원조건을_수집하지_못해도_판정한다() {
        MatchOutcome outcome = ConditionMatcher.match(FULL, null, PERSONAL, AgencyRegion.sido(GYEONGGI));

        assertThat(outcome.agencyRegion()).isEqualTo(new ItemJudgement(MET, REGION_INCLUDES));
    }

    private static ItemJudgement lifeGroup(List<LifeAttribute> profile, SupportConditions conditions) {
        return ConditionMatcher.attributeGroup(profile, LifeAttribute.values(), LifeAttribute.NONE, conditions);
    }

    private static ItemJudgement householdGroup(List<HouseholdAttribute> profile, SupportConditions conditions) {
        return ConditionMatcher.attributeGroup(profile, HouseholdAttribute.values(), HouseholdAttribute.NONE,
                conditions);
    }

    /** 성별·생애·가구 전부 Y, 연령 0~120 */
    private static SupportConditions unrestricted() {
        Set<String> codes = new HashSet<>(Set.of("JA0101", "JA0102"));
        codes.addAll(codes(LifeAttribute.values()));
        codes.addAll(codes(HouseholdAttribute.values()));
        return new SupportConditions(0, 120, codes);
    }

    private static SupportConditions conditions(Integer start, Integer end, String... codes) {
        return new SupportConditions(start, end, Set.of(codes));
    }

    private static Set<String> codes(String csv) {
        return csv == null || csv.isBlank() ? Set.of() : Set.of(csv.split(","));
    }

    private static Set<String> codes(ProfileAttribute[] attributes) {
        return Stream.of(attributes).map(ProfileAttribute::jaCode).collect(Collectors.toSet());
    }
}
