package kr.govbenefit.match.domain;

import java.util.List;
import java.util.stream.Stream;
import kr.govbenefit.profile.domain.Gender;
import kr.govbenefit.profile.domain.HouseholdAttribute;
import kr.govbenefit.profile.domain.LifeAttribute;
import kr.govbenefit.profile.domain.ProfileAttribute;

/**
 * 프로필과 혜택의 지원조건을 비교한다(REQ-MATCH-01, DEC-MATCH). DB를 모르며 같은 입력이면 같은 결과다.
 * <ul>
 *   <li>개인 대상이 아니면 불일치이고 나머지 항목은 판정하지 않는다. 개인 대상 혜택은 어떤 경우에도 불일치가 아니다.</li>
 *   <li>개인 대상이면 모든 항목이 충족일 때 부합, 아니면 확인 필요다. 그룹 사이는 AND로 해석한다.</li>
 *   <li>항목 이유는 지원조건 미수집 → 코드 없음·해석 불가 → 프로필 미입력 → 비교 결과 순으로 정한다.
 *       그래서 혜택에 제한이 없어도 프로필이 미입력이면 확인 필요다.</li>
 * </ul>
 */
public final class ConditionMatcher {

    /** 연령 시작이 이 값 이하이면 하한이 없다. 경계값으로 치지 않는다. */
    private static final int AGE_NO_LOWER_LIMIT = 0;
    /** 연령 종료가 이 값 이상이면 상한이 없다. 경계값으로 치지 않는다. */
    private static final int AGE_NO_UPPER_LIMIT = 120;

    private static final ItemJudgement NOT_EVALUATED =
            new ItemJudgement(ItemStatus.NOT_EVALUATED, MatchReason.NOT_PERSONAL_TARGET);

    private ConditionMatcher() {
    }

    /**
     * @param conditions 지원조건을 수집하지 못했으면 null이다.
     * @param userType   목록의 사용자구분 원문
     */
    public static MatchOutcome match(MatchSubject subject, SupportConditions conditions, String userType) {
        ItemJudgement personalTarget = PersonalTarget.judge(userType);
        if (personalTarget.status() == ItemStatus.NOT_MET) {
            return new MatchOutcome(MatchResult.MISMATCH, personalTarget, NOT_EVALUATED, NOT_EVALUATED,
                    NOT_EVALUATED, NOT_EVALUATED);
        }
        ItemJudgement age = age(subject.age(), conditions);
        ItemJudgement gender = gender(subject.gender(), conditions);
        ItemJudgement life = attributeGroup(subject.lifeAttributes(), LifeAttribute.values(), LifeAttribute.NONE,
                conditions);
        ItemJudgement household = attributeGroup(subject.householdAttributes(), HouseholdAttribute.values(),
                HouseholdAttribute.NONE, conditions);
        boolean allMet = Stream.of(personalTarget, age, gender, life, household)
                .allMatch(judgement -> judgement.status() == ItemStatus.MET);
        return new MatchOutcome(allMet ? MatchResult.MATCH : MatchResult.NEEDS_CHECK, personalTarget, age, gender,
                life, household);
    }

    /**
     * 시작·종료는 포함 범위로 읽되, 경계 포함 여부를 확인하지 못했으므로 경계값은 확인 필요다. 0과 120은 제한 없음이다.
     */
    static ItemJudgement age(Integer age, SupportConditions conditions) {
        if (conditions == null) {
            return ItemJudgement.needsCheck(MatchReason.CONDITIONS_NOT_COLLECTED);
        }
        Integer start = conditions.ageStart();
        Integer end = conditions.ageEnd();
        if (start == null && end == null) {
            return ItemJudgement.needsCheck(MatchReason.CODE_MISSING);
        }
        if (start == null || end == null || start > end) {
            return ItemJudgement.needsCheck(MatchReason.AGE_RANGE_INVALID);
        }
        if (age == null) {
            return ItemJudgement.needsCheck(MatchReason.PROFILE_MISSING);
        }
        int value = age;
        boolean lowerLimited = start > AGE_NO_LOWER_LIMIT;
        boolean upperLimited = end < AGE_NO_UPPER_LIMIT;
        if (!lowerLimited && !upperLimited) {
            return ItemJudgement.met(MatchReason.NO_RESTRICTION);
        }
        if ((lowerLimited && value < start) || (upperLimited && value > end)) {
            return ItemJudgement.needsCheck(MatchReason.CODE_EXCLUDES);
        }
        if ((lowerLimited && value == start) || (upperLimited && value == end)) {
            return ItemJudgement.needsCheck(MatchReason.AGE_ON_BOUNDARY);
        }
        return ItemJudgement.met(MatchReason.CODE_INCLUDES);
    }

    static ItemJudgement gender(Gender gender, SupportConditions conditions) {
        if (conditions == null) {
            return ItemJudgement.needsCheck(MatchReason.CONDITIONS_NOT_COLLECTED);
        }
        boolean male = conditions.isYes(SupportConditions.MALE);
        boolean female = conditions.isYes(SupportConditions.FEMALE);
        if (!male && !female) {
            return ItemJudgement.needsCheck(MatchReason.CODE_MISSING);
        }
        if (gender == null) {
            return ItemJudgement.needsCheck(MatchReason.PROFILE_MISSING);
        }
        if (male && female) {
            return ItemJudgement.met(MatchReason.NO_RESTRICTION);
        }
        return conditions.isYes(SupportConditions.genderCode(gender))
                ? ItemJudgement.met(MatchReason.CODE_INCLUDES)
                : ItemJudgement.needsCheck(MatchReason.CODE_EXCLUDES);
    }

    /**
     * 그룹 안은 OR로 해석한다. 그룹 전부 Y이거나 해당사항없음만 Y이면 제한이 없다(보고서 §5). 프로필의 {@code NONE}은
     * 해당사항없음 코드로 비교하므로 미입력과 다르게 판정된다.
     *
     * @param attributes 프로필의 그룹 값. 미입력이면 null이다.
     */
    static <A extends ProfileAttribute> ItemJudgement attributeGroup(List<A> attributes, A[] group, A none,
                                                                     SupportConditions conditions) {
        if (conditions == null) {
            return ItemJudgement.needsCheck(MatchReason.CONDITIONS_NOT_COLLECTED);
        }
        List<A> included = conditions.included(group);
        if (included.isEmpty()) {
            return ItemJudgement.needsCheck(MatchReason.CODE_MISSING);
        }
        if (attributes == null) {
            return ItemJudgement.needsCheck(MatchReason.PROFILE_MISSING);
        }
        if (included.size() == group.length || included.equals(List.of(none))) {
            return ItemJudgement.met(MatchReason.NO_RESTRICTION);
        }
        return attributes.stream().anyMatch(included::contains)
                ? ItemJudgement.met(MatchReason.CODE_INCLUDES)
                : ItemJudgement.needsCheck(MatchReason.CODE_EXCLUDES);
    }
}
