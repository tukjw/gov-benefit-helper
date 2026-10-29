package kr.govbenefit.account.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kr.govbenefit.FixedClockConfiguration;
import kr.govbenefit.MutableClock;
import kr.govbenefit.TestcontainersConfiguration;
import kr.govbenefit.account.SignUpSteps;
import kr.govbenefit.account.mail.RecordingAccountMailSender;
import kr.govbenefit.benefit.domain.ApplyPeriodRule;
import kr.govbenefit.benefit.domain.Benefit;
import kr.govbenefit.benefit.domain.BenefitContent;
import kr.govbenefit.benefit.domain.BenefitNormalization;
import kr.govbenefit.benefit.repository.BenefitRepository;
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
 * 탈퇴(REQ-ACCOUNT-01, DEC-AUTH). 비밀번호를 다시 확인하고 계정을 지우면 프로필·북마크·토큰이 FK로 함께 지워진다.
 * 같은 이메일을 다시 인증하려면 발송 간격이 지나야 하므로 고정한 시계로 옮긴다.
 */
@SpringBootTest(properties = "gov24.schedule.enabled=false")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
class WithdrawalApiTest {

    private static final Instant START = Instant.parse("2026-10-19T01:00:00Z");
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final Duration COOLDOWN = Duration.ofSeconds(60);
    private static final String ALICE = "alice@example.com";
    private static final String BOB = "bob@example.com";
    private static final String PASSWORD = "correct-horse";
    private static final String COOKIE = "refresh_token";
    /** 사용자 데이터가 있는 테이블. 모두 user_id로 계정을 가리킨다. */
    private static final List<String> USER_TABLES = List.of(
            "user_profile", "user_profile_attribute", "refresh_token", "password_reset_token", "bookmark");
    private static final String BENEFIT_ID = "WITHDRAWAL-BENEFIT";

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
    private BenefitRepository benefitRepository;

    private SignUpSteps signUpSteps;

    @BeforeEach
    void setUp() {
        clock.set(START);
        benefitRepository.save(Benefit.create(BENEFIT_ID, new BenefitContent("탈퇴 테스트 혜택", null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null),
                new BenefitNormalization(null, null, ApplyPeriodRule.ALWAYS), LocalDateTime.ofInstant(START, ZONE)));
        mailSender.clear();
        signUpSteps = new SignUpSteps(mockMvc, jsonMapper, mailSender);
    }

