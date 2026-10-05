package kr.govbenefit.benefit.dto;

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
public record BenefitSummary(
        String serviceId,
        String name,
        String summary,
        String agencyName,
        String agencyType,
        String field,
        String applyPeriodText,
        ApplyPeriodType applyPeriodType,
        Boolean applyPeriodNeedsReview,
        OffsetDateTime lastSeenAt) {

    public static BenefitSummary from(Benefit benefit) {
        return new BenefitSummary(benefit.getServiceId(), benefit.getName(), benefit.getPurposeSummary(),
                benefit.getAgencyName(), benefit.getAgencyType(), benefit.getServiceField(),
                benefit.getApplyPeriodText(), benefit.getApplyPeriodType(), benefit.getApplyPeriodNeedsReview(),
                ApiTimes.toOffset(benefit.getLastSeenAt()));
    }
}
