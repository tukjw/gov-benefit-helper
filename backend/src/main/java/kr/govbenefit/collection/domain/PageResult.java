package kr.govbenefit.collection.domain;

/**
 * 한 쪽을 저장한 결과 건수.
 *
 * @param received    받은 레코드 수(격리 포함)
 * @param created     새로 저장한 원문 수
 * @param changed     내용이 바뀌어 이력을 남긴 원문 수
 * @param unchanged   내용이 같은 원문 수(조회수만 바뀐 경우 포함)
 * @param quarantined 필수 식별자가 없어 격리한 레코드 수
 * @param unlinked    목록에 없는 서비스ID라 혜택에 연결하지 못한 레코드 수
 */
public record PageResult(int received, int created, int changed, int unchanged, int quarantined, int unlinked) {
}
