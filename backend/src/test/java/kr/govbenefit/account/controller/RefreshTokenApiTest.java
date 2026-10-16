package kr.govbenefit.account.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import kr.govbenefit.MutableClock;
import kr.govbenefit.TestcontainersConfiguration;
import kr.govbenefit.account.SignUpSteps;
import kr.govbenefit.account.domain.OpaqueTokens;
import kr.govbenefit.account.mail.RecordingAccountMailSender;
import kr.govbenefit.common.security.AccessTokenIssuer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 리프레시 토큰으로 로그인 유지와 로그아웃(DEC-AUTH, 결정 0017). 시각은 고정한 시계로 옮긴다.
 */
@SpringBootTest(properties = "gov24.schedule.enabled=false")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RefreshTokenApiTest.FixedClockConfig.class})
class RefreshTokenApiTest {

    private static final Instant START = Instant.parse("2026-10-14T01:00:00Z");
    private static final Duration TTL = Duration.ofDays(14);
    private static final Duration GRACE = Duration.ofSeconds(10);
    private static final String COOKIE = "refresh_token";

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
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private JsonMapper jsonMapper;
    @Autowired
    private MutableClock clock;
    @Autowired
    private AccessTokenIssuer accessTokenIssuer;
    @Autowired
    private RecordingAccountMailSender mailSender;

    @BeforeEach
    void setUp() throws Exception {
        clock.set(START);
        // 리프레시 토큰은 계정을 지우면 함께 지워진다(ON DELETE CASCADE).
        jdbcTemplate.update("DELETE FROM user_account");
        jdbcTemplate.update("DELETE FROM email_verification");
        mailSender.clear();
        new SignUpSteps(mockMvc, jsonMapper, mailSender).signUp("alice@example.com", "alice-password");
    }

    @Test
    void 로그인하면_리프레시_토큰을_쿠키로만_받는다() throws Exception {
        MockHttpServletResponse response = login().andExpect(status().isOk()).andReturn().getResponse();

        String setCookie = response.getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).startsWith(COOKIE + "=");
        assertThat(List.of(setCookie.split("; ")))
                .contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/api/auth", "Max-Age=1209600");
        String refreshToken = cookieValue(response);
        assertThat(OpaqueTokens.isWellFormed(refreshToken)).isTrue();

