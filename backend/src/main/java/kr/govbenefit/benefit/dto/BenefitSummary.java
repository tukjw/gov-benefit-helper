package kr.govbenefit.benefit.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import kr.govbenefit.benefit.domain.ApplyPeriodType;
import kr.govbenefit.benefit.domain.Benefit;
import kr.govbenefit.common.api.ApiTimes;

/**
 * 검색 결과 항목. 값은 원문 그대로이며 없으면 null이다. 기본 문구로 채우지 않는다.
 *
 * @param summary                서비스 목적 요약
 * @param field                  분야
 * @param applyPeriodType        신청기한 분류. 정규화 전에 수집된 행이면 null이다.
 * @param applyPeriodNeedsReview 신청기한 원문을 함께 봐야 하는지(결정 0008)
 * @param lastSeenAt             목록에서 마지막으로 확인한 시각. 목록에서 사라진 혜택은 이 값이 멈춘다.
 */
@Schema(description = "검색 결과 항목. 값은 원문 그대로이며 없으면 `null`이다. '정보 없음' 표시는 클라이언트가 한다.")
public record BenefitSummary(
        @Schema(description = "서비스 ID. 상세 조회에 쓴다.", example = "654000000031") String serviceId,
        @Schema(description = "혜택 이름") String name,
        @Schema(description = "서비스 목적 요약") String summary,
        @Schema(description = "소관기관명") String agencyName,
        @Schema(description = "운영 기관 유형", example = "시군구") String agencyType,
        @Schema(description = "분야", example = "생활안정") String field,
        @Schema(description = "신청기한 원문") String applyPeriodText,
        @Schema(description = "신청기한 분류. 정규화 전에 수집된 혜택이면 `null`이다.") ApplyPeriodType applyPeriodType,
        @Schema(description = BenefitDocs.NEEDS_REVIEW) Boolean applyPeriodNeedsReview,
        @Schema(description = BenefitDocs.LAST_SEEN_AT, example = ApiTimes.EXAMPLE) OffsetDateTime lastSeenAt) {

    public static BenefitSummary from(Benefit benefit) {
        return new BenefitSummary(benefit.getServiceId(), benefit.getName(), benefit.getPurposeSummary(),
                benefit.getAgencyName(), benefit.getAgencyType(), benefit.getServiceField(),
                benefit.getApplyPeriodText(), benefit.getApplyPeriodType(), benefit.getApplyPeriodNeedsReview(),
                ApiTimes.toOffset(benefit.getLastSeenAt()));
    }
}
