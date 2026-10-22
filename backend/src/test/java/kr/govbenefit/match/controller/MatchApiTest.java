package kr.govbenefit.match.controller;

import static kr.govbenefit.collection.domain.SourceEndpoint.SERVICE_LIST;
import static kr.govbenefit.collection.domain.SourceEndpoint.SUPPORT_CONDITIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kr.govbenefit.MutableClock;
import kr.govbenefit.TestcontainersConfiguration;
import kr.govbenefit.account.SignUpSteps;
import kr.govbenefit.account.mail.RecordingAccountMailSender;
import kr.govbenefit.collection.Gov24StubServer;
import kr.govbenefit.collection.service.CollectionService;
import kr.govbenefit.common.security.AccessTokenIssuer;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 조건 비교(REQ-MATCH-01, DEC-MATCH)를 Testcontainers MySQL로 검증한다. 혜택과 지원조건은 로컬 stub으로 실제 수집해 넣고,
 * stub 레코드를 바꿔 결과별 사례를 만든다. 세부 판정 규칙은 {@code ConditionMatcherTest}가 검증한다.
 */
@SpringBootTest(properties = {"gov24.schedule.enabled=false", "gov24.retry-delay=10ms"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MatchApiTest.FixedClockConfig.class})
class MatchApiTest {

    /** 서울 날짜 2026-10-20 */
    private static final Instant START = Instant.parse("2026-10-20T01:00:00Z");
    private static final String ALICE = "alice@example.com";
    private static final String BOB = "bob@example.com";
    private static final String PASSWORD = "correct-horse";
    private static final String PATH = "/api/me/matches/";
    /**
     * stub fixture의 실제 레코드. 사용자구분 개인, 성별 둘 다 Y, 연령 19~120, 생애는 축산업인만 Y, 가구 전부 Y.
     */
    private static final String HANWOO = "511000000138";
    /** 2026-10-20에 만 36세 */
    private static final String BIRTH_DATE = "1990-01-01";

    private static final Gov24StubServer stub = Gov24StubServer.start();

    @DynamicPropertySource
    static void gov24Properties(DynamicPropertyRegistry registry) {
        registry.add("gov24.base-url", stub::baseUrl);
        registry.add("gov24.service-key", () -> "stub-service-key-8e4a1c");
    }

    @AfterAll
    static void closeStub() {
        stub.close();
    }

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
    private RecordingAccountMailSender mailSender;
    @Autowired
    private AccessTokenIssuer accessTokenIssuer;
    @Autowired
    private CollectionService collectionService;

    private SignUpSteps signUpSteps;

    @BeforeEach
    void setUp() throws Exception {
        clock.set(START);
        stub.reset();
        for (String table : List.of("bookmark", "benefit_detail", "benefit", "source_record_history",
                "source_record", "collection_quarantine", "collection_failure", "collection_run_step",
                "collection_run", "user_account", "email_verification")) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        mailSender.clear();
        signUpSteps = new SignUpSteps(mockMvc, jsonMapper, mailSender);
        signUpSteps.signUp(ALICE, PASSWORD);
    }

