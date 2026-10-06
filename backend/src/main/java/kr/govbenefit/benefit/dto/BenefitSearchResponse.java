package kr.govbenefit.benefit.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 혜택 검색 결과.
 *
 * @param totalCount 조건에 맞는 전체 건수. 규모가 커지면 빼거나 다음 쪽 여부로 바꿀 수 있는 항목이다(DEC-SEARCH).
 * @param page       요청한 쪽 번호. 1부터 센다.
 * @param size       요청한 쪽 크기
 * @param items      이 쪽의 항목. 마지막 쪽을 넘으면 비어 있다.
 */
@Schema(description = "혜택 검색 결과")
public record BenefitSearchResponse(
        @Schema(description = "조건에 맞는 전체 건수. 규모가 커지면 빠지거나 다음 쪽 여부로 바뀔 수 있으므로 의존하지 않는다(DEC-SEARCH).")
        long totalCount,
        @Schema(description = "요청한 쪽 번호. 1부터 센다.") int page,
        @Schema(description = "요청한 쪽 크기") int size,
        @Schema(description = "이 쪽의 항목. 마지막 쪽을 넘으면 빈 배열이다.") List<BenefitSummary> items) {
}