        String body = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(jsonMapper.readTree(body).propertyNames())
                .containsExactlyInAnyOrder("accessToken", "tokenType", "expiresAt");
        assertThat(body).doesNotContain(refreshToken);
    }

    @Test
    void 재발급하면_새_액세스_토큰과_새_리프레시_토큰을_받는다() throws Exception {
        String refreshToken = cookieValue(login().andReturn().getResponse());

        MockHttpServletResponse response = refresh(refreshToken).andExpect(status().isOk()).andReturn().getResponse();

        String next = cookieValue(response);
        assertThat(next).isNotEqualTo(refreshToken);
        assertThat(List.of(response.getHeader(HttpHeaders.SET_COOKIE).split("; ")))
                .contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/api/auth", "Max-Age=1209600");
        JsonNode body = jsonMapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("accessToken", "tokenType", "expiresAt");
        assertThat(body.get("tokenType").asString()).isEqualTo("Bearer");
        mockMvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + body.get("accessToken").asString()))
                .andExpect(status().isOk());
        refresh(next).andExpect(status().isOk());
    }

    @Test
    void 발급_후_14일이_되면_재발급할_수_없고_재발급한_토큰은_14일을_새로_받는다() throws Exception {
        String first = cookieValue(login().andReturn().getResponse());
        String second = cookieValue(login().andReturn().getResponse());

        clock.advance(TTL.minusSeconds(1));
        String renewed = cookieValue(refresh(first).andExpect(status().isOk()).andReturn().getResponse());

        clock.advance(Duration.ofSeconds(1));
        assertUnauthenticated(refresh(second));
        // 14일 - 1초에 재발급한 토큰은 그때부터 14일이다(연장 만료).
        String again = cookieValue(refresh(renewed).andExpect(status().isOk()).andReturn().getResponse());

        clock.advance(TTL.minusSeconds(1));
        refresh(again).andExpect(status().isOk());
    }

    @Test
    void 교체된_토큰을_유예_시간이_지난_뒤_다시_쓰면_같은_로그인의_토큰이_모두_폐기된다() throws Exception {
        String old = cookieValue(login().andReturn().getResponse());
        String latest = cookieValue(refresh(old).andReturn().getResponse());

        clock.advance(GRACE);
        assertUnauthenticated(refresh(old));

        assertUnauthenticated(refresh(latest));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_token WHERE revoked_at IS NULL", Integer.class)).isZero();
    }

    @Test
    void 교체된_토큰을_유예_시간_안에_다시_쓰면_거절만_하고_최신_토큰은_계속_쓴다() throws Exception {
        String old = cookieValue(login().andReturn().getResponse());
        String latest = cookieValue(refresh(old).andReturn().getResponse());

        clock.advance(GRACE.minusSeconds(1));
        assertUnauthenticated(refresh(old));

        refresh(latest).andExpect(status().isOk());
    }

    @Test
    void 재사용이_감지되어도_다른_로그인의_토큰은_계속_쓴다() throws Exception {
        String old = cookieValue(login().andReturn().getResponse());
        String otherLogin = cookieValue(login().andReturn().getResponse());
        refresh(old).andExpect(status().isOk());

        clock.advance(GRACE);
        assertUnauthenticated(refresh(old));

        refresh(otherLogin).andExpect(status().isOk());
    }

    @Test
    void CSRF_방어를_통과하지_못한_재발급은_토큰을_건드리지_않고_거절된다() throws Exception {
        String refreshToken = cookieValue(login().andReturn().getResponse());

        assertCsrfRejected(mockMvc.perform(post("/api/auth/refresh").cookie(new Cookie(COOKIE, refreshToken))));
        assertCsrfRejected(mockMvc.perform(
                refreshRequest(refreshToken).header(HttpHeaders.ORIGIN, "https://evil.example")));
        // 같은 사이트의 다른 서브도메인은 SameSite로 막히지 않으므로 출처로 막는다.
        assertCsrfRejected(mockMvc.perform(
                refreshRequest(refreshToken).header(HttpHeaders.ORIGIN, "https://other.govbenefit.kr")));
        assertCsrfRejected(mockMvc.perform(post("/api/auth/refresh").cookie(new Cookie(COOKIE, refreshToken))
                .header("X-Requested-With", " ")));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_token WHERE rotated_at IS NOT NULL", Integer.class)).isZero();

        mockMvc.perform(refreshRequest(refreshToken).header(HttpHeaders.ORIGIN, "https://govbenefit.kr"))
                .andExpect(status().isOk());
    }

    @Test
    void 리프레시_토큰이_없거나_형식이_틀렸거나_없는_토큰이면_401이다() throws Exception {
        assertUnauthenticated(mockMvc.perform(post("/api/auth/refresh").header("X-Requested-With", "fetch")));
        assertUnauthenticated(refresh("not-a-token"));
        assertUnauthenticated(refresh(OpaqueTokens.generate()));
    }

    @Test
    void 재발급과_로그아웃은_Authorization_헤더를_보지_않는다() throws Exception {
        String refreshToken = cookieValue(login().andReturn().getResponse());
        Long userId = jdbcTemplate.queryForObject("SELECT id FROM user_account", Long.class);
        String expired = accessTokenIssuer.issue(userId, clock.instant().minus(Duration.ofMinutes(16))).value();

        String next = cookieValue(mockMvc.perform(refreshRequest(refreshToken)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
                .andExpect(status().isOk()).andReturn().getResponse());
        mockMvc.perform(post("/api/auth/logout").cookie(new Cookie(COOKIE, next))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isNoContent());
        // 그 밖의 공개 경로는 보낸 토큰을 검증한다(결정 0018).
        mockMvc.perform(post("/api/auth/login").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@example.com\",\"password\":\"alice-password\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 로그아웃하면_그_로그인의_리프레시_토큰을_쓸_수_없고_액세스_토큰은_만료까지_유효하다() throws Exception {
        MockHttpServletResponse loggedIn = login().andReturn().getResponse();
        String accessToken = jsonMapper.readTree(loggedIn.getContentAsString(StandardCharsets.UTF_8))
                .get("accessToken").asString();
        String old = cookieValue(loggedIn);
        String latest = cookieValue(refresh(old).andReturn().getResponse());

        MockHttpServletResponse response = mockMvc.perform(post("/api/auth/logout").cookie(new Cookie(COOKIE, latest)))
                .andExpect(status().isNoContent()).andReturn().getResponse();

        assertThat(response.getHeader(HttpHeaders.SET_COOKIE))
                .startsWith(COOKIE + "=;").contains("Max-Age=0", "Path=/api/auth");
        assertThat(response.getContentAsByteArray()).isEmpty();
        assertUnauthenticated(refresh(latest));
        mockMvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk());
    }

    @Test
    void 쿠키가_없거나_쓸_수_없는_토큰으로_로그아웃해도_204다() throws Exception {
        mockMvc.perform(post("/api/auth/logout")).andExpect(status().isNoContent());
        mockMvc.perform(post("/api/auth/logout").cookie(new Cookie(COOKIE, "not-a-token")))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/auth/logout").cookie(new Cookie(COOKIE, OpaqueTokens.generate())))
                .andExpect(status().isNoContent());
    }

    @Test
    void DB에는_리프레시_토큰의_해시만_저장된다() throws Exception {
        String old = cookieValue(login().andReturn().getResponse());
        String latest = cookieValue(refresh(old).andReturn().getResponse());

        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM refresh_token");
        assertThat(rows).hasSize(2);
        for (Map<String, Object> row : rows) {
            assertThat(row.values()).allSatisfy(value -> assertThat(String.valueOf(value))
                    .doesNotContain(old).doesNotContain(latest));
        }
        assertThat(rows).extracting(row -> row.get("token_hash"))
                .containsExactlyInAnyOrder(OpaqueTokens.hash(old), OpaqueTokens.hash(latest));
    }

    private ResultActions login() throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(Map.of("email", "alice@example.com", "password", "alice-password"))));
    }

    private MockHttpServletRequestBuilder refreshRequest(String refreshToken) {
        return post("/api/auth/refresh").cookie(new Cookie(COOKIE, refreshToken)).header("X-Requested-With", "fetch");
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mockMvc.perform(refreshRequest(refreshToken));
    }

    private void assertUnauthenticated(ResultActions result) throws Exception {
        MockHttpServletResponse response = result.andExpect(status().isUnauthorized()).andReturn().getResponse();
        JsonNode body = jsonMapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("code", "message", "errors");
        assertThat(body.get("code").asString()).isEqualTo("UNAUTHENTICATED");
        assertThat(body.get("errors").size()).isZero();
        // 거절할 때 쿠키를 지우지 않는다(동시 재발급에서 이긴 응답의 쿠키를 지우지 않게).
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();
    }

    private void assertCsrfRejected(ResultActions result) throws Exception {
        JsonNode body = jsonMapper.readTree(result.andExpect(status().isForbidden()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.get("code").asString()).isEqualTo("CSRF_REJECTED");
    }

    private static String cookieValue(MockHttpServletResponse response) {
        String setCookie = response.getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).as("Set-Cookie").isNotNull().startsWith(COOKIE + "=");
        return setCookie.substring(COOKIE.length() + 1, setCookie.indexOf(';'));
    }
}
