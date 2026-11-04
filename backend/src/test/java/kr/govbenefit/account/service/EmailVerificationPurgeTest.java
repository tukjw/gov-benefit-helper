package kr.govbenefit.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import kr.govbenefit.FixedClockConfiguration;
import kr.govbenefit.MutableClock;
import kr.govbenefit.TestcontainersConfiguration;
import kr.govbenefit.account.SignUpSteps;
import kr.govbenefit.account.mail.RecordingAccountMailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/**
 * 이메일 인증 기록 파기(결정 0041). 발송 후 24시간이 지난 기록만 가입 여부와 관계없이 지우고, 발송 제한과 진행 중인 인증·가입은
 * 파기 전후로 같게 동작한다. 시각은 고정한 시계로 옮기고 파기는 스케줄을 기다리지 않고 직접 부른다.
 */
@SpringBootTest(properties = "gov24.schedule.enabled=false")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
class EmailVerificationPurgeTest {

    private static final Instant START = Instant.parse("2026-11-03T01:00:00Z");
    private static final Duration COOLDOWN = Duration.ofSeconds(60);
    private static final Duration WINDOW = Duration.ofHours(24);
    private static final int MAX_PER_WINDOW = 10;
    private static final String PASSWORD = "correct-horse";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private JsonMapper jsonMapper;
    @Autowired
    private MutableClock clock;
    @Autowired
    private RecordingAccountMailSender mailSender;
    @Autowired
    private EmailVerificationPurgeJob purgeJob;
    @Autowired
    private ScheduledTaskHolder scheduledTasks;

    private SignUpSteps steps;

    @BeforeEach
    void setUp() {
        clock.set(START);
        mailSender.clear();
        steps = new SignUpSteps(mockMvc, jsonMapper, mailSender);
    }

    @Test
    void 발송_후_24시간이_지난_기록은_가입_여부와_관계없이_지운다() throws Exception {
        steps.signUp("joined@example.com", PASSWORD);
        steps.requestCode("pending@example.com").andExpect(status().isNoContent());
        steps.signUp("left@example.com", PASSWORD);
        withdraw("left@example.com");
        clock.advance(Duration.ofSeconds(1));
        steps.requestCode("recent@example.com").andExpect(status().isNoContent());
        assertThat(rows("joined@example.com", "pending@example.com", "left@example.com", "recent@example.com"))
                .isEqualTo(4);

        clock.set(START.plus(WINDOW));
        assertThat(purgeJob.purge()).isEqualTo(3);

        assertThat(rows("joined@example.com", "pending@example.com", "left@example.com")).isZero();
        assertThat(rows("recent@example.com")).isEqualTo(1);
        // 지우는 것은 인증 기록뿐이다. 가입한 계정은 그대로 로그인된다.
        login("joined@example.com").andExpect(status().isOk());
    }

    @Test
    void 진행_중인_인증번호와_가입_티켓은_파기한_뒤에도_쓸_수_있다() throws Exception {
        String email = "me@example.com";
        steps.requestCode(email).andExpect(status().isNoContent());

        clock.advance(Duration.ofMinutes(5));
        assertThat(purgeJob.purge()).isZero();
        String ticket = jsonMapper.readTree(steps.confirm(email, mailSender.lastSignupCode(email))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("verificationToken").asString();

        clock.advance(Duration.ofMinutes(20));
        assertThat(purgeJob.purge()).isZero();
        steps.signUp(email, PASSWORD, ticket).andExpect(status().isCreated());
    }

    @Test
    void 파기한_뒤에도_같은_이메일에는_60초가_지나야_다시_보낸다() throws Exception {
        String email = "me@example.com";
        steps.requestCode(email).andExpect(status().isNoContent());

        assertThat(purgeJob.purge()).isZero();
        clock.advance(COOLDOWN.minusSeconds(1));
        assertRateLimited(steps.requestCode(email), 1);
        clock.advance(Duration.ofSeconds(1));
        steps.requestCode(email).andExpect(status().isNoContent());
    }

    @Test
    void 파기는_최근_24시간_발송_횟수에서_빠진_기록만_지운다() throws Exception {
        String email = "me@example.com";
        for (int i = 0; i < MAX_PER_WINDOW; i++) {
            steps.requestCode(email).andExpect(status().isNoContent());
            clock.advance(COOLDOWN);
        }

        clock.set(START.plus(WINDOW).minusSeconds(1));
        assertThat(purgeJob.purge()).isZero();
        assertRateLimited(steps.requestCode(email), 1);

        clock.set(START.plus(WINDOW));
        assertThat(purgeJob.purge()).isEqualTo(1);
        assertThat(rows(email)).isEqualTo(MAX_PER_WINDOW - 1);
        steps.requestCode(email).andExpect(status().isNoContent());
        // 두 번째 발송이 창을 벗어날 때까지는 다시 꽉 찼다. 파기하지 않았을 때와 같다.
        clock.advance(COOLDOWN.minusSeconds(1));
        assertRateLimited(steps.requestCode(email), 1);
    }

    @Test
    void 파기는_매일_04시_30분에_돈다() {
        List<String> expressions = scheduledTasks.getScheduledTasks().stream()
                .map(ScheduledTask::getTask)
                .filter(task -> task.toString().contains(EmailVerificationPurgeJob.class.getSimpleName()))
                .map(task -> ((CronTask) task).getExpression())
                .toList();

        assertThat(expressions).containsExactly("0 30 4 * * *");
    }

    private void withdraw(String email) throws Exception {
        String accessToken = jsonMapper.readTree(login(email).andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("accessToken").asString();
        mockMvc.perform(post("/api/me/withdrawal")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("password", PASSWORD))))
                .andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM user_account WHERE email = ?", Integer.class,
                email)).isZero();
    }

    private ResultActions login(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(Map.of("email", email, "password", PASSWORD))));
    }

    private int rows(String... emails) {
        String placeholders = String.join(",", Collections.nCopies(emails.length, "?"));
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM email_verification WHERE email IN ("
                + placeholders + ")", Integer.class, (Object[]) emails);
    }

    private void assertRateLimited(ResultActions result, long retryAfterSeconds) throws Exception {
        MockHttpServletResponse response = result.andExpect(status().isTooManyRequests()).andReturn().getResponse();
        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo(String.valueOf(retryAfterSeconds));
    }
}
