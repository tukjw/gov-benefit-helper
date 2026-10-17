package kr.govbenefit.profile.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 최소 프로필 저장·조회(REQ-PROFILE-01). 시각은 고정한 시계로 옮긴다. 액세스 토큰도 같은 시계로 만료를 판정하므로 요청마다
 * 새로 발급한다.
 */
@SpringBootTest(properties = "gov24.schedule.enabled=false")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ProfileApiTest.FixedClockConfig.class})
class ProfileApiTest {

    /** 한국 시간 2026-10-17 10:00 */
    private static final Instant START = Instant.parse("2026-10-17T01:00:00Z");
    private static final String SEOUL = "6110000";
    private static final String GANGNAM = "3220000";
    private static final String JEONNAM_GWANGJU = "6130000";
    private static final String DONG_GU = "5805000";
    private static final String SEJONG = "5690000";
    private static final String BENEFIT_ID = "PROFILE-TEST";

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
        // 프로필과 속성은 계정을 지우면 함께 지워진다(ON DELETE CASCADE).
        jdbcTemplate.update("DELETE FROM user_account");
        LocalDateTime now = LocalDateTime.now(clock);
        aliceId = userAccountRepository.save(UserAccount.create("alice@example.com", "{noop}x", now)).getId();
        bobId = userAccountRepository.save(UserAccount.create("bob@example.com", "{noop}x", now)).getId();
    }

    @Test
    void 저장한_값이_조회되고_수정하면_바뀐_값이_조회된다() throws Exception {
        JsonNode saved = json(putProfile(aliceId, body("birthDate", "1995-03-01", "gender", "FEMALE", "sidoCode", SEOUL,
                "sigunguCode", GANGNAM, "lifeAttributes", List.of("EMPLOYEE", "PREGNANT"),
                "householdAttributes", List.of("NONE"))).andExpect(status().isOk()));

        JsonNode body = json(getProfile(aliceId).andExpect(status().isOk()));
        assertThat(body).isEqualTo(saved);
        assertThat(body.get("birthDate").asString()).isEqualTo("1995-03-01");
        assertThat(body.get("age").asInt()).isEqualTo(31);
        assertThat(body.get("gender").asString()).isEqualTo("FEMALE");
        assertThat(body.get("sido").get("code").asString()).isEqualTo(SEOUL);
        assertThat(body.get("sido").get("name").asString()).isEqualTo("서울특별시");
        assertThat(body.get("sigungu").get("code").asString()).isEqualTo(GANGNAM);
        assertThat(body.get("sigungu").get("name").asString()).isEqualTo("강남구");
        // 선언 순서로 정렬한다.
        assertThat(texts(body.get("lifeAttributes"))).containsExactly("PREGNANT", "EMPLOYEE");
        assertThat(texts(body.get("householdAttributes"))).containsExactly("NONE");
        assertThat(body.get("updatedAt").asString()).isEqualTo("2026-10-17T10:00:00+09:00");
        assertThat(names(body)).doesNotContain("userId", "id");

        clock.advance(Duration.ofHours(1));
        putProfile(aliceId, body("birthDate", "1995-03-01", "gender", "MALE", "sidoCode", JEONNAM_GWANGJU,
                "sigunguCode", DONG_GU, "lifeAttributes", null, "householdAttributes", List.of("SINGLE_PERSON_HOUSEHOLD")))
                .andExpect(status().isOk());

        JsonNode changed = json(getProfile(aliceId).andExpect(status().isOk()));
        assertThat(changed.get("gender").asString()).isEqualTo("MALE");
        assertThat(changed.get("sido").get("name").asString()).isEqualTo("전남광주통합특별시");
        assertThat(changed.get("sigungu").get("name").asString()).isEqualTo("동구");
        assertThat(changed.get("lifeAttributes").isNull()).isTrue();
        assertThat(texts(changed.get("householdAttributes"))).containsExactly("SINGLE_PERSON_HOUSEHOLD");
        assertThat(changed.get("updatedAt").asString()).isEqualTo("2026-10-17T11:00:00+09:00");
        assertThat(jdbcTemplate.queryForList(
                "SELECT condition_code FROM user_profile_attribute WHERE user_id = ?", String.class, aliceId))
                .containsExactly("JA0404");
    }

    @Test
    void 저장한_적이_없으면_모든_값이_null이고_행을_만들지_않는다() throws Exception {
        JsonNode body = json(getProfile(aliceId).andExpect(status().isOk()));

        assertAllNull(body);
        assertThat(body.get("updatedAt").isNull()).isTrue();
        assertThat(profileRows()).isZero();
    }

    @Test
    void 아무_항목도_없는_프로필을_저장하고_조회한다() throws Exception {
        putProfile(aliceId, body()).andExpect(status().isOk());

        JsonNode body = json(getProfile(aliceId).andExpect(status().isOk()));
        assertAllNull(body);
        assertThat(body.get("updatedAt").asString()).isEqualTo("2026-10-17T10:00:00+09:00");
        assertThat(profileRows()).isEqualTo(1);
    }

    @Test
    void 미입력과_해당_없음이_응답에서_구분된다() throws Exception {
        putProfile(aliceId, body("lifeAttributes", List.of("NONE"), "householdAttributes", null)).andExpect(status().isOk());
        JsonNode first = json(getProfile(aliceId).andExpect(status().isOk()));
        assertThat(texts(first.get("lifeAttributes"))).containsExactly("NONE");
        assertThat(first.get("householdAttributes").isNull()).isTrue();

        putProfile(aliceId, body("lifeAttributes", null, "householdAttributes", List.of("NONE"))).andExpect(status().isOk());
        JsonNode second = json(getProfile(aliceId).andExpect(status().isOk()));
        assertThat(second.get("lifeAttributes").isNull()).isTrue();
        assertThat(texts(second.get("householdAttributes"))).containsExactly("NONE");
    }

    @Test
    void 만_나이는_생일_전날_당일_다음_날_경계에서_맞다() throws Exception {
        putProfile(aliceId, body("birthDate", "2000-10-18")).andExpect(status().isOk());

        // 2026-10-17(생일 전날), 10-18(당일), 10-19(다음 날)
        assertThat(json(getProfile(aliceId)).get("age").asInt()).isEqualTo(25);
        clock.advance(Duration.ofDays(1));
        assertThat(json(getProfile(aliceId)).get("age").asInt()).isEqualTo(26);
        clock.advance(Duration.ofDays(1));
        assertThat(json(getProfile(aliceId)).get("age").asInt()).isEqualTo(26);
    }

    @Test
    void 날짜_경계는_한국_시간이다() throws Exception {
        putProfile(aliceId, body("birthDate", "2000-10-18")).andExpect(status().isOk());

        // 한국 시간 2026-10-17 23:59:59와 10-18 00:00
        clock.set(Instant.parse("2026-10-17T14:59:59Z"));
        assertThat(json(getProfile(aliceId)).get("age").asInt()).isEqualTo(25);
        clock.set(Instant.parse("2026-10-17T15:00:00Z"));
        assertThat(json(getProfile(aliceId)).get("age").asInt()).isEqualTo(26);
    }

    @Test
    void 시도만_입력할_수_있고_세종은_시도만_있다() throws Exception {
        putProfile(aliceId, body("sidoCode", SEOUL)).andExpect(status().isOk());
        assertThat(json(getProfile(aliceId)).get("sigungu").isNull()).isTrue();

        putProfile(aliceId, body("sidoCode", SEJONG)).andExpect(status().isOk());
        assertThat(json(getProfile(aliceId)).get("sido").get("name").asString()).isEqualTo("세종특별자치시");
    }

    @Test
    void 오늘_태어난_날과_만_120세는_받고_내일과_만_121세는_거절한다() throws Exception {
        putProfile(aliceId, body("birthDate", "2026-10-17")).andExpect(status().isOk());
        putProfile(aliceId, body("birthDate", "1905-10-18")).andExpect(status().isOk());
        assertThat(json(getProfile(aliceId)).get("age").asInt()).isEqualTo(120);

        assertRejected(body("birthDate", "2026-10-18"), "birthDate");
        assertRejected(body("birthDate", "1905-10-17"), "birthDate");
    }

    @Test
    void 지원하지_않는_값과_모순된_입력은_해당_항목을_가리키는_오류로_거절하고_값을_바꾸지_않는다() throws Exception {
        putProfile(aliceId, body("birthDate", "1995-03-01", "gender", "FEMALE", "sidoCode", SEOUL, "sigunguCode", GANGNAM,
                "lifeAttributes", List.of("EMPLOYEE"), "householdAttributes", List.of("NONE"))).andExpect(status().isOk());
        JsonNode before = json(getProfile(aliceId));

        assertRejected(body("gender", "OTHER"), "gender");
        assertRejected(body("gender", "male"), "gender");
        assertRejected(body("lifeAttributes", List.of("UNKNOWN")), "lifeAttributes");
        // 다른 그룹의 값
        assertRejected(body("lifeAttributes", List.of("SINGLE_PERSON_HOUSEHOLD")), "lifeAttributes");
        assertRejected(body("householdAttributes", List.of("PREGNANT")), "householdAttributes");
        assertRejected(body("sidoCode", "9999999"), "sidoCode");
        // 시군구 코드를 시도로
        assertRejected(body("sidoCode", GANGNAM), "sidoCode");
        assertRejected(body("birthDate", "2001-02-29"), "birthDate");
        assertRejected(body("birthDate", "20000517"), "birthDate");
        // 시도에 속하지 않는 시군구
        assertRejected(body("sidoCode", SEOUL, "sigunguCode", DONG_GU), "sigunguCode");
        assertRejected(body("sigunguCode", GANGNAM), "sigunguCode");
        assertRejected(body("sidoCode", SEJONG, "sigunguCode", GANGNAM), "sigunguCode");
        // 시도를 시군구로
        assertRejected(body("sidoCode", SEOUL, "sigunguCode", SEOUL), "sigunguCode");
        // 같은 그룹에서 해당 없음과 다른 속성
        assertRejected(body("lifeAttributes", List.of("NONE", "EMPLOYEE")), "lifeAttributes");
        assertRejected(body("householdAttributes", List.of("SINGLE_PERSON_HOUSEHOLD", "NONE")), "householdAttributes");
        assertRejected(body("lifeAttributes", List.of("EMPLOYEE", "EMPLOYEE")), "lifeAttributes");
        assertRejected(body("lifeAttributes", List.of()), "lifeAttributes");
        assertRejected(body("householdAttributes", Arrays.asList("NONE", null)), "householdAttributes");

        assertThat(json(getProfile(aliceId))).isEqualTo(before);
    }

    @Test
    void 잘못된_항목은_모두_한_번에_알려준다() throws Exception {
        JsonNode body = json(putProfile(aliceId, body("gender", "OTHER", "sidoCode", SEOUL, "sigunguCode", DONG_GU,
                "lifeAttributes", List.of("NONE", "EMPLOYEE"))).andExpect(status().isBadRequest()));

        assertThat(body.get("code").asString()).isEqualTo("INVALID_INPUT");
        assertThat(errorFields(body)).containsExactly("gender", "sigunguCode", "lifeAttributes");
        JsonNode sigungu = body.get("errors").get(1);
        assertThat(sigungu.get("value").asString()).isEqualTo(DONG_GU);
        assertThat(sigungu.get("reason").asString()).isEqualTo("선택한 시도의 시군구가 아닙니다.");
        assertThat(body.get("errors").get(2).get("value").asString()).isEqualTo("NONE,EMPLOYEE");
    }

    @Test
    void 시도가_틀리면_시군구는_소속을_따지지_않는다() throws Exception {
        JsonNode body = json(putProfile(aliceId, body("sidoCode", "9999999", "sigunguCode", GANGNAM))
                .andExpect(status().isBadRequest()));

        assertThat(errorFields(body)).containsExactly("sidoCode");
    }

    @Test
    void 인증되지_않은_요청은_거절한다() throws Exception {
        for (ResultActions result : List.of(
                mockMvc.perform(get("/api/me/profile")),
                mockMvc.perform(put("/api/me/profile").contentType(MediaType.APPLICATION_JSON).content("{}")),
                mockMvc.perform(get("/api/me/profile/options")))) {
            JsonNode body = json(result.andExpect(status().isUnauthorized()));
            assertThat(body.get("code").asString()).isEqualTo("UNAUTHENTICATED");
        }
        assertThat(profileRows()).isZero();
    }

    @Test
    void 요청에_다른_사용자_ID를_넣어도_그_사용자의_프로필을_읽거나_바꿀_수_없다() throws Exception {
        putProfile(bobId, body("gender", "MALE")).andExpect(status().isOk());

        Map<String, Object> request = body("gender", "FEMALE");
        request.put("userId", bobId);
        request.put("id", bobId);
        JsonNode saved = json(putProfile(aliceId, request).andExpect(status().isOk()));

        assertThat(saved.get("gender").asString()).isEqualTo("FEMALE");
        assertThat(json(getProfile(bobId)).get("gender").asString()).isEqualTo("MALE");
        assertThat(json(getProfile(aliceId)).get("gender").asString()).isEqualTo("FEMALE");
        assertThat(jdbcTemplate.queryForObject("SELECT gender FROM user_profile WHERE user_id = ?", String.class, bobId))
                .isEqualTo("MALE");
    }

    @Test
    void 다른_사용자가_저장한_값은_보이지_않는다() throws Exception {
        putProfile(aliceId, body("gender", "FEMALE", "lifeAttributes", List.of("PREGNANT"))).andExpect(status().isOk());

        assertAllNull(json(getProfile(bobId).andExpect(status().isOk())));
    }

    @Test
    void 범위_밖_정보는_받아도_저장하지_않는다() throws Exception {
        Map<String, Object> request = body("gender", "FEMALE");
        request.put("income", "3000만원");
        request.put("medianIncomeRatio", 50);
        request.put("residentRegistrationNumber", "950301-2000000");
        JsonNode saved = json(putProfile(aliceId, request).andExpect(status().isOk()));

        assertThat(names(saved)).containsExactlyInAnyOrder("birthDate", "age", "gender", "sido", "sigungu",
                "lifeAttributes", "householdAttributes", "updatedAt");
        assertThat(jdbcTemplate.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name IN ('user_profile', 'user_profile_attribute')""",
                String.class)).containsExactlyInAnyOrder("user_id", "birth_date", "gender", "sido_code",
                "sigungu_code", "created_at", "updated_at", "user_id", "condition_code");
    }

    @Test
    void 프로필이_없어도_검색과_상세를_쓸_수_있다() throws Exception {
        jdbcTemplate.update("DELETE FROM benefit WHERE service_id = ?", BENEFIT_ID);
        benefitRepository.save(Benefit.create(BENEFIT_ID, new BenefitContent("프로필 테스트 혜택", null, "주거·자립",
                "개인", null, null, null, null, null, null, null, null, "서울특별시", "광역시도", SEOUL, null,
                "https://www.gov.kr/portal/rcvfvrSvc/dtlEx/" + BENEFIT_ID),
                new BenefitNormalization(null, null, ApplyPeriodRule.DATE), LocalDateTime.now(clock)));
        try {
            String token = "Bearer " + accessTokenIssuer.issue(aliceId).value();
            mockMvc.perform(get("/api/benefits")).andExpect(status().isOk());
            mockMvc.perform(get("/api/benefits").header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isOk());
            mockMvc.perform(get("/api/benefits/" + BENEFIT_ID)).andExpect(status().isOk());
            mockMvc.perform(get("/api/benefits/" + BENEFIT_ID).header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(status().isOk());
            assertThat(profileRows()).isZero();
        } finally {
            jdbcTemplate.update("DELETE FROM benefit WHERE service_id = ?", BENEFIT_ID);
        }
    }

    @Test
    void 계정을_지우면_프로필과_속성이_함께_지워진다() throws Exception {
        putProfile(aliceId, body("lifeAttributes", List.of("EMPLOYEE"), "householdAttributes", List.of("NONE")))
                .andExpect(status().isOk());

        jdbcTemplate.update("DELETE FROM user_account WHERE id = ?", aliceId);

        assertThat(profileRows()).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM user_profile_attribute", Integer.class)).isZero();
    }

    @Test
    void 계정이_없는_토큰의_주인은_401이다() throws Exception {
        String token = "Bearer " + accessTokenIssuer.issue(aliceId).value();
        jdbcTemplate.update("DELETE FROM user_account WHERE id = ?", aliceId);

        assertThat(json(mockMvc.perform(get("/api/me/profile").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isUnauthorized())).get("code").asString()).isEqualTo("UNAUTHENTICATED");
        assertThat(json(mockMvc.perform(put("/api/me/profile").header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())).get("code").asString()).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void 선택지는_시도별_시군구와_속성의_값과_이름이다() throws Exception {
        JsonNode body = json(mockMvc.perform(get("/api/me/profile/options").header(HttpHeaders.AUTHORIZATION,
                "Bearer " + accessTokenIssuer.issue(aliceId).value())).andExpect(status().isOk()));

        JsonNode sidos = body.get("sidos");
        assertThat(sidos).hasSize(16);
        assertThat(sidos.get(0).get("name").asString()).isEqualTo("서울특별시");
        assertThat(sidos.get(15).get("name").asString()).isEqualTo("제주특별자치도");
        assertThat(sidos.get(0).get("sigungus")).hasSize(25);
        assertThat(sidos.get(0).get("sigungus").get(0).get("code").asString()).isEqualTo("3000000");
        assertThat(sidos.get(0).get("sigungus").get(0).get("name").asString()).isEqualTo("종로구");
        int sigunguCount = 0;
        for (JsonNode sido : sidos) {
            sigunguCount += sido.get("sigungus").size();
            if (List.of(SEJONG, "6500000").contains(sido.get("code").asString())) {
                assertThat(sido.get("sigungus").isEmpty()).isTrue();
            }
        }
        assertThat(sigunguCount).isEqualTo(227);

        JsonNode life = body.get("lifeAttributes");
        assertThat(life).hasSize(17);
        assertThat(life.get(16).get("value").asString()).isEqualTo("NONE");
        assertThat(life.get(16).get("label").asString()).isEqualTo("해당사항없음");
        assertThat(life.get(1).get("value").asString()).isEqualTo("PREGNANT");
        assertThat(life.get(1).get("label").asString()).isEqualTo("임산부");
        JsonNode household = body.get("householdAttributes");
        assertThat(household).hasSize(9);
        assertThat(household.get(8).get("value").asString()).isEqualTo("NONE");

        // 선택지에서 고른 값으로 저장된다.
        JsonNode sido = sidos.get(12);
        putProfile(aliceId, body("sidoCode", sido.get("code").asString(),
                "sigunguCode", sido.get("sigungus").get(0).get("code").asString(),
                "lifeAttributes", List.of(life.get(0).get("value").asString()),
                "householdAttributes", List.of(household.get(0).get("value").asString()))).andExpect(status().isOk());
    }

    private void assertRejected(Map<String, Object> request, String field) throws Exception {
        JsonNode body = json(putProfile(aliceId, request).andExpect(status().isBadRequest()));
        assertThat(body.get("code").asString()).isEqualTo("INVALID_INPUT");
        assertThat(errorFields(body)).as("거절 항목: %s", request).isNotEmpty().containsOnly(field);
    }

    private static void assertAllNull(JsonNode body) {
        for (String field : List.of("birthDate", "age", "gender", "sido", "sigungu", "lifeAttributes",
                "householdAttributes")) {
            assertThat(body.get(field).isNull()).as(field).isTrue();
        }
    }

    private int profileRows() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM user_profile", Integer.class);
    }

    private ResultActions getProfile(Long userId) throws Exception {
        return mockMvc.perform(get("/api/me/profile").header(HttpHeaders.AUTHORIZATION, bearer(userId)));
    }

    private ResultActions putProfile(Long userId, Map<String, Object> body) throws Exception {
        return mockMvc.perform(put("/api/me/profile")
                .header(HttpHeaders.AUTHORIZATION, bearer(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(body)));
    }

    /** 시계를 옮기면 이전 토큰이 만료되므로 요청마다 발급한다. */
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

    private static List<String> errorFields(JsonNode body) {
        return texts(body.get("errors"), "field");
    }

    private static List<String> texts(JsonNode array) {
        List<String> texts = new ArrayList<>();
        array.forEach(node -> texts.add(node.asString()));
        return texts;
    }

    private static List<String> texts(JsonNode array, String field) {
        List<String> texts = new ArrayList<>();
        array.forEach(node -> texts.add(node.get(field).asString()));
        return texts;
    }

    private static List<String> names(JsonNode object) {
        return new ArrayList<>(object.propertyNames());
    }
}
