package kr.govbenefit.account.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kr.govbenefit.MutableClock;
import kr.govbenefit.TestcontainersConfiguration;
import kr.govbenefit.account.SignUpSteps;
import kr.govbenefit.account.domain.OpaqueTokens;
import kr.govbenefit.account.domain.UserAccount;
import kr.govbenefit.account.mail.RecordingAccountMailSender;
import kr.govbenefit.account.mail.RecordingAccountMailSender.Kind;
import kr.govbenefit.account.repository.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 가입 API를 Testcontainers MySQL로 검증한다. 이메일 중복은 실제 unique 제약과 정렬 규칙(결정 0001)을 따른다.
 * 가입은 이메일 인증으로 받은 가입 티켓이 있어야 한다(결정 0019). 시각은 고정한 시계로 옮긴다.
 */
@SpringBootTest(properties = "gov24.schedule.enabled=false")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, SignUpApiTest.FixedClockConfig.class})
class SignUpApiTest {

    private static final String PASSWORD = "correct-horse";
    private static final Instant START = Instant.parse("2026-10-16T01:00:00Z");
    private static final Duration TICKET_TTL = Duration.ofMinutes(30);
    private static final Duration COOLDOWN = Duration.ofSeconds(60);

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfig {

        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(START, ZoneId.of("Asia/Seoul"));
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserAccountRepository userAccountRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
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
        jdbcTemplate.update("DELETE FROM user_account");
        jdbcTemplate.update("DELETE FROM email_verification");
        mailSender.clear();
        steps = new SignUpSteps(mockMvc, jsonMapper, mailSender);
    }

    @Test
    void 로그인_없이_가입하고_계정_정보를_받는다() throws Exception {
        JsonNode body = json(signUp("me@example.com", PASSWORD).andExpect(status().isCreated()));

        assertThat(body.propertyNames()).containsExactlyInAnyOrder("email", "createdAt");
        assertThat(body.get("email").asString()).isEqualTo("me@example.com");
        assertThat(body.get("createdAt").asString()).endsWith("+09:00");
        assertThat(userAccountRepository.count()).isEqualTo(1);
    }

    @Test
    void 비밀번호는_원문이_아니라_되돌릴_수_없는_해시로_저장한다() throws Exception {
        signUp("me@example.com", PASSWORD).andExpect(status().isCreated());

        String stored = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM user_account WHERE email = 'me@example.com'", String.class);
        assertThat(stored).isNotEqualTo(PASSWORD).doesNotContain(PASSWORD).startsWith("{bcrypt}$2");
        assertThat(passwordEncoder.matches(PASSWORD, stored)).isTrue();
        assertThat(passwordEncoder.matches("wrong-horse", stored)).isFalse();
    }

    @Test
    void 이메일은_소문자로_저장한다() throws Exception {
        JsonNode body = json(signUp("Me@Example.COM", PASSWORD).andExpect(status().isCreated()));

        assertThat(body.get("email").asString()).isEqualTo("me@example.com");
        UserAccount account = userAccountRepository.findByEmail("me@example.com").orElseThrow();
        assertThat(account.getEmail()).isEqualTo("me@example.com");
    }

    @Test
    void 이미_가입된_이메일은_인증을_마쳐도_대소문자가_달라도_거절한다() throws Exception {
        signUp("me@example.com", PASSWORD).andExpect(status().isCreated());

        for (String email : List.of("me@example.com", "ME@EXAMPLE.COM")) {
            clock.advance(COOLDOWN);
            // 인증번호 요청 단계에서는 가입 여부를 드러내지 않는다.
            String ticket = steps.verify(email);
            JsonNode body = json(steps.signUp(email, "another-password", ticket).andExpect(status().isConflict()));
            assertThat(body.get("code").asString()).isEqualTo("DUPLICATE_EMAIL");
            assertThat(body.get("errors").size()).isZero();
        }
        assertThat(mailSender.sent(Kind.SIGNUP_CODE, "me@example.com")).hasSize(3);
        assertThat(userAccountRepository.count()).isEqualTo(1);
    }

    @Test
    void 이미_가입된_이메일도_티켓이_없으면_409가_아니라_403이다() throws Exception {
        signUp("me@example.com", PASSWORD).andExpect(status().isCreated());

        assertNotVerified(steps.signUp("me@example.com", "another-password", OpaqueTokens.generate()));
    }

    @Test
    void 티켓을_빈_값으로_보내면_항목별_오류이고_값을_빼고_응답한다() throws Exception {
        assertInputError(steps.signUp("me@example.com", PASSWORD, null),
                "verificationToken", null, "이메일 인증을 마쳐야 합니다.");
        assertInputError(steps.signUp("me@example.com", PASSWORD, " "),
                "verificationToken", null, "이메일 인증을 마쳐야 합니다.");
        assertThat(userAccountRepository.count()).isZero();
    }

    @Test
    void 형식이_틀렸거나_발급하지_않은_티켓은_거절한다() throws Exception {
        assertNotVerified(steps.signUp("me@example.com", PASSWORD, "not-a-ticket"));
        assertNotVerified(steps.signUp("me@example.com", PASSWORD, OpaqueTokens.generate()));
        assertThat(userAccountRepository.count()).isZero();
    }

    @Test
    void 확인한_것과_다른_이메일로는_가입할_수_없다() throws Exception {
        String ticket = steps.verify("alice@example.com");

        assertNotVerified(steps.signUp("bob@example.com", PASSWORD, ticket));
        assertThat(userAccountRepository.count()).isZero();
        // 거절된 뒤에도 확인한 이메일로는 쓸 수 있다.
        steps.signUp("alice@example.com", PASSWORD, ticket).andExpect(status().isCreated());
    }

