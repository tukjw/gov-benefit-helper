package kr.govbenefit.bookmark.dto;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import kr.govbenefit.benefit.dto.BenefitSearchRequest;

/**
 * 북마크 목록의 쪽 번호와 크기. 범위와 기본값은 혜택 검색과 같다(DEC-SEARCH).
 *
 * @param page 1부터 센 쪽 번호. 없으면 1이다.
 * @param size 쪽 크기. 없으면 20이다.
 */
public record BookmarkListRequest(
        @Parameter(description = "1부터 센 쪽 번호", schema = @Schema(implementation = Integer.class, defaultValue = "1"))
        @Min(value = 1, message = BookmarkListRequest.PAGE_RANGE) Integer page,
        @Parameter(description = "쪽 크기", schema = @Schema(implementation = Integer.class, defaultValue = "20"))
        @Min(value = 1, message = BookmarkListRequest.SIZE_RANGE)
        @Max(value = BenefitSearchRequest.MAX_SIZE, message = BookmarkListRequest.SIZE_RANGE) Integer size) {

    static final String PAGE_RANGE = "1 이상이어야 합니다.";
    static final String SIZE_RANGE = "1 이상 100 이하여야 합니다.";

    public BookmarkListRequest {
        page = page == null ? 1 : page;
        size = size == null ? BenefitSearchRequest.DEFAULT_SIZE : size;
    }
}