    @Test
    void 코드상_모두_충족하면_부합이고_비교한_값과_원문이_함께_온다() throws Exception {
        collectionService.collectAll();
        putProfile(ALICE, profile("FEMALE", List.of("LIVESTOCK_FARMER"), List.of("NONE")));

        JsonNode body = match(ALICE, HANWOO);

        assertThat(body.get("serviceId").asString()).isEqualTo(HANWOO);
        assertThat(body.get("name").asString()).isEqualTo("한우 친자확인 지원");
        assertThat(body.get("result").asString()).isEqualTo("MATCH");
        assertThat(body.get("judgedOn").asString()).isEqualTo("2026-10-20");
        assertThat(texts(body.get("notices"))).containsExactly("CODE_BASED", "GROUP_RULE_INTERPRETED", "NOT_FINAL");
        assertThat(texts(body.get("uncomparedConditions")))
                .containsExactly("INCOME", "RESIDENCE", "OTHER_SOURCE_CONDITIONS");

        JsonNode items = body.get("items");
        assertItem(items.get("personalTarget"), "MET", "PERSONAL_USER_TYPE");
        assertThat(items.get("personalTarget").get("userType").asString()).isEqualTo("개인");
        assertItem(items.get("age"), "MET", "CODE_INCLUDES");
        assertThat(items.get("age").get("ageStart").asInt()).isEqualTo(19);
        assertThat(items.get("age").get("ageEnd").asInt()).isEqualTo(120);
        assertThat(items.get("age").get("profileAge").asInt()).isEqualTo(36);
        assertItem(items.get("gender"), "MET", "NO_RESTRICTION");
        assertThat(texts(items.get("gender").get("allowedGenders"))).containsExactly("MALE", "FEMALE");
        assertThat(items.get("gender").get("profileGender").asString()).isEqualTo("FEMALE");
        assertItem(items.get("lifeAttributes"), "MET", "CODE_INCLUDES");
        assertThat(texts(items.get("lifeAttributes").get("includedAttributes"))).containsExactly("LIVESTOCK_FARMER");
        assertThat(texts(items.get("lifeAttributes").get("profileAttributes"))).containsExactly("LIVESTOCK_FARMER");
        assertItem(items.get("householdAttributes"), "MET", "NO_RESTRICTION");
        assertThat(items.get("householdAttributes").get("includedAttributes")).hasSize(9);
        assertThat(texts(items.get("householdAttributes").get("profileAttributes"))).containsExactly("NONE");

        JsonNode source = body.get("source");
        assertThat(source.get("target").asString()).isEqualTo("○ 관내 한우농가");
        assertThat(source.get("selectionCriteria").isNull()).isTrue();
        assertThat(source.get("guideUrl").asString()).isEqualTo("https://www.gov.kr/portal/rcvfvrSvc/dtlEx/" + HANWOO);
    }

    @Test
    void 코드상_벗어나거나_경계값이면_확인_필요이고_이유와_원문이_함께_온다() throws Exception {
        stub.record(SUPPORT_CONDITIONS, HANWOO).put("JA0110", 36);
        collectionService.collectAll();
        putProfile(ALICE, profile("FEMALE", List.of("EMPLOYEE"), List.of("NONE")));

        JsonNode body = match(ALICE, HANWOO);

        assertThat(body.get("result").asString()).isEqualTo("NEEDS_CHECK");
        assertItem(body.get("items").get("age"), "NEEDS_CHECK", "AGE_ON_BOUNDARY");
        assertItem(body.get("items").get("lifeAttributes"), "NEEDS_CHECK", "CODE_EXCLUDES");
        assertItem(body.get("items").get("gender"), "MET", "NO_RESTRICTION");
        assertThat(texts(body.get("notices"))).contains("NOT_FINAL");
        assertThat(body.get("source").get("target").asString()).isEqualTo("○ 관내 한우농가");
        assertThat(body.get("source").get("guideUrl").asString()).endsWith(HANWOO);
    }

    @Test
    void 개인_대상이_아닌_혜택만_불일치이고_나머지_항목은_판정하지_않는다() throws Exception {
        stub.record(SERVICE_LIST, HANWOO).put("사용자구분", "법인/시설/단체");
        collectionService.collectAll();
        putProfile(ALICE, profile("FEMALE", List.of("LIVESTOCK_FARMER"), List.of("NONE")));

        JsonNode body = match(ALICE, HANWOO);

        assertThat(body.get("result").asString()).isEqualTo("MISMATCH");
        assertThat(texts(body.get("notices"))).containsExactly("NOT_FINAL");
        assertItem(body.get("items").get("personalTarget"), "NOT_MET", "NON_PERSONAL_USER_TYPE");
        assertThat(body.get("items").get("personalTarget").get("userType").asString()).isEqualTo("법인/시설/단체");
        for (String item : List.of("age", "gender", "lifeAttributes", "householdAttributes")) {
            assertItem(body.get("items").get(item), "NOT_EVALUATED", "NOT_PERSONAL_TARGET");
        }
        assertThat(body.get("source").get("guideUrl").asString()).endsWith(HANWOO);
    }