    @Test
    void 한_번의_확인으로는_계정을_하나만_만든다() throws Exception {
        String ticket = steps.verify("me@example.com");
        steps.signUp("me@example.com", PASSWORD, ticket).andExpect(status().isCreated());
        jdbcTemplate.update("DELETE FROM user_account");

        // 계정을 지워 중복 검사를 통과해도 같은 티켓은 다시 쓸 수 없다.
        assertNotVerified(steps.signUp("me@example.com", "another-password", ticket));
        assertNotVerified(steps.signUp("ME@example.com", PASSWORD, ticket));
        assertThat(userAccountRepository.count()).isZero();
    }

    @Test
    void 가입_티켓은_확인하고_30분_뒤부터_쓸_수_없다() throws Exception {
        String early = steps.verify("early@example.com");
        String late = steps.verify("late@example.com");

        clock.advance(TICKET_TTL.minusSeconds(1));
        steps.signUp("early@example.com", PASSWORD, early).andExpect(status().isCreated());
        clock.advance(Duration.ofSeconds(1));
        assertNotVerified(steps.signUp("late@example.com", PASSWORD, late));
        assertThat(userAccountRepository.findByEmail("late@example.com")).isEmpty();
    }

    @Test
    void 가입_티켓은_이후에_인증번호를_다시_받아도_쓸_수_있다() throws Exception {
        String ticket = steps.verify("me@example.com");
        clock.advance(COOLDOWN);
        steps.requestCode("me@example.com").andExpect(status().isNoContent());

        steps.signUp("me@example.com", PASSWORD, ticket).andExpect(status().isCreated());
    }

    @Test
    void 형식이_잘못된_이메일은_항목별_오류다() throws Exception {
        for (String email : List.of("not-an-email", "a@", "@example.com", "a b@example.com")) {
            assertInputError(signUpUnverified(email, PASSWORD), "email", email, "이메일 형식이 올바르지 않습니다.");
        }
        // 형식은 맞고 255자다. 도메인 이름표는 63자까지라 여러 개로 나눈다.
        String tooLong = "a@" + ("b".repeat(62) + ".").repeat(3) + "c".repeat(61) + ".kr";
        assertThat(tooLong).hasSize(255);
        assertInputError(signUpUnverified(tooLong, PASSWORD), "email", tooLong, "254자까지 입력할 수 있습니다.");
    }

    @Test
    void 빈_이메일은_항목별_오류다() throws Exception {
        assertInputError(signUpUnverified("", PASSWORD), "email", "", "이메일을 입력해야 합니다.");
        assertInputError(signUpUnverified(null, PASSWORD), "email", null, "이메일을 입력해야 합니다.");
    }

    @Test
    void 빈_비밀번호와_규칙에_맞지_않는_비밀번호는_값을_빼고_항목별_오류다() throws Exception {
        assertInputError(signUpUnverified("me@example.com", ""), "password", null, "8자 이상이어야 합니다.");
        assertInputError(signUpUnverified("me@example.com", null), "password", null, "비밀번호를 입력해야 합니다.");
        assertInputError(signUpUnverified("me@example.com", "short12"), "password", null, "8자 이상이어야 합니다.");
        assertInputError(signUpUnverified("me@example.com", "가".repeat(25)), "password", null,
                "72바이트 이하여야 합니다(한글은 24자).");
        assertThat(userAccountRepository.count()).isZero();
    }

    @Test
    void 이메일과_비밀번호가_모두_잘못되면_오류가_둘이다() throws Exception {
        JsonNode body = json(signUpUnverified("not-an-email", "").andExpect(status().isBadRequest()));

        assertThat(body.get("code").asString()).isEqualTo("INVALID_INPUT");
        assertThat(body.get("errors").valueStream().map(error -> error.get("field").asString()))
                .containsExactlyInAnyOrder("email", "password");
    }

    @Test
    void 본문이_JSON이_아니면_잘못된_입력이다() throws Exception {
        JsonNode body = json(mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest()));

        assertThat(body.get("code").asString()).isEqualTo("INVALID_INPUT");
    }

    private void assertNotVerified(ResultActions result) throws Exception {
        JsonNode body = json(result.andExpect(status().isForbidden()));
        assertThat(body.get("code").asString()).isEqualTo("EMAIL_NOT_VERIFIED");
        assertThat(body.get("errors").size()).isZero();
    }

    private void assertInputError(ResultActions result, String field, String value, String reason) throws Exception {
        JsonNode body = json(result.andExpect(status().isBadRequest()));
        assertThat(body.get("code").asString()).isEqualTo("INVALID_INPUT");
        assertThat(body.get("errors").size()).as("errors: %s", body.get("errors")).isEqualTo(1);
        JsonNode error = body.get("errors").get(0);
        assertThat(error.get("field").asString()).isEqualTo(field);
        if (value == null) {
            assertThat(error.get("value").isNull()).as("value: %s", error.get("value")).isTrue();
        } else {
            assertThat(error.get("value").asString()).isEqualTo(value);
        }
        assertThat(error.get("reason").asString()).isEqualTo(reason);
    }

    /**
     * 인증을 마치고 가입한다.
     */
    private ResultActions signUp(String email, String password) throws Exception {
        return steps.signUp(email, password, steps.verify(email));
    }

    /**
     * 입력 검증만 보는 요청. 티켓 항목이 오류에 섞이지 않게 형식만 맞는 티켓을 넣는다.
     */
    private ResultActions signUpUnverified(String email, String password) throws Exception {
        Map<String, String> request = new HashMap<>();
        request.put("email", email);
        request.put("password", password);
        request.put("verificationToken", OpaqueTokens.generate());
        return mockMvc.perform(post("/api/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(request)));
    }

    private JsonNode json(ResultActions result) throws Exception {
        return jsonMapper.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
