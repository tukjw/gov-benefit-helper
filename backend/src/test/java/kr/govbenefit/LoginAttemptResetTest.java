package kr.govbenefit;

import static org.assertj.core.api.Assertions.assertThat;

import kr.govbenefit.account.service.LoginAttemptLimiter;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 메모리에 있는 로그인 시도 제한 기록도 테스트 메서드마다 비워진다(결정 0033·0035).
 */
@SpringBootTest(properties = "gov24.schedule.enabled=false")
@Import(TestcontainersConfiguration.class)
class LoginAttemptResetTest {

    @Autowired
    private LoginAttemptLimiter limiter;

    @RepeatedTest(2)
    void 테스트마다_시도_제한_기록이_비어_있다() {
        assertThat(limiter.isEmpty()).isTrue();

        limiter.reserve("alice@example.com", "203.0.113.7");
        assertThat(limiter.isEmpty()).isFalse();
    }
}