    @Test
    void 프로필을_수정한_뒤_다시_비교하면_바뀐_값으로_판정된다() throws Exception {
        collectionService.collectAll();
        putProfile(ALICE, profile("FEMALE", List.of("EMPLOYEE"), List.of("NONE")));
        assertThat(match(ALICE, HANWOO).get("result").asString()).isEqualTo("NEEDS_CHECK");

        putProfile(ALICE, profile("FEMALE", List.of("EMPLOYEE", "LIVESTOCK_FARMER"), List.of("NONE")));

        JsonNode body = match(ALICE, HANWOO);
        assertThat(body.get("result").asString()).isEqualTo("MATCH");
        assertThat(texts(body.get("items").get("lifeAttributes").get("profileAttributes")))
                .containsExactly("LIVESTOCK_FARMER", "EMPLOYEE");
    }

    @Test
    void 프로필을_저장한_적이_없으면_모든_항목이_미입력으로_확인_필요다() throws Exception {
        collectionService.collectAll();

        JsonNode body = match(ALICE, HANWOO);

        assertThat(body.get("result").asString()).isEqualTo("NEEDS_CHECK");
        assertItem(body.get("items").get("personalTarget"), "MET", "PERSONAL_USER_TYPE");
        for (String item : List.of("age", "gender", "lifeAttributes", "householdAttributes")) {
            assertItem(body.get("items").get(item), "NEEDS_CHECK", "PROFILE_MISSING");
        }
        assertThat(body.get("items").get("age").get("profileAge").isNull()).isTrue();
        assertThat(body.get("items").get("lifeAttributes").get("profileAttributes").isNull()).isTrue();
        // 혜택 쪽 값은 그대로 보여준다.
        assertThat(body.get("items").get("age").get("ageStart").asInt()).isEqualTo(19);
    }

