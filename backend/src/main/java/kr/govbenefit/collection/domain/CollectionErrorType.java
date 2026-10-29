package kr.govbenefit.collection.domain;

/**
 * 수집 실패 유형(결정 0006). {@code API_} 접두어는 공공 API 오류다.
 */
public enum CollectionErrorType {

    /** 연결·응답 시간 제한 초과. */
    API_TIMEOUT(true, false),
    /** 연결 실패, 5xx. */
    API_UNAVAILABLE(true, false),
    /** 200인데 data 없음, 본문 해석 불가, 401·403·429 외 4xx. */
    API_BAD_RESPONSE(false, false),
    /** 계산한 마지막 쪽 전에 빈 data가 왔다. */
    API_TRUNCATED(false, false),
    /** 401, 403. 이후 요청도 모두 실패하므로 실행을 중단한다. */
    API_AUTH(false, true),
    /** 429. 한도가 풀릴 때까지 기다리지 않고 실행을 중단한다. */
    API_RATE_LIMIT(false, true),
    /** 다음 시도가 실행 호출 예산을 넘긴다. */
    BUDGET_EXCEEDED(false, true),
    /** 쪽 저장 중 DB 오류. */
    DB_ERROR(false, false),
    /** 위에 해당하지 않는 예외. 실행을 실패로 끝낸다. */
    INTERNAL(false, true);

    private final boolean retryable;
    private final boolean abortsRun;

    CollectionErrorType(boolean retryable, boolean abortsRun) {
        this.retryable = retryable;
        this.abortsRun = abortsRun;
    }

    public boolean retryable() {
        return retryable;
    }

    public boolean abortsRun() {
        return abortsRun;
    }
}
