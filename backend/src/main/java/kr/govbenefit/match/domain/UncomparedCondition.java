package kr.govbenefit.match.domain;

/**
 * 비교하지 않은 조건. 혜택마다 다르지 않은 고정 목록이다.
 */
public enum UncomparedCondition {
    /** 소득 */
    INCOME,
    /** 거주 지역 */
    RESIDENCE,
    /** 원문의 그 밖의 조건 */
    OTHER_SOURCE_CONDITIONS
}
