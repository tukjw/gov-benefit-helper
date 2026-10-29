package kr.govbenefit.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import kr.govbenefit.MutableClock;
import kr.govbenefit.account.config.LoginAttemptProperties;
import kr.govbenefit.account.service.LoginAttemptLimiter.Attempt;
import kr.govbenefit.common.api.RateLimitedException;
import org.junit.jupiter.api.Test;

class LoginAttemptLimiterTest {

    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int PER_EMAIL_AND_IP = 5;
    private static final int PER_IP = 30;
    private static final Instant START = Instant.parse("2026-10-29T01:00:00Z");
    private static final String ALICE = "alice@example.com";
    private static final String IP = "203.0.113.7";

    private final MutableClock clock = new MutableClock(START, ZoneId.of("Asia/Seoul"));
    private final LoginAttemptLimiter limiter = new LoginAttemptLimiter(
            new LoginAttemptProperties(WINDOW, PER_EMAIL_AND_IP, PER_IP), clock);

    @Test
    void 같은_이메일_IP로_5번_실패하면_가장_오래된_실패가_창을_벗어날_때까지_거절한다() {
        failTimes(ALICE, IP, 4);
        clock.advance(Duration.ofMinutes(5));
        limiter.reserve(ALICE, IP);

        assertRateLimited(ALICE, IP, Duration.ofMinutes(10));
        clock.advance(Duration.ofMinutes(10).minusNanos(1));
        assertRateLimited(ALICE, IP, Duration.ofNanos(1));
        clock.advance(Duration.ofNanos(1));
        limiter.reserve(ALICE, IP);
    }

    @Test
    void 거절된_시도는_실패로_세지_않는다() {
        failTimes(ALICE, IP, PER_EMAIL_AND_IP);
        for (int i = 0; i < 10; i++) {
            assertRateLimited(ALICE, IP, WINDOW);
        }

        clock.advance(WINDOW);
        failTimes(ALICE, IP, PER_EMAIL_AND_IP);
        assertRateLimited(ALICE, IP, WINDOW);
    }

    @Test
    void 같은_IP로_여러_이메일이_30번_실패하면_거절한다() {
        for (int i = 0; i < PER_IP; i++) {
            limiter.reserve("user" + i + "@example.com", IP);
        }

        assertRateLimited("new@example.com", IP, WINDOW);
        limiter.reserve("new@example.com", "198.51.100.1");
    }

    @Test
    void IP_한도에_차면_IP의_가장_오래된_실패를_기준으로_기다린다() {
        for (int i = 0; i < PER_IP; i++) {
            limiter.reserve("user" + i + "@example.com", IP);
        }
        clock.advance(Duration.ofMinutes(5));

        assertRateLimited(ALICE, IP, Duration.ofMinutes(10));
    }

    @Test
    void 두_기준이_모두_차면_더_긴_대기_시간을_준다() {
        for (int i = 0; i < PER_IP - PER_EMAIL_AND_IP; i++) {
            limiter.reserve("user" + i + "@example.com", IP);
        }
        clock.advance(Duration.ofMinutes(5));
        failTimes(ALICE, IP, PER_EMAIL_AND_IP);
        clock.advance(Duration.ofMinutes(1));

        // IP는 9분 뒤, (이메일+IP)는 14분 뒤에 빈다.
        assertRateLimited(ALICE, IP, Duration.ofMinutes(14));
    }

    @Test
    void 다른_IP의_같은_이메일은_영향이_없다() {
        failTimes(ALICE, IP, PER_EMAIL_AND_IP);

        limiter.reserve(ALICE, "198.51.100.1");
    }

    @Test
    void IPv6는_같은_64비트_대역을_같은_IP로_센다() {
        for (int i = 1; i <= PER_EMAIL_AND_IP; i++) {
            limiter.reserve(ALICE, "2001:db8:1:2::" + i);
        }

        assertRateLimited(ALICE, "2001:db8:1:2::ffff", WINDOW);
        limiter.reserve(ALICE, "2001:db8:1:3::1");
    }

    @Test
    void 성공하면_이메일_IP_기록은_지우고_IP_기록은_그_시도만_지운다() {
        for (int i = 0; i < PER_IP - 1; i++) {
            limiter.reserve("user" + i + "@example.com", IP);
        }
        Attempt success = limiter.reserve(ALICE, IP);
        limiter.succeeded(success);

        // IP 기록에는 다른 이메일의 실패 29번이 남아 한 번 더 받는다.
        limiter.reserve(ALICE, IP);
        assertRateLimited(ALICE, IP, WINDOW);
    }

    @Test
    void 성공하면_같은_이메일_IP의_실패는_다시_0부터_센다() {
        failTimes(ALICE, IP, PER_EMAIL_AND_IP - 1);
        limiter.succeeded(limiter.reserve(ALICE, IP));

        failTimes(ALICE, IP, PER_EMAIL_AND_IP);
        assertRateLimited(ALICE, IP, WINDOW);
    }

    @Test
    void 이메일_기록을_지우면_모든_IP에서_그_이메일의_제한이_풀리고_IP_기록은_남는다() {
        failTimes(ALICE, IP, PER_EMAIL_AND_IP);
        failTimes(ALICE, "198.51.100.1", PER_EMAIL_AND_IP);

        limiter.clearEmail(ALICE);

        limiter.reserve(ALICE, IP);
        limiter.reserve(ALICE, "198.51.100.1");
        for (int i = 0; i < PER_IP - PER_EMAIL_AND_IP - 1; i++) {
            limiter.reserve("user" + i + "@example.com", IP);
        }
        assertRateLimited("other@example.com", IP, WINDOW);
    }

    @Test
    void 창이_지난_기록은_주기_정리에서_지워진다() {
        failTimes(ALICE, IP, 2);
        clock.advance(WINDOW.minusSeconds(1));
        limiter.evictExpired();
        assertThat(limiter.isEmpty()).isFalse();

        clock.advance(Duration.ofSeconds(1));
        limiter.evictExpired();
        assertThat(limiter.isEmpty()).isTrue();
    }

    private void failTimes(String email, String ip, int times) {
        for (int i = 0; i < times; i++) {
            limiter.reserve(email, ip);
        }
    }

    private void assertRateLimited(String email, String ip, Duration retryAfter) {
        assertThatThrownBy(() -> limiter.reserve(email, ip))
                .isInstanceOfSatisfying(RateLimitedException.class,
                        ex -> assertThat(ex.retryAfter()).isEqualTo(retryAfter));
    }
}
