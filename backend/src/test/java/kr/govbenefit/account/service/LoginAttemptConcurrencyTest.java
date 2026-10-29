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
import kr.govbenefit.account.dto.LoginRequest;
import kr.govbenefit.account.dto.SignUpRequest;
import kr.govbenefit.account.mail.RecordingAccountMailSender;
import kr.govbenefit.common.api.BusinessException;
import kr.govbenefit.common.api.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 같은 이메일·IP로 틀린 비밀번호를 동시에 보내도 비밀번호를 비교하는 요청은 한도 수를 넘지 않는다(결정 0035). 시도를 비교 전에
 * 기록하지 않으면 BCrypt 비교 동안 들어온 요청이 모두 확인을 통과한다.
 */
@SpringBootTest(properties = "gov24.schedule.enabled=false")
@Import(TestcontainersConfiguration.class)
class LoginAttemptConcurrencyTest {

    private static final int PER_EMAIL_AND_IP = 5;
    private static final int THREADS = 20;

    @Autowired
    private AccountService accountService;
    @Autowired
    private EmailVerificationService emailVerificationService;
    @Autowired
    private RecordingAccountMailSender mailSender;

    @BeforeEach
    void setUp() {
        mailSender.clear();
        String ticket = SignUpSteps.verify(emailVerificationService, mailSender, "race@example.com");
        accountService.signUp(new SignUpRequest("race@example.com", "correct-horse", ticket));
    }

    @Test
    void 동시에_틀린_비밀번호를_보내도_한도_수만큼만_비교하고_나머지는_429다() throws Exception {
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        List<Future<?>> results = new ArrayList<>();
        List<ErrorCode> failed = new ArrayList<>();
        try {
            for (int i = 0; i < THREADS; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    accountService.login(new LoginRequest("race@example.com", "wrong-password"), "203.0.113.7");
                    return null;
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> result : results) {
                try {
                    result.get(30, TimeUnit.SECONDS);
                } catch (ExecutionException ex) {
                    assertThat(ex.getCause()).isInstanceOf(BusinessException.class);
                    failed.add(((BusinessException) ex.getCause()).code());
                }
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(failed).hasSize(THREADS);
        assertThat(failed).filteredOn(code -> code == ErrorCode.INVALID_CREDENTIALS).hasSize(PER_EMAIL_AND_IP);
        assertThat(failed).filteredOn(code -> code == ErrorCode.TOO_MANY_REQUESTS)
                .hasSize(THREADS - PER_EMAIL_AND_IP);
    }
}
