package kr.govbenefit.benefit.domain;

import java.time.LocalDateTime;

/**
 * 목록(serviceList) 원문을 해석한 값. 근거 원문은 같은 서비스ID의 목록 원문(source_record)이고, 항목마다 원문 필드가 하나로 정해져 있다.
 *
 * @param sourceRegisteredAt 원문 {@code 등록일시}. 없거나 해석할 수 없으면 null이며 수집 시각으로 채우지 않는다.
 * @param sourceModifiedAt   원문 {@code 수정일시}. 없거나 해석할 수 없으면 null이며 수집 시각으로 채우지 않는다.
 * @param applyPeriodRule    원문 {@code 신청기한}을 분류한 규칙
 */
public record BenefitNormalization(
        LocalDateTime sourceRegisteredAt,
        LocalDateTime sourceModifiedAt,
        ApplyPeriodRule applyPeriodRule) {
}
