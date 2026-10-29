package kr.govbenefit.collection.client;

import kr.govbenefit.collection.domain.CollectionErrorType;

/**
 * 공공서비스 API 호출 실패. 메시지에는 경로·쪽·상태 코드와 응답의 code·msg만 담고 요청 헤더(인증키)는 담지 않는다.
 */
public class Gov24ApiException extends RuntimeException {

    private final CollectionErrorType type;
    private final int attempts;

    public Gov24ApiException(CollectionErrorType type, String message) {
        this(type, message, null, 0);
    }

    public Gov24ApiException(CollectionErrorType type, String message, Throwable cause) {
        this(type, message, cause, 0);
    }

    private Gov24ApiException(CollectionErrorType type, String message, Throwable cause, int attempts) {
        super(message, cause);
        this.type = type;
        this.attempts = attempts;
    }

    public CollectionErrorType type() {
        return type;
    }

    /**
     * 이 쪽에 보낸 HTTP 시도 횟수. 예산이 남지 않아 한 번도 보내지 못했으면 0이다.
     */
    public int attempts() {
        return attempts;
    }

    Gov24ApiException withAttempts(int attempts) {
        return new Gov24ApiException(type, getMessage(), getCause(), attempts);
    }
}
