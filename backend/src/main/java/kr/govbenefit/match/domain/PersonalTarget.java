package kr.govbenefit.match.domain;

import java.util.Arrays;
import java.util.Set;

/**
 * 개인 대상 여부(DEC-MATCH). 목록의 사용자구분으로 판정하고 지원조건 코드는 보지 않는다.
 * 그래서 지원조건을 수집하지 못한 것만으로 개인 대상이 아니게 되지 않는다.
 */
public final class PersonalTarget {

    /** 사용자구분 원문의 여러 값 구분자 */
    private static final String SEPARATOR = "\\|\\|";
    private static final Set<String> PERSONAL = Set.of("개인", "가구");

    private PersonalTarget() {
    }

    /**
     * 사용자구분에 개인 또는 가구가 있으면 충족, 둘 다 없으면 미충족이다. 비어 있으면 데이터 누락이라 확인 필요다.
     */
    public static ItemJudgement judge(String userType) {
        if (userType == null || userType.isBlank()) {
            return ItemJudgement.needsCheck(MatchReason.USER_TYPE_MISSING);
        }
        boolean personal = Arrays.stream(userType.split(SEPARATOR)).map(String::strip).anyMatch(PERSONAL::contains);
        return personal
                ? ItemJudgement.met(MatchReason.PERSONAL_USER_TYPE)
                : new ItemJudgement(ItemStatus.NOT_MET, MatchReason.NON_PERSONAL_USER_TYPE);
    }
}
