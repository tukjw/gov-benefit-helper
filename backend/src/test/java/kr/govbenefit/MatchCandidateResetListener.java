package kr.govbenefit;

import kr.govbenefit.match.service.MatchCandidateStore;
import org.springframework.core.Ordered;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

/**
 * 테스트 메서드마다 조건 비교 목록의 판정 입력을 비운다(결정 0033·0038). 판정 입력은 앱 메모리에 있어 DB 정리로 지워지지 않고,
 * 테스트는 컨텍스트를 공유한다. 비우지 않으면 앞 테스트의 혜택으로 판정한다.
 * META-INF/spring.factories로 모든 Spring 테스트에 등록되고, 테스트 클래스의 {@code @BeforeEach}보다 먼저 돈다.
 */
public class MatchCandidateResetListener extends AbstractTestExecutionListener {

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void beforeTestMethod(TestContext testContext) {
        MatchCandidateStore store = testContext.getApplicationContext()
                .getBeanProvider(MatchCandidateStore.class).getIfAvailable();
        if (store != null) {
            store.clear();
        }
    }
}
