package kr.govbenefit.region.domain;

import java.util.List;

/**
 * 혜택 검색의 운영 기관 지역 필터가 찾을 소관기관코드(결정 0025). 유형과 함께 비교해야 조건 비교의 분류와 어긋나지 않는다.
 *
 * @param localGovernmentCodes  지자체 유형({@link AgencyCategory#LOCAL_GOVERNMENT}) 혜택에서 찾을 코드
 * @param educationOfficeCodes  교육청 유형 혜택에서 찾을 코드. 시군구 필터면 비어 있다.
 */
public record AgencyFilter(List<String> localGovernmentCodes, List<String> educationOfficeCodes) {
}
