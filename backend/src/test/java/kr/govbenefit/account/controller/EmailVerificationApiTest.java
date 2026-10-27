package kr.govbenefit.account.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import kr.govbenefit.FixedClockConfiguration;
import kr.govbenefit.MutableClock;
import kr.govbenefit.TestcontainersConfiguration;
import kr.govbenefit.account.SignUpSteps;
import kr.govbenefit.account.domain.OpaqueTokens;
import kr.govbenefit.account.domain.VerificationCodes;
import kr.govbenefit.account.mail.RecordingAccountMailSender;
import kr.govbenefit.account.mail.RecordingAccountMailSender.Kind;
import kr.govbenefit.account.mail.RecordingAccountMailSender.SentMail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 가입 전 이메일 인증(결정 0019). 메일은 가짜 발송에 기록만 하고, 시각은 고정한 시계로 옮긴다.
 */
@SpringBootTest(properties = "gov24.schedule.enabled=false")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
class EmailVerificationApiTest {

    private static final Instant START = Instant.parse("2026-10-16T01:00:00Z");
    private static final Duration CODE_TTL = Duration.ofMinutes(10);
    private static final Duration TICKET_TTL = Duration.ofMinutes(30);
    private static final Duration COOLDOWN = Duration.ofSeconds(60);
    private static final Duration WINDOW = Duration.ofHours(24);
    private static final int MAX_ATTEMPTS = 5;
    private static final int MAX_PER_WINDOW = 10;
    private static final String EMAIL = "me@example.com";

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

    private SignUpSteps steps;

    @BeforeEach
    void setUp() {
        clock.set(START);
        mailSender.clear();
        steps = new SignUpSteps(mockMvc, jsonMapper, mailSender);
    }

    @Test
    void 인증번호를_요청하면_그_이메일로_메일이_한_건_기록된다() throws Exception {
        MockHttpServletResponse response = steps.requestCode("Me@Example.com")
                .andExpect(status().isNoContent()).andReturn().getResponse();

        assertThat(response.getContentAsString()).isEmpty();
        assertThat(mailSender.sent()).hasSize(1);
        SentMail mail = mailSender.sent().getFirst();
        assertThat(mail.kind()).isEqualTo(Kind.SIGNUP_CODE);
        assertThat(mail.email()).isEqualTo(EMAIL);
        assertThat(mail.content()).matches("\\d{6}");
        assertThat(mail.validFor()).isEqualTo(CODE_TTL);
    }

    @Test
    void 맞는_인증번호로_확인하면_30분짜리_가입_티켓을_받는다() throws Exception {
        steps.requestCode(EMAIL).andExpect(status().isNoContent());

        JsonNode body = json(steps.confirm("ME@example.com", mailSender.lastSignupCode(EMAIL))
                .andExpect(status().isOk()));

        assertThat(body.propertyNames()).containsExactlyInAnyOrder("verificationToken", "expiresAt");
        assertThat(OpaqueTokens.isWellFormed(body.get("verificationToken").asString())).isTrue();
        assertThat(body.get("expiresAt").asString()).endsWith("+09:00");
        assertThat(OffsetDateTime.parse(body.get("expiresAt").asString()).toInstant())
                .isEqualTo(START.plus(TICKET_TTL));
    }

    @Test
    void 인증번호는_발송하고_10분_뒤부터_거절한다() throws Exception {
        steps.requestCode("early@example.com").andExpect(status().isNoContent());
        steps.requestCode("late@example.com").andExpect(status().isNoContent());

        clock.advance(CODE_TTL.minusSeconds(1));
        steps.confirm("early@example.com", mailSender.lastSignupCode("early@example.com"))
                .andExpect(status().isOk());
        clock.advance(Duration.ofSeconds(1));
        assertRejected(steps.confirm("late@example.com", mailSender.lastSignupCode("late@example.com")),
                "VERIFICATION_CODE_EXPIRED");
        assertThat(attempts("late@example.com")).isZero();
    }

