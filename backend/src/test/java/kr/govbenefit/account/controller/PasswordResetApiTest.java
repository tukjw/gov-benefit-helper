package kr.govbenefit.account.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import kr.govbenefit.FixedClockConfiguration;
import kr.govbenefit.MutableClock;
import kr.govbenefit.TestcontainersConfiguration;
import kr.govbenefit.account.SignUpSteps;
import kr.govbenefit.account.domain.OpaqueTokens;
import kr.govbenefit.account.mail.RecordingAccountMailSender;
import kr.govbenefit.account.mail.RecordingAccountMailSender.Kind;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 비밀번호 재설정(결정 0019). 요청은 가입 여부를 드러내지 않고, 재설정하면 그 계정의 리프레시 토큰을 모두 폐기한다.
 * 메일은 가짜 발송에 기록만 하고, 시각은 고정한 시계로 옮긴다.
 */
@SpringBootTest(properties = "gov24.schedule.enabled=false")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
class PasswordResetApiTest {

    private static final Instant START = Instant.parse("2026-10-16T01:00:00Z");
    private static final Duration TOKEN_TTL = Duration.ofMinutes(30);
    private static final Duration COOLDOWN = Duration.ofSeconds(60);
    private static final int MAX_PER_WINDOW = 10;
    private static final String EMAIL = "alice@example.com";
    private static final String OLD_PASSWORD = "alice-password";
    private static final String NEW_PASSWORD = "new-alice-password";
    private static final String RESET_URL = "https://govbenefit.kr/reset-password#token=";

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

    @BeforeEach
    void setUp() throws Exception {
        clock.set(START);
        // 재설정 토큰과 리프레시 토큰은 계정을 지우면 함께 지워진다(ON DELETE CASCADE).
        mailSender.clear();
        new SignUpSteps(mockMvc, jsonMapper, mailSender).signUp(EMAIL, OLD_PASSWORD);
    }

    @Test
    void 가입되지_않은_이메일로_요청해도_응답이_같고_메일은_가입된_이메일에만_간다() throws Exception {
        MockHttpServletResponse registered = requestReset("ALICE@example.com")
                .andExpect(status().isNoContent()).andReturn().getResponse();
        MockHttpServletResponse unknown = requestReset("nobody@example.com")
                .andExpect(status().isNoContent()).andReturn().getResponse();

        assertThat(registered.getContentAsString()).isEmpty();
        assertThat(unknown.getContentAsString()).isEmpty();
        assertThat(registered.getHeaderNames()).containsExactlyInAnyOrderElementsOf(unknown.getHeaderNames());
        assertThat(mailSender.sent(Kind.PASSWORD_RESET, "nobody@example.com")).isEmpty();
        assertThat(mailSender.sent(Kind.PASSWORD_RESET, EMAIL)).hasSize(1)
                .allSatisfy(mail -> assertThat(mail.validFor()).isEqualTo(TOKEN_TTL));
        String url = mailSender.lastResetUrl(EMAIL);
        assertThat(url).startsWith(RESET_URL);
        assertThat(OpaqueTokens.isWellFormed(url.substring(RESET_URL.length()))).isTrue();
    }

