package kr.govbenefit.match.domain;

/**
 * 비교 항목 하나의 판정.
 */
public enum ItemStatus {
    /** 코드상 충족한다. */
    MET,
    /** 원문을 확인해야 한다. 이유는 {@link MatchReason}이다. */
    NEEDS_CHECK,
    /** 충족하지 않는다. 개인 대상 항목에만 쓴다. */
    NOT_MET,
    /** 개인 대상이 아닌 혜택이라 판정하지 않았다. */
    NOT_EVALUATED
}
