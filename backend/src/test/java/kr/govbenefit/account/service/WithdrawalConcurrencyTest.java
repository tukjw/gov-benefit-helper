package kr.govbenefit.account.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import kr.govbenefit.TestcontainersConfiguration;
import kr.govbenefit.account.SignUpSteps;
import kr.govbenefit.account.dto.SignUpRequest;
import kr.govbenefit.account.mail.RecordingAccountMailSender;
import kr.govbenefit.common.api.BusinessException;
import kr.govbenefit.common.api.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 같은 사용자의 동시 탈퇴는 실제 MySQL의 계정 행 잠금으로 하나만 성공한다.
 */
@SpringBootTest(properties = "gov24.schedule.enabled=false")
@Import(TestcontainersConfiguration.class)
class WithdrawalConcurrencyTest {

    private static final int THREADS = 2;

    @Autowired
    private AccountService accountService;
    @Autowired
    private EmailVerificationService emailVerificationService;
    @Autowired
    private RecordingAccountMailSender mailSender;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;

    @BeforeEach
    void setUp() {
        mailSender.clear();
        String ticket = SignUpSteps.verify(emailVerificationService, mailSender, "race@example.com");
        accountService.signUp(new SignUpRequest("race@example.com", "correct-horse", ticket));
        userId = jdbcTemplate.queryForObject("SELECT id FROM user_account", Long.class);
    }

    @Test
    void 같은_사용자가_동시에_탈퇴하면_하나만_성공하고_나머지는_인증되지_않은_요청이다() throws Exception {
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        List<Future<?>> results = new ArrayList<>();
        int succeeded = 0;
        List<ErrorCode> failed = new ArrayList<>();
        try {
            for (int i = 0; i < THREADS; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    accountService.withdraw(userId, "correct-horse", "127.0.0.1");
                    return null;
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> result : results) {
                try {
                    result.get(30, TimeUnit.SECONDS);
                    succeeded++;
                } catch (ExecutionException ex) {
                    assertThat(ex.getCause()).isInstanceOf(BusinessException.class);
                    failed.add(((BusinessException) ex.getCause()).code());
                }
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(succeeded).isEqualTo(1);
        assertThat(failed).containsExactly(ErrorCode.UNAUTHENTICATED);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM user_account", Integer.class)).isZero();
    }
}
