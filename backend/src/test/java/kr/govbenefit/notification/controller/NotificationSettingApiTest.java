package kr.govbenefit.notification.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kr.govbenefit.FixedClockConfiguration;
import kr.govbenefit.MutableClock;
import kr.govbenefit.TestcontainersConfiguration;
import kr.govbenefit.account.domain.UserAccount;
import kr.govbenefit.account.repository.UserAccountRepository;
import kr.govbenefit.benefit.domain.ApplyPeriodRule;
import kr.govbenefit.benefit.domain.Benefit;
import kr.govbenefit.benefit.domain.BenefitContent;
import kr.govbenefit.benefit.domain.BenefitNormalization;
import kr.govbenefit.benefit.repository.BenefitRepository;
import kr.govbenefit.common.security.AccessTokenIssuer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 새 혜택 알림 켜기·끄기(REQ-NOTIFY-03, DEC-NOTIFY).
 */
@SpringBootTest(properties = "gov24.schedule.enabled=false")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
class NotificationSettingApiTest {

    /** 한국 시간 2026-11-03 10:00 */
    private static final Instant START = Instant.parse("2026-11-03T01:00:00Z");
    private static final String PATH = "/api/me/notification-settings";
    private static final String BENEFIT_ID = "NOTIFY-SETTING-TEST";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AccessTokenIssuer accessTokenIssuer;
    @Autowired
    private UserAccountRepository userAccountRepository;
    @Autowired
    private BenefitRepository benefitRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private JsonMapper jsonMapper;
    @Autowired
    private MutableClock clock;

    private Long aliceId;
    private Long bobId;

    @BeforeEach
    void setUp() {
        clock.set(START);
        LocalDateTime now = LocalDateTime.now(clock);
        aliceId = userAccountRepository.save(UserAccount.create("alice@example.com", "{noop}x", now)).getId();
        bobId = userAccountRepository.save(UserAccount.create("bob@example.com", "{noop}x", now)).getId();
    }

    @Test
    void 설정한_적이_없으면_꺼짐이다() throws Exception {
        JsonNode body = json(getSetting(aliceId).andExpect(status().isOk()));

        assertThat(names(body)).containsExactly("newBenefit");
        assertThat(body.get("newBenefit").asBoolean()).isFalse();
    }

    @Test
    void 켜고_끈_값이_조회되고_같은_값을_다시_보내도_결과가_같다() throws Exception {
        for (boolean enabled : List.of(true, true, false, false)) {
            String changed = changeSetting(aliceId, body("newBenefit", enabled)).andExpect(status().isNoContent())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

            assertThat(changed).isEmpty();
            assertThat(json(getSetting(aliceId)).get("newBenefit").asBoolean()).isEqualTo(enabled);
            assertThat(storedValue(aliceId)).isEqualTo(enabled);
        }
    }

    @Test
    void 새_혜택_알림_값이_없거나_본문을_읽을_수_없으면_거절하고_값을_바꾸지_않는다() throws Exception {
        changeSetting(aliceId, body("newBenefit", true)).andExpect(status().isNoContent());

        for (String content : List.of("{}", "{\"newBenefit\": null}")) {
            JsonNode body = json(changeSetting(aliceId, content).andExpect(status().isBadRequest()));
            assertThat(body.get("code").asString()).as(content).isEqualTo("INVALID_INPUT");
            assertThat(body.get("errors").get(0).get("field").asString()).as(content).isEqualTo("newBenefit");
        }
        for (String content : List.of("", "{")) {
            JsonNode body = json(changeSetting(aliceId, content).andExpect(status().isBadRequest()));
            assertThat(body.get("code").asString()).as(content).isEqualTo("INVALID_INPUT");
        }
        assertThat(storedValue(aliceId)).isTrue();
    }

