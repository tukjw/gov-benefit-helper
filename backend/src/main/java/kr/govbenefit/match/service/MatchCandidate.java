package kr.govbenefit.match.service;

import kr.govbenefit.benefit.dto.BenefitSummary;
import kr.govbenefit.match.domain.SupportConditions;
import kr.govbenefit.region.domain.AgencyRegion;

/**
 * 조건 비교 목록이 판정하는 혜택 하나. 프로필과 무관한 값만 담는다.
 *
 * @param summary      목록 항목에 그대로 담는 혜택 정보
 * @param userType     목록의 사용자구분 원문
 * @param agencyRegion 운영 기관 지역
 * @param conditions   지원조건을 수집하지 못했으면 null이다.
 */
public record MatchCandidate(BenefitSummary summary, String userType, AgencyRegion agencyRegion,
                             SupportConditions conditions) {
}
