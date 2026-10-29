package kr.govbenefit.account.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import kr.govbenefit.TestcontainersConfiguration;
import kr.govbenefit.account.SignUpSteps;
import kr.govbenefit.account.dto.LoginRequest;
import kr.govbenefit.account.dto.SignUpRequest;
import kr.govbenefit.account.mail.RecordingAccountMailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 같은 리프레시 토큰의 동시 재발급은 실제 MySQL의 조건부 UPDATE로 하나만 성공한다(결정 0017).
 */
@SpringBootTest(properties = "gov24.schedule.enabled=false")
@Import(TestcontainersConfiguration.class)
class RefreshTokenConcurrencyTest {

    private static final int THREADS = 8;

    @Autowired
    private AccountService accountService;
    @Autowired
    private EmailVerificationService emailVerificationService;
    @Autowired
    private RecordingAccountMailSender mailSender;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        mailSender.clear();
        String ticket = SignUpSteps.verify(emailVerificationService, mailSender, "race@example.com");
        accountService.signUp(new SignUpRequest("race@example.com", "correct-horse", ticket));
    }

    @Test
    void 같은_토큰으로_동시에_재발급해도_하나만_성공하고_이긴_토큰은_폐기되지_않는다() throws Exception {
        String refreshToken = accountService.login(new LoginRequest("race@example.com", "correct-horse"), "127.0.0.1").refreshToken();

        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        List<Future<Optional<AuthTokens>>> results = new ArrayList<>();
        List<AuthTokens> succeeded = new ArrayList<>();
        try {
            for (int i = 0; i < THREADS; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return accountService.refresh(refreshToken);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<Optional<AuthTokens>> result : results) {
                result.get(30, TimeUnit.SECONDS).ifPresent(succeeded::add);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(succeeded).hasSize(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM refresh_token", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_token WHERE rotated_at IS NOT NULL", Integer.class)).isEqualTo(1);
        // 진 요청은 교체 직후의 재사용이라 같은 로그인을 폐기하지 않는다.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_token WHERE revoked_at IS NOT NULL", Integer.class)).isZero();
        assertThat(accountService.refresh(succeeded.getFirst().refreshToken())).isPresent();
    }
}
