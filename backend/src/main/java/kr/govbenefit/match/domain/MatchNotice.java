package kr.govbenefit.match.domain;

/**
 * 결과와 함께 보여줄 고정 안내.
 */
public enum MatchNotice {
    /** 원문이 아니라 지원조건 코드로 비교한 결과다. */
    CODE_BASED,
    /** 그룹 안은 OR, 그룹 사이는 AND로 해석했다. 검증된 관계가 아니다. */
    GROUP_RULE_INTERPRETED,
    /** 신청 가능 확정이 아니다. 공식 안내로 확인해야 한다. */
    NOT_FINAL
}
