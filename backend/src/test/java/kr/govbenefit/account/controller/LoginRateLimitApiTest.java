package kr.govbenefit.account.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 로그인·탈퇴의 비밀번호 확인 시도 제한(결정 0035). 같은 이메일·IP에서 15분에 5번, 같은 IP에서 15분에 30번 실패까지 받는다.
 * 요청자 주소는 MockMvc 요청에 직접 넣고, 시각은 고정한 시계로 옮긴다.
 */
@SpringBootTest(properties = "gov24.schedule.enabled=false")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
class LoginRateLimitApiTest {

    private static final Instant START = Instant.parse("2026-10-29T01:00:00Z");
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int PER_EMAIL_AND_IP = 5;
    private static final int PER_IP = 30;
    private static final String ALICE = "alice@example.com";
    private static final String PASSWORD = "correct-horse";
    private static final String WRONG = "wrong-password";
    private static final String IP = "203.0.113.7";
    private static final String OTHER_IP = "198.51.100.1";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JsonMapper jsonMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private MutableClock clock;
    @Autowired
    private RecordingAccountMailSender mailSender;

    @BeforeEach
    void setUp() throws Exception {
        clock.set(START);
        mailSender.clear();
        new SignUpSteps(mockMvc, jsonMapper, mailSender).signUp(ALICE, PASSWORD);
    }

