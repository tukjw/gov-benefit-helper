package kr.govbenefit.common.api;

import java.time.Duration;

/**
 * 요청 횟수 제한에 걸렸다. 응답은 429 {@code TOO_MANY_REQUESTS}이고 {@code Retry-After} 헤더로 다시 시도할 수 있을 때까지의
 * 초를 준다(결정 0009·0019).
 */
public class RateLimitedException extends BusinessException {

    private final Duration retryAfter;

    public RateLimitedException(Duration retryAfter) {
        super(ErrorCode.TOO_MANY_REQUESTS, "retryAfter=" + retryAfter);
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }

    /**
     * {@code Retry-After}에 넣을 초. 소수점은 올리고, 최소 1초다. 0초를 주면 클라이언트가 곧바로 다시 보내 또 거절된다.
     */
    public long retryAfterSeconds() {
        long seconds = retryAfter.toSeconds();
        if (retryAfter.toNanosPart() > 0) {
            seconds++;
        }
        return Math.max(1, seconds);
    }
}