    @Test
    void 북마크를_저장하거나_해제해도_알림_설정은_바뀌지_않는다() throws Exception {
        benefitRepository.save(Benefit.create(BENEFIT_ID, new BenefitContent("알림 설정 테스트 혜택", null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null),
                new BenefitNormalization(null, null, ApplyPeriodRule.ALWAYS), LocalDateTime.now(clock)));

        saveBookmark(aliceId).andExpect(status().isNoContent());
        assertThat(json(getSetting(aliceId)).get("newBenefit").asBoolean()).isFalse();

        changeSetting(aliceId, body("newBenefit", true)).andExpect(status().isNoContent());
        removeBookmark(aliceId).andExpect(status().isNoContent());
        saveBookmark(aliceId).andExpect(status().isNoContent());
        assertThat(json(getSetting(aliceId)).get("newBenefit").asBoolean()).isTrue();
    }

    @Test
    void 요청에_다른_사용자_ID를_넣어도_그_사용자의_설정을_읽거나_바꿀_수_없다() throws Exception {
        changeSetting(aliceId, body("newBenefit", true, "userId", bobId, "id", bobId))
                .andExpect(status().isNoContent());

        assertThat(storedValue(aliceId)).isTrue();
        assertThat(storedValue(bobId)).isFalse();
        JsonNode aliceView = json(mockMvc.perform(get(PATH).param("userId", String.valueOf(bobId))
                .header(HttpHeaders.AUTHORIZATION, bearer(aliceId))).andExpect(status().isOk()));
        assertThat(aliceView.get("newBenefit").asBoolean()).isTrue();
    }

    @Test
    void 인증되지_않은_요청은_거절한다() throws Exception {
        for (ResultActions result : List.of(
                mockMvc.perform(get(PATH)),
                mockMvc.perform(patch(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"newBenefit\": true}")))) {
            JsonNode body = json(result.andExpect(status().isUnauthorized()));
            assertThat(body.get("code").asString()).isEqualTo("UNAUTHENTICATED");
        }
        assertThat(storedValue(aliceId)).isFalse();
        assertThat(storedValue(bobId)).isFalse();
    }

    @Test
    void 계정이_없는_토큰의_주인은_401이다() throws Exception {
        String token = bearer(aliceId);
        jdbcTemplate.update("DELETE FROM user_account WHERE id = ?", aliceId);

        assertThat(json(mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isUnauthorized())).get("code").asString()).isEqualTo("UNAUTHENTICATED");
        assertThat(json(mockMvc.perform(patch(PATH).header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"newBenefit\": true}"))
                .andExpect(status().isUnauthorized())).get("code").asString()).isEqualTo("UNAUTHENTICATED");
    }

    private boolean storedValue(Long userId) {
        return jdbcTemplate.queryForObject("SELECT new_benefit_notify FROM user_account WHERE id = ?", Boolean.class,
                userId);
    }

    private ResultActions getSetting(Long userId) throws Exception {
        return mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, bearer(userId)));
    }

    private ResultActions changeSetting(Long userId, Map<String, Object> body) throws Exception {
        return changeSetting(userId, jsonMapper.writeValueAsString(body));
    }

    private ResultActions changeSetting(Long userId, String content) throws Exception {
        return mockMvc.perform(patch(PATH)
                .header(HttpHeaders.AUTHORIZATION, bearer(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(content));
    }

    private ResultActions saveBookmark(Long userId) throws Exception {
        return mockMvc.perform(put("/api/me/bookmarks/" + BENEFIT_ID).header(HttpHeaders.AUTHORIZATION, bearer(userId)));
    }

    private ResultActions removeBookmark(Long userId) throws Exception {
        return mockMvc.perform(delete("/api/me/bookmarks/" + BENEFIT_ID)
                .header(HttpHeaders.AUTHORIZATION, bearer(userId)));
    }

    private String bearer(Long userId) {
        return "Bearer " + accessTokenIssuer.issue(userId).value();
    }

    /** null 값도 넣을 수 있게 {@link Map#of} 대신 쓴다. */
    private static Map<String, Object> body(Object... keyValues) {
        Map<String, Object> body = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            body.put((String) keyValues[i], keyValues[i + 1]);
        }
        return body;
    }

    private JsonNode json(ResultActions result) throws Exception {
        return jsonMapper.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static List<String> names(JsonNode object) {
        return new ArrayList<>(object.propertyNames());
    }
}
