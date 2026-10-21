package kr.govbenefit.match.domain;

/**
 * 조건 비교의 전체 결과(REQ-MATCH-01, DEC-MATCH). 어떤 값도 신청 가능 확정이 아니다.
 */
public enum MatchResult {
    /** 비교 항목을 지원조건 코드상 모두 충족한다. */
    MATCH,
    /** 코드상 벗어나거나, 코드·프로필 값이 없거나, 연령이 경계값인 항목이 있다. 원문을 확인해야 한다. */
    NEEDS_CHECK,
    /** 개인 대상이 아닌 혜택이다. 개인 대상 혜택에는 쓰지 않는다. */
    MISMATCH
}
