package kr.govbenefit.match.dto;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import kr.govbenefit.benefit.dto.BenefitSearchRequest;

/**
 * 조건 비교 목록의 지역 범위, 쪽 번호와 크기. 쪽 번호·크기의 범위와 기본값은 혜택 검색과 같다(DEC-SEARCH).
 *
 * @param regionScope 운영 기관 지역 범위. 없으면 null이며 거주지에 따라 정한다.
 *
 * @param page 1부터 센 쪽 번호. 없으면 1이다.
 * @param size 쪽 크기. 없으면 20이다.
 */
public record MatchListRequest(
        @Parameter(description = """
                운영 기관 지역 범위. 거주 요건이 아니라 운영 기관 소재지 기준이다(결정 0025).
                - `MY_REGION`: 지역 없는 기관(중앙행정기관, 공공기관)과 거주 시도의 시도 기관(교육청 포함), 거주 시군구의 기관. \
                시도만 입력했으면 도내 모든 시군구 기관이다.
                - `ALL`: 개인 대상 혜택 전체.
                없으면 거주지가 있을 때 `MY_REGION`, 없을 때 `ALL`이다. 거주지가 없으면 `MY_REGION`을 보내도 `ALL`이다.""")
        RegionScope regionScope,
        @Parameter(description = "1부터 센 쪽 번호", schema = @Schema(implementation = Integer.class, defaultValue = "1"))
        @Min(value = 1, message = MatchListRequest.PAGE_RANGE) Integer page,
        @Parameter(description = "쪽 크기", schema = @Schema(implementation = Integer.class, defaultValue = "20"))
        @Min(value = 1, message = MatchListRequest.SIZE_RANGE)
        @Max(value = BenefitSearchRequest.MAX_SIZE, message = MatchListRequest.SIZE_RANGE) Integer size) {

    static final String PAGE_RANGE = "1 이상이어야 합니다.";
    static final String SIZE_RANGE = "1 이상 100 이하여야 합니다.";

    public MatchListRequest {
        page = page == null ? 1 : page;
        size = size == null ? BenefitSearchRequest.DEFAULT_SIZE : size;
    }
}
