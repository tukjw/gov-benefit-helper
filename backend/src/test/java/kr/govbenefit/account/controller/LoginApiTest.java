package kr.govbenefit.account.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import kr.govbenefit.TestcontainersConfiguration;
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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 로그인과 내 정보 API. 로그인 실패 응답으로 가입 여부를 알 수 없어야 한다(결정 0013).
 */
@SpringBootTest(properties = "gov24.schedule.enabled=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LoginApiTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private JsonMapper jsonMapper;
    @Autowired
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() throws Exception {
        jdbcTemplate.update("DELETE FROM user_account");
        signUp("alice@example.com", "alice-password");
        signUp("bob@example.com", "bob-password");
    }

    @Test
    void 로그인하면_15분짜리_액세스_토큰을_받는다() throws Exception {
        Instant before = Instant.now();
        JsonNode body = json(login("alice@example.com", "alice-password").andExpect(status().isOk()));

        assertThat(body.propertyNames()).containsExactlyInAnyOrder("accessToken", "tokenType", "expiresAt");
        assertThat(body.get("tokenType").asString()).isEqualTo("Bearer");
        Jwt jwt = jwtDecoder.decode(body.get("accessToken").asString());
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
        assertThat(jwt.getIssuedAt()).isBetween(before.minusSeconds(1), Instant.now());
        assertThat(jwt.getClaimAsString("token_use")).isEqualTo("access");

        String expiresAt = body.get("expiresAt").asString();
        assertThat(expiresAt).endsWith("+09:00");
        assertThat(OffsetDateTime.parse(expiresAt).toInstant()).isEqualTo(jwt.getExpiresAt());
    }

    @Test
    void 토큰으로_내_정보를_부르면_토큰_주인의_정보만_돌아온다() throws Exception {
        String aliceToken = token("alice@example.com", "alice-password");
        String bobToken = token("bob@example.com", "bob-password");

        JsonNode alice = json(me(aliceToken).andExpect(status().isOk()));
        JsonNode bob = json(me(bobToken).andExpect(status().isOk()));

        assertThat(alice.propertyNames()).containsExactlyInAnyOrder("email", "createdAt");
        assertThat(alice.get("email").asString()).isEqualTo("alice@example.com");
        assertThat(alice.get("createdAt").asString()).endsWith("+09:00");
        assertThat(bob.get("email").asString()).isEqualTo("bob@example.com");
    }

    @Test
    void 이메일은_대소문자를_구분하지_않는다() throws Exception {
        String token = token("ALICE@Example.com", "alice-password");

        assertThat(json(me(token)).get("email").asString()).isEqualTo("alice@example.com");
    }

    @Test
    void 비밀번호가_틀린_경우와_가입되지_않은_이메일은_같은_응답이다() throws Exception {
        MockHttpServletResponse wrongPassword = login("alice@example.com", "wrong-password").andReturn().getResponse();
        MockHttpServletResponse unknownEmail = login("nobody@example.com", "alice-password").andReturn().getResponse();
        // 규칙(72바이트)을 넘는 비밀번호와 형식이 틀린 이메일도 같은 실패다.
        MockHttpServletResponse tooLong = login("alice@example.com", "가".repeat(25)).andReturn().getResponse();
        MockHttpServletResponse notAnEmail = login("not-an-email", "alice-password").andReturn().getResponse();

        for (MockHttpServletResponse response : new MockHttpServletResponse[] {unknownEmail, tooLong, notAnEmail}) {
            assertThat(response.getStatus()).isEqualTo(wrongPassword.getStatus()).isEqualTo(401);
            assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                    .isEqualTo(wrongPassword.getContentAsString(StandardCharsets.UTF_8));
            assertThat(response.getHeaderNames()).containsExactlyInAnyOrderElementsOf(wrongPassword.getHeaderNames());
        }
        JsonNode body = jsonMapper.readTree(wrongPassword.getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.get("code").asString()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(body.get("errors").size()).isZero();
    }

    /**
     * BCrypt는 앞 72바이트만 비교하므로 {@code matches}에 넘기면 72바이트 비밀번호 뒤에 글자를 붙여도 맞다고 한다(결정 0013).
     */
    @Test
    void 비밀번호_72바이트_뒤에_글자를_붙이면_로그인할_수_없다() throws Exception {
        String password = "p".repeat(72);
        signUp("long@example.com", password);

        login("long@example.com", password).andExpect(status().isOk());
        JsonNode body = json(login("long@example.com", password + "x").andExpect(status().isUnauthorized()));
        assertThat(body.get("code").asString()).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void 빈_이메일이나_비밀번호는_잘못된_입력이다() throws Exception {
        JsonNode body = json(login("", "").andExpect(status().isBadRequest()));

        assertThat(body.get("code").asString()).isEqualTo("INVALID_INPUT");
        assertThat(body.get("errors").valueStream().map(error -> error.get("field").asString()))
                .containsExactlyInAnyOrder("email", "password");
        body.get("errors").forEach(error -> {
            if (error.get("field").asString().equals("password")) {
                assertThat(error.get("value").isNull()).isTrue();
            }
        });
    }

    private void signUp(String email, String password) throws Exception {
        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isCreated());
    }

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(Map.of("email", email, "password", password))));
    }

    private String token(String email, String password) throws Exception {
        return json(login(email, password).andExpect(status().isOk())).get("accessToken").asString();
    }

    private ResultActions me(String token) throws Exception {
        return mockMvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private JsonNode json(ResultActions result) throws Exception {
        return jsonMapper.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
