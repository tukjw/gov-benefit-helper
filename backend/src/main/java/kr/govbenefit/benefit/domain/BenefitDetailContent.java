package kr.govbenefit.benefit.domain;

/**
 * 상세(serviceDetail)에서 받은 항목. 목록과 값이 같은 필드는 {@link BenefitContent}에만 둔다. 값은 원문 문자열 그대로다.
 */
public record BenefitDetailContent(
        String purpose,
        String applyMethod,
        String requiredDocuments,
        String officialCheckDocuments,
        String selfCheckDocuments,
        String contact,
        String onlineApplyUrl,
        String laws,
        String localLaws,
        String adminRules,
        String receptionAgencyName,
        String supportType) {
}
