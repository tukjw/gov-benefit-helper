package kr.govbenefit.profile.service;

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
import kr.govbenefit.account.domain.UserAccount;
import kr.govbenefit.account.repository.UserAccountRepository;
import kr.govbenefit.profile.domain.HouseholdAttribute;
import kr.govbenefit.profile.dto.ProfileRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 프로필이 없는 사용자의 첫 저장이 동시에 와도 계정 행 잠금으로 직렬화되어 행이 하나만 생긴다(#49 설계 8).
 */
@SpringBootTest(properties = "gov24.schedule.enabled=false")
@Import(TestcontainersConfiguration.class)
class ProfileConcurrencyTest {

    private static final int THREADS = 8;

    @Autowired
    private ProfileService profileService;
    @Autowired
    private UserAccountRepository userAccountRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM user_account");
        userId = userAccountRepository.save(UserAccount.create("alice@example.com", "{noop}x", LocalDateTime.now()))
                .getId();
    }

    @Test
    void 첫_저장이_동시에_와도_모두_성공하고_행은_하나다() throws Exception {
        HouseholdAttribute[] values = HouseholdAttribute.values();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < THREADS; i++) {
                HouseholdAttribute attribute = values[i];
                futures.add(executor.submit(() -> {
                    start.await();
                    return profileService.put(userId, new ProfileRequest(null, null, null, null, null,
                            List.of(attribute.name())));
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM user_profile", Integer.class)).isEqualTo(1);
        // 나중 요청이 덮어써 속성은 한 요청의 값 하나만 남는다.
        List<String> codes = jdbcTemplate.queryForList(
                "SELECT condition_code FROM user_profile_attribute WHERE user_id = ?", String.class, userId);
        assertThat(codes).hasSize(1);
        assertThat(codes.getFirst()).isIn(List.of(values).subList(0, THREADS).stream()
                .map(HouseholdAttribute::jaCode).toList());
    }
}