    @Test
    void 탈퇴하면_204이고_리프레시_토큰_쿠키를_지운다() throws Exception {
        Session alice = signUpAndLogin(ALICE, PASSWORD);

        MockHttpServletResponse response = withdraw(alice.accessToken(), PASSWORD)
                .andExpect(status().isNoContent()).andReturn().getResponse();

        assertThat(response.getContentAsString()).isEmpty();
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE))
                .startsWith(COOKIE + "=;").contains("Max-Age=0", "Path=/api/auth");
    }

    @Test
    void 탈퇴하면_계정과_프로필_속성_북마크_토큰이_남지_않는다() throws Exception {
        Session alice = signUpAndLogin(ALICE, PASSWORD);
        Long aliceId = userId(ALICE);
        saveProfile(alice.accessToken()).andExpect(status().isOk());
        saveBookmark(alice.accessToken()).andExpect(status().isNoContent());
        requestPasswordReset(ALICE).andExpect(status().isNoContent());
        for (String table : USER_TABLES) {
            assertThat(count(table, aliceId)).as(table).isPositive();
        }

        withdraw(alice.accessToken(), PASSWORD).andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM user_account WHERE id = ?", Integer.class,
                aliceId)).isZero();
        for (String table : USER_TABLES) {
            assertThat(count(table, aliceId)).as(table).isZero();
        }
    }

    @Test
    void 탈퇴_뒤_남은_액세스_토큰으로는_개인_API를_쓸_수_없다() throws Exception {
        Session alice = signUpAndLogin(ALICE, PASSWORD);
        saveProfile(alice.accessToken()).andExpect(status().isOk());

        withdraw(alice.accessToken(), PASSWORD).andExpect(status().isNoContent());

        assertUnauthenticated(mockMvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, alice.accessToken())));
        assertUnauthenticated(mockMvc.perform(get("/api/me/profile")
                .header(HttpHeaders.AUTHORIZATION, alice.accessToken())));
        assertUnauthenticated(saveProfile(alice.accessToken()));
        assertUnauthenticated(withdraw(alice.accessToken(), PASSWORD));
    }

    @Test
    void 탈퇴_뒤_남은_리프레시_토큰으로는_재발급할_수_없다() throws Exception {
        Session alice = signUpAndLogin(ALICE, PASSWORD);

        withdraw(alice.accessToken(), PASSWORD).andExpect(status().isNoContent());

        assertUnauthenticated(refresh(alice.refreshToken()));
    }

    @Test
    void 같은_이메일로_다시_가입하면_이전_프로필이_없는_새_사용자다() throws Exception {
        Session before = signUpAndLogin(ALICE, PASSWORD);
        Long beforeId = userId(ALICE);
        saveProfile(before.accessToken()).andExpect(status().isOk());
        withdraw(before.accessToken(), PASSWORD).andExpect(status().isNoContent());
        clock.advance(COOLDOWN);

        Session after = signUpAndLogin(ALICE, "another-password");

        assertThat(userId(ALICE)).isNotEqualTo(beforeId);
        JsonNode profile = json(mockMvc.perform(get("/api/me/profile")
                .header(HttpHeaders.AUTHORIZATION, after.accessToken())).andExpect(status().isOk()));
        assertThat(profile.get("sido").isNull()).isTrue();
        assertThat(profile.get("lifeAttributes").isNull()).isTrue();
        assertThat(profile.get("updatedAt").isNull()).isTrue();
        // 탈퇴 전 토큰의 sub는 새 계정을 가리키지 않는다(결정 0014).
        assertUnauthenticated(mockMvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, before.accessToken())));
        assertUnauthenticated(refresh(before.refreshToken()));
    }

    @Test
    void 토큰_없는_탈퇴_요청은_거절하고_계정이_남는다() throws Exception {
        signUpAndLogin(ALICE, PASSWORD);

        assertUnauthenticated(mockMvc.perform(post("/api/me/withdrawal")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(Map.of("password", PASSWORD)))));

        assertThat(userId(ALICE)).isNotNull();
    }

    @Test
    void 비밀번호가_틀리면_거절하고_아무것도_지우지_않는다() throws Exception {
        String longPassword = "a".repeat(72);
        Session alice = signUpAndLogin(ALICE, longPassword);
        Long aliceId = userId(ALICE);
        saveProfile(alice.accessToken()).andExpect(status().isOk());

        assertBadRequest(withdraw(alice.accessToken(), "wrong-password"), "PASSWORD_MISMATCH");
        // BCrypt는 앞 72바이트만 비교하므로 넘는 입력은 비교하지 않고 불일치다(결정 0013).
        assertBadRequest(withdraw(alice.accessToken(), longPassword + "x"), "PASSWORD_MISMATCH");
        assertBadRequest(withdraw(alice.accessToken(), ""), "INVALID_INPUT");
        assertBadRequest(withdraw(alice.accessToken(), null), "INVALID_INPUT");

        for (String table : List.of("user_profile", "user_profile_attribute", "refresh_token")) {
            assertThat(count(table, aliceId)).as(table).isPositive();
        }
        refresh(alice.refreshToken()).andExpect(status().isOk());
        withdraw(alice.accessToken(), longPassword).andExpect(status().isNoContent());
    }

    @Test
    void 한_사용자의_탈퇴는_다른_사용자에게_영향을_주지_않는다() throws Exception {
        Session alice = signUpAndLogin(ALICE, PASSWORD);
        Session bob = signUpAndLogin(BOB, PASSWORD);
        Long bobId = userId(BOB);
        saveProfile(bob.accessToken()).andExpect(status().isOk());
        saveBookmark(bob.accessToken()).andExpect(status().isNoContent());
        requestPasswordReset(BOB).andExpect(status().isNoContent());

        // 본문에 다른 사용자의 ID를 넣어도 토큰 주인만 탈퇴한다(결정 0018).
        Map<String, Object> body = new HashMap<>();
        body.put("password", PASSWORD);
        body.put("userId", bobId);
        mockMvc.perform(post("/api/me/withdrawal")
                        .header(HttpHeaders.AUTHORIZATION, alice.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(body)))
                .andExpect(status().isNoContent());

        assertThat(userId(ALICE)).isNull();
        assertThat(userId(BOB)).isEqualTo(bobId);
        for (String table : USER_TABLES) {
            assertThat(count(table, bobId)).as(table).isPositive();
        }
        mockMvc.perform(get("/api/me/profile").header(HttpHeaders.AUTHORIZATION, bob.accessToken()))
                .andExpect(status().isOk());
        refresh(bob.refreshToken()).andExpect(status().isOk());
    }

    private Session signUpAndLogin(String email, String password) throws Exception {
        signUpSteps.signUp(email, password);
        ResultActions login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isOk());
        String setCookie = login.andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);
        return new Session("Bearer " + json(login).get("accessToken").asString(),
                setCookie.substring((COOKIE + "=").length(), setCookie.indexOf(';')));
    }

    private ResultActions withdraw(String accessToken, String password) throws Exception {
        Map<String, String> body = new HashMap<>();
        body.put("password", password);
        return mockMvc.perform(post("/api/me/withdrawal")
                .header(HttpHeaders.AUTHORIZATION, accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(body)));
    }

    private ResultActions saveProfile(String accessToken) throws Exception {
        return mockMvc.perform(put("/api/me/profile")
                .header(HttpHeaders.AUTHORIZATION, accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"birthDate": "1995-03-01", "sidoCode": "6110000", "lifeAttributes": ["EMPLOYEE"]}"""));
    }

    private ResultActions saveBookmark(String accessToken) throws Exception {
        return mockMvc.perform(put("/api/me/bookmarks/" + BENEFIT_ID).header(HttpHeaders.AUTHORIZATION, accessToken));
    }

    private ResultActions requestPasswordReset(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/password-reset")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(Map.of("email", email))));
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/auth/refresh")
                .header("X-Requested-With", "fetch")
                .cookie(new Cookie(COOKIE, refreshToken)));
    }

    private Long userId(String email) {
        List<Long> ids = jdbcTemplate.queryForList("SELECT id FROM user_account WHERE email = ?", Long.class, email);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private int count(String table, Long userId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id = ?", Integer.class,
                userId);
    }

    private void assertUnauthenticated(ResultActions result) throws Exception {
        assertError(result.andExpect(status().isUnauthorized()), "UNAUTHENTICATED");
    }

    private void assertBadRequest(ResultActions result, String code) throws Exception {
        assertError(result.andExpect(status().isBadRequest()), code);
    }

    private void assertError(ResultActions result, String code) throws Exception {
        assertThat(json(result).get("code").asString()).isEqualTo(code);
    }

    private JsonNode json(ResultActions result) throws Exception {
        return jsonMapper.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /**
     * @param accessToken  {@code Bearer }를 붙인 Authorization 헤더 값
     * @param refreshToken 리프레시 토큰 쿠키 값
     */
    private record Session(String accessToken, String refreshToken) {
    }
}
