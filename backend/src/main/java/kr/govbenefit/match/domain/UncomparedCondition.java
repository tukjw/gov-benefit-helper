package kr.govbenefit.match.domain;

/**
 * 비교하지 않은 조건. 혜택마다 다르지 않은 고정 목록이다.
 */
public enum UncomparedCondition {
    /** 소득 */
    INCOME,
    /** 원문의 거주 요건. 운영 기관 지역만 비교한다. */
    RESIDENCE,
    /** 원문의 그 밖의 조건 */
    OTHER_SOURCE_CONDITIONS
}