    @Test
    void 재설정하면_새_비밀번호로만_로그인된다() throws Exception {
        requestReset(EMAIL).andExpect(status().isNoContent());

        MockHttpServletResponse response = reset(mailSender.lastResetToken(EMAIL), NEW_PASSWORD)
                .andExpect(status().isNoContent()).andReturn().getResponse();

        assertThat(response.getContentAsString()).isEmpty();
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();
        login(NEW_PASSWORD).andExpect(status().isOk());
        JsonNode body = json(login(OLD_PASSWORD).andExpect(status().isUnauthorized()));
        assertThat(body.get("code").asString()).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void 재설정하면_모든_로그인의_리프레시_토큰으로_더는_재발급할_수_없다() throws Exception {
        String first = refreshCookie(login(OLD_PASSWORD).andExpect(status().isOk()));
        String second = refreshCookie(login(OLD_PASSWORD).andExpect(status().isOk()));
        requestReset(EMAIL).andExpect(status().isNoContent());

        reset(mailSender.lastResetToken(EMAIL), NEW_PASSWORD).andExpect(status().isNoContent());

        for (String refreshToken : new String[] {first, second}) {
            JsonNode body = json(refresh(refreshToken).andExpect(status().isUnauthorized()));
            assertThat(body.get("code").asString()).isEqualTo("UNAUTHENTICATED");
        }
        // 재설정 뒤의 새 로그인은 쓸 수 있다.
        refresh(refreshCookie(login(NEW_PASSWORD).andExpect(status().isOk()))).andExpect(status().isOk());
    }

    @Test
    void 이미_쓴_재설정_토큰은_거절한다() throws Exception {
        requestReset(EMAIL).andExpect(status().isNoContent());
        String token = mailSender.lastResetToken(EMAIL);
        reset(token, NEW_PASSWORD).andExpect(status().isNoContent());

        assertInvalidToken(reset(token, "another-password"));
        login(NEW_PASSWORD).andExpect(status().isOk());
    }

    @Test
    void 재설정_토큰은_발송하고_30분_전까지_쓸_수_있다() throws Exception {
        requestReset(EMAIL).andExpect(status().isNoContent());

        clock.advance(TOKEN_TTL.minusSeconds(1));
        reset(mailSender.lastResetToken(EMAIL), NEW_PASSWORD).andExpect(status().isNoContent());
    }

    @Test
    void 재설정_토큰은_발송하고_30분_뒤부터_거절한다() throws Exception {
        requestReset(EMAIL).andExpect(status().isNoContent());

        clock.advance(TOKEN_TTL);
        assertInvalidToken(reset(mailSender.lastResetToken(EMAIL), NEW_PASSWORD));
        login(OLD_PASSWORD).andExpect(status().isOk());
    }

    @Test
    void 다시_요청하면_이전_토큰은_쓸_수_없다() throws Exception {
        requestReset(EMAIL).andExpect(status().isNoContent());
        String old = mailSender.lastResetToken(EMAIL);
        clock.advance(COOLDOWN);
        requestReset(EMAIL).andExpect(status().isNoContent());
        String latest = mailSender.lastResetToken(EMAIL);

        assertInvalidToken(reset(old, NEW_PASSWORD));
        reset(latest, NEW_PASSWORD).andExpect(status().isNoContent());
    }

    @Test
    void 형식이_틀렸거나_발급하지_않은_토큰은_같은_거절이다() throws Exception {
        assertInvalidToken(reset("not-a-token", NEW_PASSWORD));
        assertInvalidToken(reset(OpaqueTokens.generate(), NEW_PASSWORD));
    }

    @Test
    void 새_비밀번호에도_가입과_같은_규칙을_적용하고_거절해도_토큰을_쓰지_않는다() throws Exception {
        requestReset(EMAIL).andExpect(status().isNoContent());
        String token = mailSender.lastResetToken(EMAIL);

        assertPasswordError(reset(token, "short12"), "8자 이상이어야 합니다.");
        assertPasswordError(reset(token, "a".repeat(73)), "72바이트 이하여야 합니다(한글은 24자).");
        assertPasswordError(reset(token, null), "비밀번호를 입력해야 합니다.");

        reset(token, "a".repeat(72)).andExpect(status().isNoContent());
        login("a".repeat(72)).andExpect(status().isOk());
    }

    @Test
    void 입력_오류에_토큰_값을_넣지_않는다() throws Exception {
        JsonNode body = json(reset(" ", NEW_PASSWORD).andExpect(status().isBadRequest()));

        assertThat(body.get("errors").get(0).get("field").asString()).isEqualTo("token");
        assertThat(body.get("errors").get(0).get("value").isNull()).isTrue();
    }

    @Test
    void 같은_계정에는_60초가_지나야_다시_보내고_제한에_걸려도_응답은_같다() throws Exception {
        requestReset(EMAIL).andExpect(status().isNoContent());
        clock.advance(COOLDOWN.minusSeconds(1));
        requestReset(EMAIL).andExpect(status().isNoContent());
        assertThat(mailSender.sent(Kind.PASSWORD_RESET, EMAIL)).hasSize(1);

        clock.advance(Duration.ofSeconds(1));
        requestReset(EMAIL).andExpect(status().isNoContent());
        assertThat(mailSender.sent(Kind.PASSWORD_RESET, EMAIL)).hasSize(2);
    }

    @Test
    void 최근_24시간에는_10번까지_보낸다() throws Exception {
        for (int i = 0; i < MAX_PER_WINDOW + 1; i++) {
            requestReset(EMAIL).andExpect(status().isNoContent());
            clock.advance(COOLDOWN);
        }

        assertThat(mailSender.sent(Kind.PASSWORD_RESET, EMAIL)).hasSize(MAX_PER_WINDOW);
    }

    @Test
    void 재설정_토큰은_원문이_아니라_해시로_저장한다() throws Exception {
        requestReset(EMAIL).andExpect(status().isNoContent());
        String token = mailSender.lastResetToken(EMAIL);

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM password_reset_token");
        assertThat(row.get("token_hash")).isEqualTo(OpaqueTokens.hash(token));
        assertThat(row.values().stream().map(String::valueOf)).noneMatch(value -> value.contains(token));
    }

    private void assertInvalidToken(ResultActions result) throws Exception {
        JsonNode body = json(result.andExpect(status().isBadRequest()));
        assertThat(body.get("code").asString()).isEqualTo("INVALID_RESET_TOKEN");
        assertThat(body.get("errors").size()).isZero();
    }

    private void assertPasswordError(ResultActions result, String reason) throws Exception {
        JsonNode body = json(result.andExpect(status().isBadRequest()));
        assertThat(body.get("code").asString()).isEqualTo("INVALID_INPUT");
        assertThat(body.get("errors").size()).isEqualTo(1);
        JsonNode error = body.get("errors").get(0);
        assertThat(error.get("field").asString()).isEqualTo("newPassword");
        assertThat(error.get("value").isNull()).as("value: %s", error.get("value")).isTrue();
        assertThat(error.get("reason").asString()).isEqualTo(reason);
    }

    private ResultActions requestReset(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/password-reset")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(Map.of("email", email))));
    }

    private ResultActions reset(String token, String newPassword) throws Exception {
        Map<String, String> request = new HashMap<>();
        request.put("token", token);
        request.put("newPassword", newPassword);
        return mockMvc.perform(post("/api/auth/password-reset/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(request)));
    }

    private ResultActions login(String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(Map.of("email", EMAIL, "password", password))));
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/auth/refresh")
                .header("X-Requested-With", "fetch")
                .cookie(new Cookie("refresh_token", refreshToken)));
    }

    private static String refreshCookie(ResultActions result) {
        String setCookie = result.andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);
        return setCookie.substring("refresh_token=".length(), setCookie.indexOf(';'));
    }

    private JsonNode json(ResultActions result) throws Exception {
        return jsonMapper.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
