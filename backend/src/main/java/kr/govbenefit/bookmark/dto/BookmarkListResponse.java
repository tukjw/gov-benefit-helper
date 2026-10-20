package kr.govbenefit.bookmark.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 북마크 목록의 한 쪽. 마지막으로 저장한 순서다.
 *
 * @param totalCount 전체 북마크 수
 * @param page       요청한 쪽 번호. 1부터 센다.
 * @param size       요청한 쪽 크기
 * @param items      이 쪽의 항목. 마지막 쪽을 넘으면 비어 있다.
 */
@Schema(description = "북마크 목록. 마지막으로 저장한 순서다.")
public record BookmarkListResponse(
        @Schema(description = "전체 북마크 수") long totalCount,
        @Schema(description = "요청한 쪽 번호. 1부터 센다.") int page,
        @Schema(description = "요청한 쪽 크기") int size,
        @Schema(description = "이 쪽의 항목. 마지막 쪽을 넘으면 빈 배열이다.") List<BookmarkItem> items) {
}
