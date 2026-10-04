package kr.govbenefit.benefit.dto;

import java.util.List;

/**
 * 혜택 검색 결과.
 *
 * @param totalCount 조건에 맞는 전체 건수. 규모가 커지면 빼거나 다음 쪽 여부로 바꿀 수 있는 항목이다(DEC-SEARCH).
 * @param page       요청한 쪽 번호. 1부터 센다.
 * @param size       요청한 쪽 크기
 * @param items      이 쪽의 항목. 마지막 쪽을 넘으면 비어 있다.
 */
public record BenefitSearchResponse(long totalCount, int page, int size, List<BenefitSummary> items) {
}
