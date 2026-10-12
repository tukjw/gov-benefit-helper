package kr.govbenefit.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import kr.govbenefit.TestcontainersConfiguration;
import kr.govbenefit.account.domain.UserAccount;
import kr.govbenefit.account.dto.SignUpRequest;
import kr.govbenefit.account.repository.UserAccountRepository;
import kr.govbenefit.common.api.BusinessException;
import kr.govbenefit.common.api.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 같은 이메일의 동시 가입은 실제 MySQL unique 제약으로 하나만 성공한다(설계 3).
 */
@SpringBootTest(properties = "gov24.schedule.enabled=false")
@Import(TestcontainersConfiguration.class)
class SignUpConcurrencyTest {

    private static final int THREADS = 8;

    @Autowired
    private AccountService accountService;
    @Autowired
    private UserAccountRepository userAccountRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM user_account");
    }

    @Test
    void 같은_이메일로_동시에_가입해도_계정은_하나다() throws Exception {
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        List<Future<Object>> results = new ArrayList<>();
        try {
            for (int i = 0; i < THREADS; i++) {
                // 대소문자를 섞어 정규화 뒤에 같은 이메일이 되는 경우도 함께 본다.
                String email = i % 2 == 0 ? "race@example.com" : "RACE@example.com";
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        return accountService.signUp(new SignUpRequest(email, "correct-horse"));
                    } catch (BusinessException ex) {
                        return ex;
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            int succeeded = 0;
            for (Future<Object> result : results) {
                Object outcome = result.get(30, TimeUnit.SECONDS);
                if (outcome instanceof BusinessException ex) {
                    assertThat(ex.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.code()).isEqualTo(ErrorCode.DUPLICATE_EMAIL);
                } else {
                    succeeded++;
                }
            }
            assertThat(succeeded).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
        assertThat(userAccountRepository.count()).isEqualTo(1);
    }

    /**
     * 선조회를 함께 통과한 경합은 매번 재현되지 않는다. 그 경로가 쓰는 제약 위반 판별이 실제 MySQL 예외에서 맞는지 따로 확인한다.
     */
    @Test
    void 이메일_unique_제약_위반을_구분한다() {
        userAccountRepository.saveAndFlush(UserAccount.create("dup@example.com", "{noop}x", LocalDateTime.now()));

        assertThatThrownBy(() -> userAccountRepository.saveAndFlush(
                UserAccount.create("dup@example.com", "{noop}y", LocalDateTime.now())))
                .isInstanceOfSatisfying(DataIntegrityViolationException.class,
                        ex -> assertThat(AccountService.isEmailUniqueViolation(ex)).isTrue());
        assertThat(AccountService.isEmailUniqueViolation(
                new DataIntegrityViolationException("Column 'email' cannot be null"))).isFalse();
    }
}
