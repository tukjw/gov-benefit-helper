package kr.govbenefit.match.dto;

/**
 * 조건 비교 목록의 운영 기관 지역 범위(DEC-MATCH, 결정 0025).
 */
public enum RegionScope {
    /** 지역 없는 기관과, 거주 시도의 시도 기관·거주 시군구의 기관. 시도만 입력했으면 도내 모든 시군구 기관이다. */
    MY_REGION,
    /** 개인 대상 혜택 전체 */
    ALL
}
