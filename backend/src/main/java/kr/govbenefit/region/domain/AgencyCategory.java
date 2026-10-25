package kr.govbenefit.region.domain;

import java.util.Arrays;
import java.util.List;

/**
 * 운영 기관 지역을 정하는 방법에 따른 소관기관유형 분류(결정 0025). 지역 없는 기관은 코드가 아니라 유형으로 정한다.
 */
public enum AgencyCategory {
    /** 지역이 없다. */
    NO_REGION(List.of("중앙행정기관", "공공기관")),
    /** 소관기관코드가 시도·시군구 코드다. 산하 공기업·출연기관은 상위 자치단체 코드를 쓴다. */
    LOCAL_GOVERNMENT(List.of("광역시도", "시군구", "지방공기업", "지방출자_출연기관")),
    /** 소관기관코드가 시도의 교육청 코드다. */
    EDUCATION_OFFICE(List.of("교육청")),
    /** 처음 보는 유형이거나 유형이 비어 있다. 지역 모름이다. */
    UNKNOWN(List.of());

    private final List<String> agencyTypes;

    AgencyCategory(List<String> agencyTypes) {
        this.agencyTypes = agencyTypes;
    }

    /**
     * @param agencyType 목록의 소관기관유형 원문
     */
    public static AgencyCategory of(String agencyType) {
        if (agencyType == null) {
            return UNKNOWN;
        }
        return Arrays.stream(values())
                .filter(category -> category.agencyTypes.contains(agencyType))
                .findFirst()
                .orElse(UNKNOWN);
    }

    /** 이 분류에 드는 소관기관유형 원문 값 */
    public List<String> agencyTypes() {
        return agencyTypes;
    }
}
