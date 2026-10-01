package kr.govbenefit.benefit.domain;

/**
 * 혜택의 상세 수집 상태. 저장하지 않고 상세 행이 있는지로 정한다.
 */
public enum DetailStatus {
    /** 상세를 한 번 이상 받았다. 이후 상세 요청이 실패해도 이전 상세를 유지한다. */
    COLLECTED,
    /** 목록에는 있지만 상세를 아직 받지 못했다. */
    NOT_COLLECTED
}
