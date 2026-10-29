package kr.govbenefit.account.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import kr.govbenefit.TestcontainersConfiguration;
import kr.govbenefit.account.SignUpSteps;
import kr.govbenefit.account.dto.SignUpRequest;
import kr.govbenefit.account.mail.RecordingAccountMailSender;
import kr.govbenefit.account.repository.UserAccountRepository;
import kr.govbenefit.account.service.ConfirmOutcome.Rejected;
import kr.govbenefit.common.api.BusinessException;
import kr.govbenefit.common.api.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 인증번호의 동시 추측과 가입 티켓의 동시 사용은 실제 MySQL의 조건부 UPDATE로 막는다(결정 0019).
 */
@SpringBootTest(properties = "gov24.schedule.enabled=false")
@Import(TestcontainersConfiguration.class)
class EmailVerificationConcurrencyTest {

    private static final String EMAIL = "race@example.com";
    private static final int MAX_ATTEMPTS = 5;

    @Autowired
    private EmailVerificationService emailVerificationService;
    @Autowired
    private AccountService accountService;
    @Autowired
    private UserAccountRepository userAccountRepository;
    @Autowired
    private RecordingAccountMailSender mailSender;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        mailSender.clear();
    }

    @Test
    void 동시에_추측해도_비교는_제한_횟수까지만_한다() throws Exception {
        emailVerificationService.request(EMAIL);
        String code = mailSender.lastSignupCode(EMAIL);
        String wrong = String.format("%06d", (Integer.parseInt(code) + 1) % 1_000_000);

        List<ConfirmOutcome> outcomes = concurrently(20, () -> emailVerificationService.confirm(EMAIL, wrong));

        assertThat(outcomes).filteredOn(outcome -> outcome.equals(new Rejected(ErrorCode.VERIFICATION_CODE_MISMATCH)))
                .hasSize(MAX_ATTEMPTS);
        assertThat(outcomes).filteredOn(
                        outcome -> outcome.equals(new Rejected(ErrorCode.VERIFICATION_ATTEMPTS_EXCEEDED)))
                .hasSize(20 - MAX_ATTEMPTS);
        assertThat(jdbcTemplate.queryForObject("SELECT attempts FROM email_verification", Integer.class))
                .isEqualTo(MAX_ATTEMPTS);
        assertThat(emailVerificationService.confirm(EMAIL, code))
                .isEqualTo(new Rejected(ErrorCode.VERIFICATION_ATTEMPTS_EXCEEDED));
    }

    @Test
    void 같은_가입_티켓으로_동시에_가입해도_계정은_하나다() throws Exception {
        String ticket = SignUpSteps.verify(emailVerificationService, mailSender, EMAIL);

        List<Object> outcomes = concurrently(8, () -> {
            try {
                return accountService.signUp(new SignUpRequest(EMAIL, "correct-horse", ticket));
            } catch (BusinessException ex) {
                return ex.code();
            }
        });

        assertThat(outcomes).filteredOn(outcome -> !(outcome instanceof ErrorCode)).hasSize(1);
        // 티켓 사용 표시(조건부 UPDATE)에서 진 요청과, 이긴 요청이 커밋한 뒤 티켓을 검사한 요청 모두 같은 거절이다.
        assertThat(outcomes).filteredOn(outcome -> outcome instanceof ErrorCode)
                .containsOnly(ErrorCode.EMAIL_NOT_VERIFIED);
        assertThat(userAccountRepository.count()).isEqualTo(1);
    }

    private static <T> List<T> concurrently(int threads, Callable<T> task) throws Exception {
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }
}
