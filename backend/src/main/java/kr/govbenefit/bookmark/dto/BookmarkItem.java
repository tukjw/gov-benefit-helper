package kr.govbenefit.bookmark.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import kr.govbenefit.benefit.dto.BenefitSummary;
import kr.govbenefit.common.api.ApiTimes;

/**
 * 북마크 목록 항목. 혜택 정보는 저장할 때의 값이 아니라 지금 저장된 값이다(REQ-SAVE-01).
 *
 * @param bookmarkedAt 마지막으로 저장한 시각
 * @param benefit      혜택의 현재 정보
 */
@Schema(description = "북마크 목록 항목")
public record BookmarkItem(
        @Schema(description = "마지막으로 저장한 시각. 다시 저장하면 바뀐다.", example = ApiTimes.EXAMPLE)
        OffsetDateTime bookmarkedAt,
        @Schema(description = "혜택의 현재 정보. 저장한 뒤 다시 수집되어 바뀌면 바뀐 값이다. 목록에서 사라진 혜택도 숨기지 않으며 "
                + "`lastSeenAt`이 멈춘다.") BenefitSummary benefit) {
}