    @Test
    void 같은_이메일_IP로_5번_실패하면_맞는_비밀번호도_429이고_Retry_After가_지나면_로그인된다() throws Exception {
        failLogins(ALICE, IP, PER_EMAIL_AND_IP);

        MockHttpServletResponse limited = assertTooManyRequests(login(ALICE, PASSWORD, IP));
        assertThat(limited.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo(String.valueOf(WINDOW.toSeconds()));

        clock.advance(WINDOW.minusSeconds(1));
        assertTooManyRequests(login(ALICE, PASSWORD, IP));
        clock.advance(Duration.ofSeconds(1));
        login(ALICE, PASSWORD, IP).andExpect(status().isOk());
    }

    @Test
    void 이메일은_대소문자를_구분하지_않고_센다() throws Exception {
        for (int i = 0; i < PER_EMAIL_AND_IP; i++) {
            login(i % 2 == 0 ? ALICE : "ALICE@example.com", WRONG, IP).andExpect(status().isUnauthorized());
        }

        assertTooManyRequests(login("Alice@Example.com", PASSWORD, IP));
    }

    @Test
    void 같은_IP에서_여러_이메일로_30번_실패하면_429다() throws Exception {
        for (int i = 0; i < PER_IP; i++) {
            login("user" + i + "@example.com", WRONG, IP).andExpect(status().isUnauthorized());
        }

        assertTooManyRequests(login(ALICE, PASSWORD, IP));
        login(ALICE, PASSWORD, OTHER_IP).andExpect(status().isOk());
    }

    @Test
    void 제한에_걸려도_다른_IP의_계정_주인은_로그인하고_비밀번호_재설정은_요청된다() throws Exception {
        failLogins(ALICE, IP, PER_EMAIL_AND_IP);
        assertTooManyRequests(login(ALICE, PASSWORD, IP));

        login(ALICE, PASSWORD, OTHER_IP).andExpect(status().isOk());
        requestPasswordReset(ALICE, IP).andExpect(status().isNoContent());
        assertThat(mailSender.lastResetToken(ALICE)).isNotNull();
    }

    @Test
    void 비밀번호를_재설정하면_같은_IP에서_곧바로_로그인되고_IP_실패는_남는다() throws Exception {
        for (int i = 0; i < PER_IP - PER_EMAIL_AND_IP; i++) {
            login("user" + i + "@example.com", WRONG, IP).andExpect(status().isUnauthorized());
        }
        failLogins(ALICE, IP, PER_EMAIL_AND_IP);
        assertTooManyRequests(login(ALICE, PASSWORD, IP));
        requestPasswordReset(ALICE, OTHER_IP).andExpect(status().isNoContent());
        resetPassword(mailSender.lastResetToken(ALICE), "new-alice-password").andExpect(status().isNoContent());

        // (이메일+IP)는 풀렸지만 IP 한도의 실패 30번은 남아 있다.
        assertTooManyRequests(login(ALICE, "new-alice-password", IP));
        login(ALICE, "new-alice-password", OTHER_IP).andExpect(status().isOk());
    }

    @Test
    void 비밀번호를_재설정하면_그_이메일의_제한이_풀린다() throws Exception {
        failLogins(ALICE, IP, PER_EMAIL_AND_IP);
        assertTooManyRequests(login(ALICE, PASSWORD, IP));

        requestPasswordReset(ALICE, IP).andExpect(status().isNoContent());
        resetPassword(mailSender.lastResetToken(ALICE), "new-alice-password").andExpect(status().isNoContent());

        login(ALICE, "new-alice-password", IP).andExpect(status().isOk());
    }

    @Test
    void 가입된_이메일과_가입되지_않은_이메일의_제한_응답이_같다() throws Exception {
        failLogins(ALICE, IP, PER_EMAIL_AND_IP);
        failLogins("nobody@example.com", IP, PER_EMAIL_AND_IP);

        MockHttpServletResponse registered = assertTooManyRequests(login(ALICE, PASSWORD, IP));
        MockHttpServletResponse unknown = assertTooManyRequests(login("nobody@example.com", PASSWORD, IP));

        assertThat(registered.getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(unknown.getContentAsString(StandardCharsets.UTF_8));
        assertThat(registered.getHeaderNames()).containsExactlyInAnyOrderElementsOf(unknown.getHeaderNames());
        assertThat(registered.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo(unknown.getHeader(HttpHeaders.RETRY_AFTER));
    }

    @Test
    void 성공한_로그인_뒤에는_실패를_다시_0부터_센다() throws Exception {
        failLogins(ALICE, IP, PER_EMAIL_AND_IP - 1);
        login(ALICE, PASSWORD, IP).andExpect(status().isOk());

        failLogins(ALICE, IP, PER_EMAIL_AND_IP - 1);
        login(ALICE, PASSWORD, IP).andExpect(status().isOk());
    }

    @Test
    void 탈퇴의_비밀번호_실패가_5번이면_429이고_계정은_남는다() throws Exception {
        String accessToken = accessToken(login(ALICE, PASSWORD, OTHER_IP));
        for (int i = 0; i < PER_EMAIL_AND_IP; i++) {
            withdraw(accessToken, WRONG, IP).andExpect(status().isBadRequest());
        }

        assertTooManyRequests(withdraw(accessToken, PASSWORD, IP));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM user_account", Integer.class)).isOne();
        withdraw(accessToken, PASSWORD, OTHER_IP).andExpect(status().isNoContent());
    }

    @Test
    void 탈퇴와_로그인의_실패는_같은_한도를_함께_쓴다() throws Exception {
        String accessToken = accessToken(login(ALICE, PASSWORD, OTHER_IP));
        failLogins(ALICE, IP, 3);
        withdraw(accessToken, WRONG, IP).andExpect(status().isBadRequest());
        withdraw(accessToken, WRONG, IP).andExpect(status().isBadRequest());

        assertTooManyRequests(login(ALICE, PASSWORD, IP));
        assertTooManyRequests(withdraw(accessToken, PASSWORD, IP));
    }

    private void failLogins(String email, String ip, int times) throws Exception {
        for (int i = 0; i < times; i++) {
            login(email, WRONG, ip).andExpect(status().isUnauthorized());
        }
    }

    private MockHttpServletResponse assertTooManyRequests(ResultActions result) throws Exception {
        MockHttpServletResponse response = result.andExpect(status().isTooManyRequests()).andReturn().getResponse();
        assertThat(json(response).get("code").asString()).isEqualTo("TOO_MANY_REQUESTS");
        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isNotNull();
        return response;
    }

    private ResultActions login(String email, String password, String ip) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(Map.of("email", email, "password", password))));
    }

    private ResultActions withdraw(String accessToken, String password, String ip) throws Exception {
        return mockMvc.perform(post("/api/me/withdrawal")
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(Map.of("password", password))));
    }

    private ResultActions requestPasswordReset(String email, String ip) throws Exception {
        return mockMvc.perform(post("/api/auth/password-reset")
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(Map.of("email", email))));
    }

    private ResultActions resetPassword(String token, String newPassword) throws Exception {
        Map<String, String> body = new HashMap<>();
        body.put("token", token);
        body.put("newPassword", newPassword);
        return mockMvc.perform(post("/api/auth/password-reset/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(body)));
    }

    private String accessToken(ResultActions login) throws Exception {
        return json(login.andExpect(status().isOk()).andReturn().getResponse()).get("accessToken").asString();
    }

    private JsonNode json(MockHttpServletResponse response) throws Exception {
        return jsonMapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }
}
