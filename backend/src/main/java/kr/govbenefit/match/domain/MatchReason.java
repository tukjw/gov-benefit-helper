package kr.govbenefit.match.domain;

/**
 * 항목 판정의 이유. 여러 이유가 해당하면 지원조건 미수집 → 코드 없음·해석 불가 → 프로필 미입력 → 비교 결과 순으로 처음 것 하나다.
 * 운영 기관 지역은 지원조건과 무관하며 기관 지역 모름 → 지역 없는 기관 → 프로필 미입력 → 비교 결과 순이다.
 */
public enum MatchReason {
    /** 사용자구분에 개인 또는 가구가 있다. */
    PERSONAL_USER_TYPE,
    /** 사용자구분에 개인과 가구가 모두 없다. */
    NON_PERSONAL_USER_TYPE,
    /** 사용자구분이 비어 있다. */
    USER_TYPE_MISSING,
    /** 혜택의 지원조건을 아직 수집하지 못했다. */
    CONDITIONS_NOT_COLLECTED,
    /** 그 항목의 지원조건 코드가 모두 비어 있다. */
    CODE_MISSING,
    /** 연령 코드가 한쪽만 있거나 시작이 종료보다 크다. */
    AGE_RANGE_INVALID,
    /** 그 항목의 프로필 값이 미입력이다. 혜택에 제한이 없어도 확인 필요다. */
    PROFILE_MISSING,
    /** 혜택에 그 항목의 제한이 없다. 운영 기관 지역이면 지역이 없는 기관(중앙행정기관, 공공기관)이다. */
    NO_RESTRICTION,
    /** 프로필 값이 코드에 포함된다. */
    CODE_INCLUDES,
    /** 프로필 값이 코드에서 벗어난다. */
    CODE_EXCLUDES,
    /** 만 나이가 연령 코드의 경계값과 같다. 경계 포함 여부를 확인하지 못했다. */
    AGE_ON_BOUNDARY,
    /** 운영 기관이 거주 시도의 기관이거나 거주 시군구의 기관이다. */
    REGION_INCLUDES,
    /** 운영 기관이 다른 시도나 다른 시군구의 기관이다. 비거주자에게 열린 혜택일 수 있다. */
    REGION_EXCLUDES,
    /** 지자체 유형 기관인데 소관기관코드로 지역을 찾지 못했거나, 처음 보는 기관 유형이다. */
    AGENCY_REGION_UNKNOWN,
    /** 개인 대상이 아닌 혜택이라 판정하지 않았다. */
    NOT_PERSONAL_TARGET
}
