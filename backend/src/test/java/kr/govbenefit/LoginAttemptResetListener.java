package kr.govbenefit;

import kr.govbenefit.account.service.LoginAttemptLimiter;
import org.springframework.core.Ordered;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

/**
 * 테스트 메서드마다 로그인 시도 제한 기록을 비운다(결정 0033·0035). 기록은 앱 메모리에 있어 DB 정리로 지워지지 않고, 테스트는
 * 컨텍스트를 공유하며 대부분 같은 주소(127.0.0.1)로 요청한다. 비우지 않으면 다른 테스트의 실패가 IP 한도에 쌓인다.
 * META-INF/spring.factories로 모든 Spring 테스트에 등록되고, 테스트 클래스의 {@code @BeforeEach}보다 먼저 돈다.
 */
public class LoginAttemptResetListener extends AbstractTestExecutionListener {

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void beforeTestMethod(TestContext testContext) {
        LoginAttemptLimiter limiter = testContext.getApplicationContext()
                .getBeanProvider(LoginAttemptLimiter.class).getIfAvailable();
        if (limiter != null) {
            limiter.clear();
        }
    }
}
