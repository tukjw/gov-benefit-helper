package kr.govbenefit.benefit.dto;

/**
 * 검색·상세 응답이 함께 쓰는 API 명세 설명(결정 0012).
 */
final class BenefitDocs {

    static final String NEEDS_REVIEW = "신청기한 확인 필요 여부(결정 0008). `true`이면 '확인 필요'로 표시하고 원문을 함께 보여준다. "
            + "정규화 전에 수집된 혜택이면 `null`이다.";
    static final String LAST_SEEN_AT = "목록에서 마지막으로 확인한 수집 시각. 출처 수정 시각이 아니다. "
            + "목록에서 사라진 혜택은 이 값이 멈춘다.";

    private BenefitDocs() {
    }
}
