package kr.govbenefit.region.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 지역 전체(243행)로 만든 조회(결정 0021, 0025). 소관기관유형·코드로 운영 기관 지역을 정하고, 검색 필터의 코드를 만든다.
 * 기관명은 해석하지 않는다.
 */
public final class Regions {

    private final Map<String, Region> byCode;
    private final Map<String, Region> sidoByEducationOffice;

    private Regions(Map<String, Region> byCode, Map<String, Region> sidoByEducationOffice) {
        this.byCode = byCode;
        this.sidoByEducationOffice = sidoByEducationOffice;
    }

    public static Regions of(Collection<Region> regions) {
        return new Regions(
                regions.stream().collect(Collectors.toUnmodifiableMap(Region::getCode, Function.identity())),
                regions.stream().filter(region -> region.getEducationOfficeCode() != null)
                        .collect(Collectors.toUnmodifiableMap(Region::getEducationOfficeCode, Function.identity())));
    }

    public Optional<Region> find(String code) {
        return Optional.ofNullable(code).map(byCode::get);
    }

    /**
     * 지자체 유형은 코드를 시도·시군구에서, 교육청은 시도의 교육청 코드에서 찾는다. 찾지 못하면 지역 모름이다.
     *
     * @param agencyType 목록의 소관기관유형 원문
     * @param agencyCode 목록의 소관기관코드 원문
     */
    public AgencyRegion agencyRegionOf(String agencyType, String agencyCode) {
        return switch (AgencyCategory.of(agencyType)) {
            case NO_REGION -> AgencyRegion.NONE;
            case LOCAL_GOVERNMENT -> find(agencyCode)
                    .map(region -> region.isSido()
                            ? AgencyRegion.sido(region.getCode())
                            : AgencyRegion.sigungu(region.getParentCode(), region.getCode()))
                    .orElse(AgencyRegion.UNKNOWN);
            case EDUCATION_OFFICE -> Optional.ofNullable(agencyCode).map(sidoByEducationOffice::get)
                    .map(sido -> AgencyRegion.sido(sido.getCode()))
                    .orElse(AgencyRegion.UNKNOWN);
            case UNKNOWN -> AgencyRegion.UNKNOWN;
        };
    }

    /**
     * 시군구가 있으면 그 시군구 기관만, 없으면 시도 기관·도내 시군구 기관·교육청이다. 지역 없는 기관은 나오지 않는다.
     *
     * @param sido    검증한 시도
     * @param sigungu 검증한 시군구. 없으면 null이다.
     */
    public AgencyFilter agencyFilterOf(Region sido, Region sigungu) {
        if (sigungu != null) {
            return new AgencyFilter(List.of(sigungu.getCode()), List.of());
        }
        List<String> codes = new ArrayList<>();
        codes.add(sido.getCode());
        byCode.values().stream()
                .filter(region -> sido.getCode().equals(region.getParentCode()))
                .map(Region::getCode)
                .sorted()
                .forEach(codes::add);
        List<String> educationOffices = sido.getEducationOfficeCode() == null ? List.of()
                : List.of(sido.getEducationOfficeCode());
        return new AgencyFilter(List.copyOf(codes), educationOffices);
    }
}
