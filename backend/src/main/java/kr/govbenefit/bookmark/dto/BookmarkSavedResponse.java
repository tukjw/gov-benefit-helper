package kr.govbenefit.bookmark.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 요청한 서비스 ID 중 북마크에 있는 것.
 *
 * @param serviceIds 요청 순서이며 중복이 없다.
 */
@Schema(description = "요청한 서비스 ID 중 북마크에 있는 것")
public record BookmarkSavedResponse(
        @Schema(description = "북마크에 있는 서비스 ID. 요청 순서이며 중복이 없다.", example = "[\"654000000031\"]")
        List<String> serviceIds) {
}
