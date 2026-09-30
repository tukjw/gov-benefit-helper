package kr.govbenefit.benefit.domain;

/**
 * 목록(serviceList)에서 받은 혜택 기본 항목. 값은 원문 문자열 그대로다.
 */
public record BenefitContent(
        String name,
        String purposeSummary,
        String serviceField,
        String userType,
        String supportType,
        String target,
        String selectionCriteria,
        String supportContent,
        String applyPeriodText,
        String applyMethodType,
        String phoneInquiry,
        String receptionAgency,
        String agencyName,
        String agencyType,
        String agencyCode,
        String departmentName,
        String detailUrl) {
}
