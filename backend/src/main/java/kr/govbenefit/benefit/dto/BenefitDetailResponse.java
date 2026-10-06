package kr.govbenefit.benefit.dto;

import java.time.OffsetDateTime;
import kr.govbenefit.benefit.domain.ApplyPeriodType;
import kr.govbenefit.benefit.domain.Benefit;
import kr.govbenefit.benefit.domain.BenefitDetail;
import kr.govbenefit.benefit.domain.OnlineApplyUrl;
import kr.govbenefit.common.api.ApiTimes;

/**
 * 혜택 상세(REQ-DETAIL-01). 최상위 필드는 목록(serviceList), {@code detail}은 상세(serviceDetail)에서 받은 값이다.
 * 값은 원문 그대로이며 없으면 null이다. 기본 문구로 채우지 않는다.
 *
 * @param summary          서비스 목적 요약
 * @param field            분야
 * @param applyMethodType  목록의 신청방법
 * @param receptionAgency  목록의 접수기관
 * @param phoneInquiry     목록의 전화문의
 * @param guideUrl         공식 출처(gov.kr 상세 페이지). 링크로 쓸 수 없으면 null이다.
 * @param sourceModifiedAt 출처 수정 시각. 원문에 없으면 null이며 수집 시각으로 채우지 않는다.
 * @param lastSeenAt       목록에서 마지막으로 확인한 시각. 상세 항목의 수집 시각이 아니다.
 * @param detail           상세 항목. 상세를 아직 받지 못했으면 null이다.
 */
public record BenefitDetailResponse(
        String serviceId,
        String name,
        String summary,
        String field,
        String agencyName,
        String agencyType,
        String departmentName,
        String supportType,
        String supportContent,
        String target,
        String selectionCriteria,
        ApplyPeriod applyPeriod,
        String applyMethodType,
        String receptionAgency,
        String phoneInquiry,
        String guideUrl,
        OffsetDateTime sourceModifiedAt,
        OffsetDateTime lastSeenAt,
        Detail detail,
        Freshness freshness) {

    public static BenefitDetailResponse of(Benefit benefit, BenefitDetail detail, Freshness freshness) {
        return new BenefitDetailResponse(benefit.getServiceId(), benefit.getName(), benefit.getPurposeSummary(),
                benefit.getServiceField(), benefit.getAgencyName(), benefit.getAgencyType(),
                benefit.getDepartmentName(), benefit.getSupportType(), benefit.getSupportContent(),
                benefit.getTarget(), benefit.getSelectionCriteria(),
                new ApplyPeriod(benefit.getApplyPeriodText(), benefit.getApplyPeriodType(),
                        benefit.getApplyPeriodNeedsReview()),
                benefit.getApplyMethodType(), benefit.getReceptionAgency(), benefit.getPhoneInquiry(),
                OnlineApplyUrl.toLink(benefit.getDetailUrl()), ApiTimes.toOffset(benefit.getSourceModifiedAt()),
                ApiTimes.toOffset(benefit.getLastSeenAt()), detail == null ? null : Detail.from(detail), freshness);
    }

    /**
     * 신청기한. {@code needsReview}가 true이면 원문을 함께 봐야 한다(결정 0008).
     *
     * @param text        원문
     * @param type        분류. 정규화 전에 수집된 행이면 null이다.
     * @param needsReview 확인 필요 여부. 정규화 전에 수집된 행이면 null이다.
     */
    public record ApplyPeriod(String text, ApplyPeriodType type, Boolean needsReview) {
    }

    /**
     * 상세에서 받은 항목.
     *
     * @param applyMethod        상세의 신청방법(접수 방법)
     * @param contact            상세의 문의처
     * @param onlineApplyUrl     링크로 쓸 수 있게 정리한 온라인 신청 주소(결정 0011). 원문이 없거나 링크로 쓸 수 없으면 null이다.
     * @param onlineApplyUrlText 온라인 신청 주소 원문
     */
    public record Detail(
            String purpose,
            String applyMethod,
            String requiredDocuments,
            String officialCheckDocuments,
            String selfCheckDocuments,
            String contact,
            String receptionAgencyName,
            String onlineApplyUrl,
            String onlineApplyUrlText) {

        static Detail from(BenefitDetail detail) {
            return new Detail(detail.getPurpose(), detail.getApplyMethod(), detail.getRequiredDocuments(),
                    detail.getOfficialCheckDocuments(), detail.getSelfCheckDocuments(), detail.getContact(),
                    detail.getReceptionAgencyName(), OnlineApplyUrl.toLink(detail.getOnlineApplyUrl()),
                    detail.getOnlineApplyUrl());
        }
    }
}
