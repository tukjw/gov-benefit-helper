package kr.govbenefit.collection.domain;

/**
 * 수집 실행 하나가 끝났다. 성공·부분 실패·실패 모두 낸다. 쪽마다 커밋하므로 실패한 실행도 혜택과 원문을 바꿨을 수 있다.
 */
public record CollectionRunFinished(Long runId, CollectionStatus status) {
}
