package kr.govbenefit.bookmark.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import kr.govbenefit.TestcontainersConfiguration;
import kr.govbenefit.account.SignUpSteps;
import kr.govbenefit.account.dto.SignUpRequest;
import kr.govbenefit.account.mail.RecordingAccountMailSender;
import kr.govbenefit.account.service.AccountService;
import kr.govbenefit.account.service.EmailVerificationService;
import kr.govbenefit.benefit.domain.ApplyPeriodRule;
import kr.govbenefit.benefit.domain.Benefit;
import kr.govbenefit.benefit.domain.BenefitContent;
import kr.govbenefit.benefit.domain.BenefitNormalization;
import kr.govbenefit.benefit.repository.BenefitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 같은 혜택의 동시 저장은 실제 MySQL의 {@code INSERT ... ON DUPLICATE KEY UPDATE}와 기본 키로 한 행만 남는다.
 */
@SpringBootTest(properties = "gov24.schedule.enabled=false")
@Import(TestcontainersConfiguration.class)
class BookmarkConcurrencyTest {

    private static final int THREADS = 8;
    private static final String SERVICE_ID = "RACE";

    @Autowired
    private BookmarkService bookmarkService;
    @Autowired
    private AccountService accountService;
    @Autowired
    private EmailVerificationService emailVerificationService;
    @Autowired
    private RecordingAccountMailSender mailSender;
    @Autowired
    private BenefitRepository benefitRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;

    @BeforeEach
    void setUp() {
        mailSender.clear();
        String ticket = SignUpSteps.verify(emailVerificationService, mailSender, "race@example.com");
        accountService.signUp(new SignUpRequest("race@example.com", "correct-horse", ticket));
        userId = jdbcTemplate.queryForObject("SELECT id FROM user_account WHERE email = ?", Long.class,
                "race@example.com");
        benefitRepository.save(Benefit.create(SERVICE_ID, new BenefitContent("동시 저장 혜택", null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null),
                new BenefitNormalization(null, null, ApplyPeriodRule.ALWAYS), LocalDateTime.now()));
    }

    @Test
    void 같은_혜택을_동시에_저장해도_모두_성공하고_한_건만_남는다() throws Exception {
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        List<Future<?>> results = new ArrayList<>();
        try {
            for (int i = 0; i < THREADS; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    bookmarkService.save(userId, SERVICE_ID);
                    return null;
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> result : results) {
                // 데드락이나 중복 키 오류가 나면 여기서 ExecutionException으로 실패한다.
                result.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bookmark WHERE user_id = ?", Integer.class,
                userId)).isEqualTo(1);
    }
}
