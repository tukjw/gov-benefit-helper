package kr.govbenefit.benefit.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = """
        혜택 상세(REQ-DETAIL-01). 최상위 필드는 목록에서, `detail`은 상세에서 받은 값이다. \
        값은 원문 그대로이며, 필드가 `null`이면 원문에 값이 없다는 뜻이다. \
        서버는 기본 문구로 채우지 않으므로 '정보 없음' 표시는 클라이언트가 한다.""")
public record BenefitDetailResponse(
        @Schema(description = "서비스 ID", example = "654000000031") String serviceId,
        @Schema(description = "혜택 이름") String name,
        @Schema(description = "서비스 목적 요약") String summary,
        @Schema(description = "분야", example = "생활안정") String field,
        @Schema(description = "소관기관명") String agencyName,
        @Schema(description = "운영 기관 유형", example = "시군구") String agencyType,
        @Schema(description = "부서명") String departmentName,
        @Schema(description = "지원 유형") String supportType,
        @Schema(description = "지원 내용") String supportContent,
        @Schema(description = "지원 대상") String target,
        @Schema(description = "선정 기준") String selectionCriteria,
        @Schema(description = "신청기한") ApplyPeriod applyPeriod,
        @Schema(description = "목록의 신청방법") String applyMethodType,
        @Schema(description = "목록의 접수기관") String receptionAgency,
        @Schema(description = "목록의 전화문의") String phoneInquiry,
        @Schema(description = "공식 출처(gov.kr 상세 페이지). 링크로 쓸 수 없으면 `null`이다.",
                example = "https://www.gov.kr/portal/rcvfvrSvc/dtlEx/654000000031")
        String guideUrl,
        @Schema(description = """
                출처(공공서비스 API)가 알려 준 수정 시각. 수집 시각(`lastSeenAt`)과 다르다. \
                원문에 없으면 `null`이며 수집 시각으로 채우지 않는다.""", example = ApiTimes.EXAMPLE)
        OffsetDateTime sourceModifiedAt,
        @Schema(description = BenefitDocs.LAST_SEEN_AT + " 상세 항목(`detail`)을 받은 시각도 아니다.",
                example = ApiTimes.EXAMPLE)
        OffsetDateTime lastSeenAt,
        @Schema(description = """
                상세에서 받은 항목. `null`이면 상세를 아직 수집하지 못한 것이다. \
                객체 안의 필드가 `null`인 것(원문에 값이 없음)과 구분한다.""")
        Detail detail) {

    public static BenefitDetailResponse of(Benefit benefit, BenefitDetail detail) {
        return new BenefitDetailResponse(benefit.getServiceId(), benefit.getName(), benefit.getPurposeSummary(),
                benefit.getServiceField(), benefit.getAgencyName(), benefit.getAgencyType(),
                benefit.getDepartmentName(), benefit.getSupportType(), benefit.getSupportContent(),
                benefit.getTarget(), benefit.getSelectionCriteria(),
                new ApplyPeriod(benefit.getApplyPeriodText(), benefit.getApplyPeriodType(),
                        benefit.getApplyPeriodNeedsReview()),
                benefit.getApplyMethodType(), benefit.getReceptionAgency(), benefit.getPhoneInquiry(),
                OnlineApplyUrl.toLink(benefit.getDetailUrl()), ApiTimes.toOffset(benefit.getSourceModifiedAt()),
                ApiTimes.toOffset(benefit.getLastSeenAt()), detail == null ? null : Detail.from(detail));
    }

    /**
     * 신청기한. {@code needsReview}가 true이면 원문을 함께 봐야 한다(결정 0008).
     *
     * @param text        원문
     * @param type        분류. 정규화 전에 수집된 행이면 null이다.
     * @param needsReview 확인 필요 여부. 정규화 전에 수집된 행이면 null이다.
     */
    @Schema(description = "신청기한")
    public record ApplyPeriod(
            @Schema(description = "신청기한 원문") String text,
            @Schema(description = "분류. 정규화 전에 수집된 혜택이면 `null`이다.") ApplyPeriodType type,
            @Schema(description = BenefitDocs.NEEDS_REVIEW) Boolean needsReview) {
    }

    /**
     * 상세에서 받은 항목.
     *
     * @param applyMethod        상세의 신청방법(접수 방법)
     * @param contact            상세의 문의처
     * @param onlineApplyUrl     링크로 쓸 수 있게 정리한 온라인 신청 주소(결정 0011). 원문이 없거나 링크로 쓸 수 없으면 null이다.
     * @param onlineApplyUrlText 온라인 신청 주소 원문
     */
    @Schema(description = "상세에서 받은 항목. 필드가 `null`이면 원문에 값이 없다.")
    public record Detail(
            @Schema(description = "서비스 목적") String purpose,
            @Schema(description = "상세의 신청방법(접수 방법)") String applyMethod,
            @Schema(description = "구비서류") String requiredDocuments,
            @Schema(description = "공무원이 확인할 서류") String officialCheckDocuments,
            @Schema(description = "본인이 확인할 서류") String selfCheckDocuments,
            @Schema(description = "상세의 문의처") String contact,
            @Schema(description = "접수기관명") String receptionAgencyName,
            @Schema(description = """
                    링크로 쓸 수 있게 정리한 온라인 신청 주소(결정 0011). 스킴이 없던 주소에는 `https://`를 붙인다. \
                    원문이 없거나 링크로 쓸 수 없으면 `null`이며, 이때는 링크 대신 접수 방법(`detail.applyMethod`)을 보여준다.""",
                    example = "https://www.bokjiro.go.kr")
            String onlineApplyUrl,
            @Schema(description = "온라인 신청 주소 원문. 링크로 쓸 수 없는 값도 그대로 있다.") String onlineApplyUrlText) {

        static Detail from(BenefitDetail detail) {
            return new Detail(detail.getPurpose(), detail.getApplyMethod(), detail.getRequiredDocuments(),
                    detail.getOfficialCheckDocuments(), detail.getSelfCheckDocuments(), detail.getContact(),
                    detail.getReceptionAgencyName(), OnlineApplyUrl.toLink(detail.getOnlineApplyUrl()),
                    detail.getOnlineApplyUrl());
        }
    }
}