    @Test
    void 지원조건을_수집하지_못한_혜택은_확인_필요이고_불일치가_아니다() throws Exception {
        stub.override(SUPPORT_CONDITIONS, 1, 503, "{\"code\":-1,\"msg\":\"일시 장애\"}");
        collectionService.collectAll();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM source_record WHERE endpoint = 'SUPPORT_CONDITIONS'",
                Integer.class)).isZero();
        putProfile(ALICE, profile("FEMALE", List.of("LIVESTOCK_FARMER"), List.of("NONE")));

        JsonNode body = match(ALICE, HANWOO);

        assertThat(body.get("result").asString()).isEqualTo("NEEDS_CHECK");
        assertItem(body.get("items").get("personalTarget"), "MET", "PERSONAL_USER_TYPE");
        for (String item : List.of("age", "gender", "lifeAttributes", "householdAttributes")) {
            assertItem(body.get("items").get(item), "NEEDS_CHECK", "CONDITIONS_NOT_COLLECTED");
        }
        assertThat(body.get("items").get("age").get("ageStart").isNull()).isTrue();
        assertThat(body.get("items").get("gender").get("allowedGenders").isNull()).isTrue();
        assertThat(body.get("items").get("age").get("profileAge").asInt()).isEqualTo(36);
    }

    @Test
    void 수집되지_않은_서비스_ID는_찾을_수_없음이다() throws Exception {
        collectionService.collectAll();

        assertError(perform(ALICE, "NO-SUCH", Map.of()).andExpect(status().isNotFound()), "NOT_FOUND");
    }

    @Test
    void 인증되지_않은_요청은_거절한다() throws Exception {
        collectionService.collectAll();

        assertError(mockMvc.perform(get(PATH + HANWOO)).andExpect(status().isUnauthorized()), "UNAUTHENTICATED");
        assertError(mockMvc.perform(get(PATH + HANWOO).header(HttpHeaders.AUTHORIZATION, "Bearer not-a-token"))
                .andExpect(status().isUnauthorized()), "UNAUTHENTICATED");

        // 탈퇴 뒤 남은 액세스 토큰도 401이다.
        String token = token(ALICE);
        mockMvc.perform(post("/api/me/withdrawal")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("password", PASSWORD))))
                .andExpect(status().isNoContent());
        assertError(mockMvc.perform(get(PATH + HANWOO).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isUnauthorized()), "UNAUTHENTICATED");
    }

    @Test
    void 다른_사용자의_프로필로_비교할_수_없다() throws Exception {
        collectionService.collectAll();
        signUpSteps.signUp(BOB, PASSWORD);
        putProfile(ALICE, profile("FEMALE", List.of("LIVESTOCK_FARMER"), List.of("NONE")));
        putProfile(BOB, profile("MALE", List.of("EMPLOYEE"), null));

        assertThat(match(ALICE, HANWOO).get("result").asString()).isEqualTo("MATCH");
        JsonNode bob = match(BOB, HANWOO);
        assertThat(bob.get("result").asString()).isEqualTo("NEEDS_CHECK");
        assertThat(bob.get("items").get("gender").get("profileGender").asString()).isEqualTo("MALE");

        // 요청에 다른 사용자의 ID를 넣어도 토큰 주인의 프로필로 비교한다(결정 0018).
        JsonNode asBob = json(perform(ALICE, HANWOO, Map.of("userId", String.valueOf(userId(BOB))))
                .andExpect(status().isOk()));
        assertThat(asBob.get("result").asString()).isEqualTo("MATCH");
        assertThat(asBob.get("items").get("gender").get("profileGender").asString()).isEqualTo("FEMALE");
    }

    @Test
    void 비로그인_상세_조회는_그대로다() throws Exception {
        collectionService.collectAll();

        JsonNode detail = json(mockMvc.perform(get("/api/benefits/" + HANWOO)).andExpect(status().isOk()));

        assertThat(detail.get("serviceId").asString()).isEqualTo(HANWOO);
        assertThat(detail.has("result")).isFalse();
    }

    private Map<String, Object> profile(String gender, List<String> life, List<String> household) {
        Map<String, Object> body = new HashMap<>();
        body.put("birthDate", BIRTH_DATE);
        body.put("gender", gender);
        body.put("lifeAttributes", life);
        body.put("householdAttributes", household);
        return body;
    }

    private void putProfile(String email, Map<String, Object> body) throws Exception {
        mockMvc.perform(put("/api/me/profile")
                        .header(HttpHeaders.AUTHORIZATION, token(email))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    private JsonNode match(String email, String serviceId) throws Exception {
        return json(perform(email, serviceId, Map.of()).andExpect(status().isOk()));
    }

    private ResultActions perform(String email, String serviceId, Map<String, String> params) throws Exception {
        var request = get(PATH + serviceId).header(HttpHeaders.AUTHORIZATION, token(email));
        params.forEach(request::param);
        return mockMvc.perform(request);
    }

    /** 요청마다 토큰 주인을 바꿔 쓰므로 그때그때 발급한다. */
    private String token(String email) {
        return "Bearer " + accessTokenIssuer.issue(userId(email)).value();
    }

    private Long userId(String email) {
        return jdbcTemplate.queryForObject("SELECT id FROM user_account WHERE email = ?", Long.class, email);
    }

    private static void assertItem(JsonNode item, String status, String reason) {
        assertThat(item.get("status").asString()).isEqualTo(status);
        assertThat(item.get("reason").asString()).isEqualTo(reason);
    }

    private void assertError(ResultActions result, String code) throws Exception {
        assertThat(json(result).get("code").asString()).isEqualTo(code);
    }

    private JsonNode json(ResultActions result) throws Exception {
        return jsonMapper.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asString()));
        return values;
    }
}