    @Test
    void 네_번_틀린_뒤에는_맞는_번호로_확인할_수_있다() throws Exception {
        steps.requestCode(EMAIL).andExpect(status().isNoContent());
        String code = mailSender.lastSignupCode(EMAIL);

        for (int i = 0; i < MAX_ATTEMPTS - 1; i++) {
            assertRejected(steps.confirm(EMAIL, wrong(code)), "VERIFICATION_CODE_MISMATCH");
        }
        steps.confirm(EMAIL, code).andExpect(status().isOk());
        assertThat(attempts(EMAIL)).isEqualTo(MAX_ATTEMPTS);
    }

    @Test
    void 다섯_번_틀린_뒤에는_맞는_번호도_거절한다() throws Exception {
        steps.requestCode(EMAIL).andExpect(status().isNoContent());
        String code = mailSender.lastSignupCode(EMAIL);

        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            assertRejected(steps.confirm(EMAIL, wrong(code)), "VERIFICATION_CODE_MISMATCH");
        }
        assertRejected(steps.confirm(EMAIL, code), "VERIFICATION_ATTEMPTS_EXCEEDED");
        assertRejected(steps.confirm(EMAIL, wrong(code)), "VERIFICATION_ATTEMPTS_EXCEEDED");
        assertThat(attempts(EMAIL)).isEqualTo(MAX_ATTEMPTS);
    }

    @Test
    void 인증번호를_다시_받으면_새_번호로_확인하고_이전_번호는_틀린_번호다() throws Exception {
        steps.requestCode(EMAIL).andExpect(status().isNoContent());
        String old = mailSender.lastSignupCode(EMAIL);
        // 새 번호가 이전 번호와 우연히 같으면(10^-6) 다시 받는다.
        String latest = old;
        while (latest.equals(old)) {
            clock.advance(COOLDOWN);
            steps.requestCode(EMAIL).andExpect(status().isNoContent());
            latest = mailSender.lastSignupCode(EMAIL);
        }

        assertRejected(steps.confirm(EMAIL, old), "VERIFICATION_CODE_MISMATCH");
        steps.confirm(EMAIL, latest).andExpect(status().isOk());
    }

    @Test
    void 다시_받으면_틀린_횟수도_새로_센다() throws Exception {
        steps.requestCode(EMAIL).andExpect(status().isNoContent());
        String code = mailSender.lastSignupCode(EMAIL);
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            steps.confirm(EMAIL, wrong(code));
        }
        clock.advance(COOLDOWN);
        steps.requestCode(EMAIL).andExpect(status().isNoContent());

        steps.confirm(EMAIL, mailSender.lastSignupCode(EMAIL)).andExpect(status().isOk());
    }

    @Test
    void 이미_확인한_번호와_요청한_적_없는_이메일은_만료와_같다() throws Exception {
        steps.requestCode(EMAIL).andExpect(status().isNoContent());
        String code = mailSender.lastSignupCode(EMAIL);
        steps.confirm(EMAIL, code).andExpect(status().isOk());

        assertRejected(steps.confirm(EMAIL, code), "VERIFICATION_CODE_EXPIRED");
        assertRejected(steps.confirm("nobody@example.com", "123456"), "VERIFICATION_CODE_EXPIRED");
    }

    @Test
    void 형식이_틀린_번호는_입력_오류이고_시도로_세지_않는다() throws Exception {
        steps.requestCode(EMAIL).andExpect(status().isNoContent());

        for (String code : List.of("12345", "1234567", "abcdef", "12 456")) {
            JsonNode body = json(steps.confirm(EMAIL, code).andExpect(status().isBadRequest()));
            assertThat(body.get("code").asString()).isEqualTo("INVALID_INPUT");
            assertThat(body.get("errors").get(0).get("field").asString()).isEqualTo("code");
            assertThat(body.get("errors").get(0).get("reason").asString()).isEqualTo("6자리 숫자여야 합니다.");
        }
        assertThat(json(steps.confirm(EMAIL, null).andExpect(status().isBadRequest()))
                .get("errors").get(0).get("reason").asString()).isEqualTo("인증번호를 입력해야 합니다.");
        assertThat(attempts(EMAIL)).isZero();
    }

    @Test
    void 형식이_잘못된_이메일로는_요청할_수_없다() throws Exception {
        for (String email : List.of("", "not-an-email")) {
            JsonNode body = json(steps.requestCode(email).andExpect(status().isBadRequest()));
            assertThat(body.get("code").asString()).isEqualTo("INVALID_INPUT");
        }
        assertThat(mailSender.sent()).isEmpty();
    }

    @Test
    void 같은_이메일에는_60초가_지나야_다시_보낸다() throws Exception {
        steps.requestCode(EMAIL).andExpect(status().isNoContent());

        clock.advance(COOLDOWN.minusSeconds(1));
        assertRateLimited(steps.requestCode("ME@example.com"), 1);
        clock.advance(Duration.ofSeconds(1));
        steps.requestCode(EMAIL).andExpect(status().isNoContent());

        assertThat(mailSender.sent(Kind.SIGNUP_CODE, EMAIL)).hasSize(2);
        // 다른 이메일은 따로 센다.
        steps.requestCode("other@example.com").andExpect(status().isNoContent());
    }

    @Test
    void 최근_24시간에는_10번까지_보낸다() throws Exception {
        for (int i = 0; i < MAX_PER_WINDOW; i++) {
            steps.requestCode(EMAIL).andExpect(status().isNoContent());
            clock.advance(COOLDOWN);
        }
        // 첫 발송 + 600초. 첫 발송이 창을 벗어날 때까지 기다린다.
        assertRateLimited(steps.requestCode(EMAIL), WINDOW.minus(COOLDOWN.multipliedBy(MAX_PER_WINDOW)).toSeconds());

        clock.set(START.plus(WINDOW).minusSeconds(1));
        assertRateLimited(steps.requestCode(EMAIL), 1);
        clock.set(START.plus(WINDOW));
        steps.requestCode(EMAIL).andExpect(status().isNoContent());

        assertThat(mailSender.sent(Kind.SIGNUP_CODE, EMAIL)).hasSize(MAX_PER_WINDOW + 1);
    }

    @Test
    void 인증번호와_가입_티켓은_원문이_아니라_해시로_저장한다() throws Exception {
        steps.requestCode(EMAIL).andExpect(status().isNoContent());
        String code = mailSender.lastSignupCode(EMAIL);
        String ticket = json(steps.confirm(EMAIL, code).andExpect(status().isOk()))
                .get("verificationToken").asString();

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM email_verification WHERE email = ?", EMAIL);
        assertThat(row.get("code_hash")).isEqualTo(VerificationCodes.hash(code)).isNotEqualTo(code);
        assertThat(row.get("ticket_hash")).isEqualTo(OpaqueTokens.hash(ticket));
        assertThat(row.values().stream().map(String::valueOf)).noneMatch(value -> value.contains(ticket));
    }

    private void assertRejected(ResultActions result, String code) throws Exception {
        JsonNode body = json(result.andExpect(status().isBadRequest()));
        assertThat(body.get("code").asString()).isEqualTo(code);
        assertThat(body.get("errors").size()).isZero();
    }

    private void assertRateLimited(ResultActions result, long retryAfterSeconds) throws Exception {
        MockHttpServletResponse response = result.andExpect(status().isTooManyRequests()).andReturn().getResponse();
        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo(String.valueOf(retryAfterSeconds));
        assertThat(jsonMapper.readTree(response.getContentAsString(StandardCharsets.UTF_8)).get("code").asString())
                .isEqualTo("TOO_MANY_REQUESTS");
    }

    private int attempts(String email) {
        return jdbcTemplate.queryForObject(
                "SELECT attempts FROM email_verification WHERE email = ? ORDER BY id DESC LIMIT 1", Integer.class, email);
    }

    /** 맞는 번호와 다른 6자리 번호 */
    private static String wrong(String code) {
        return String.format("%06d", (Integer.parseInt(code) + 1) % 1_000_000);
    }

    private JsonNode json(ResultActions result) throws Exception {
        return jsonMapper.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
