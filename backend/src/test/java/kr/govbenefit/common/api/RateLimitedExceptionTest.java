package kr.govbenefit.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class RateLimitedExceptionTest {

    @Test
    void 응답은_429_TOO_MANY_REQUESTS다() {
        RateLimitedException ex = new RateLimitedException(Duration.ofSeconds(3));

        assertThat(ex.code()).isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
        assertThat(ex.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void Retry_After는_초를_올리고_최소_1초다() {
        assertThat(new RateLimitedException(Duration.ofSeconds(42)).retryAfterSeconds()).isEqualTo(42);
        assertThat(new RateLimitedException(Duration.ofMillis(41_001)).retryAfterSeconds()).isEqualTo(42);
        assertThat(new RateLimitedException(Duration.ofMillis(1)).retryAfterSeconds()).isEqualTo(1);
        assertThat(new RateLimitedException(Duration.ZERO).retryAfterSeconds()).isEqualTo(1);
    }
}
