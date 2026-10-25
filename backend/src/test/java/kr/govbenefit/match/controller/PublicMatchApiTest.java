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
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import kr.govbenefit.MutableClock;
import kr.govbenefit.TestcontainersConfiguration;
import kr.govbenefit.account.SignUpSteps;
import kr.govbenefit.account.mail.RecordingAccountMailSender;
import kr.govbenefit.collection.Gov24StubServer;
import kr.govbenefit.collection.domain.SourceEndpoint;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 로그인 없이 요청에 담은 프로필 값으로 비교하는 조건 비교(REQ-MATCH-01, DEC-AUTH 공개 범위)를 Testcontainers MySQL로
 * 검증한다. 판정 규칙 자체는 {@code MatchApiTest}·{@code MatchListApiTest}가 검증하므로, 여기서는 같은 값을 저장한 로그인
 * 사용자의 응답과 같은지, 저장하지 않는지, 입력 검증이 프로필 저장과 같은지를 본다. fixture 구성은 {@code MatchListApiTest}와 같다.
 */
@SpringBootTest(properties = {"gov24.schedule.enabled=false", "gov24.retry-delay=10ms"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, PublicMatchApiTest.FixedClockConfig.class})
class PublicMatchApiTest {

    /** 서울 날짜 2026-10-20 */
    private static final Instant START = Instant.parse("2026-10-20T01:00:00Z");
    private static final String ALICE = "alice@example.com";
    private static final String PASSWORD = "correct-horse";
    private static final String PATH = "/api/matches";
    private static final String ME_PATH = "/api/me/matches";
    /** 본으로 쓰는 stub fixture 레코드. 사용자구분 개인, 성별 둘 다 Y, 연령 19~120, 생애는 축산업인만 Y, 가구 전부 Y. */
    private static final String HANWOO = "511000000138";
    private static final List<String> FIXTURE_IDS = List.of(HANWOO, "403000000173", "O00024000031");
    /** 본 레코드의 운영 기관(경상북도 상주시)과 프로필 거주지 */
    private static final String GYEONGBUK = "6470000";
    private static final String SANGJU = "5110000";
    private static final String POHANG = "5020000";
    private static final String GYEONGBUK_EDUCATION = "8750000";
    private static final String SUYEONG = "3380000";
    /** 2026-10-20에 만 36세 */
    private static final String BIRTH_DATE = "1990-01-01";
    /** 연령 시작을 만 나이와 같게 해 경계값(확인 필요)으로 만든다. */
    private static final Consumer<ObjectNode> AGE_ON_BOUNDARY = conditions -> conditions.put("JA0110", 36);
    private static final Consumer<ObjectNode> UNCHANGED = conditions -> {
    };

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

    private final Map<SourceEndpoint, ObjectNode> templates = new EnumMap<>(SourceEndpoint.class);

    @BeforeEach
    void setUp() throws Exception {
        clock.set(START);
        stub.reset();
        for (SourceEndpoint endpoint : SourceEndpoint.values()) {
            templates.put(endpoint, stub.record(endpoint, HANWOO).deepCopy());
        }
        FIXTURE_IDS.forEach(stub::removeService);
        for (String table : List.of("bookmark", "benefit_detail", "benefit", "source_record_history",
                "source_record", "collection_quarantine", "collection_failure", "collection_run_step",
                "collection_run", "user_account", "email_verification")) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        mailSender.clear();
        new SignUpSteps(mockMvc, jsonMapper, mailSender).signUp(ALICE, PASSWORD);
        services();
    }

    @Test
    void 같은_프로필_값이면_로그인_사용자의_목록_상세와_응답이_같다() throws Exception {
        Map<String, Object> full = profile();
        Map<String, Object> sidoOnly = profile();
        sidoOnly.put("sigunguCode", null);
        Map<String, Object> noRegion = profile();
        noRegion.put("sidoCode", null);
        noRegion.put("sigunguCode", null);
        // 선언 순서와 다르게 보내도 저장된 프로필과 같은 순서로 비교한다.
        Map<String, Object> unordered = profile();
        unordered.put("lifeAttributes", List.of("EMPLOYEE", "LIVESTOCK_FARMER"));
        unordered.put("householdAttributes", null);

        for (Map<String, Object> profile : List.of(full, sidoOnly, noRegion, unordered)) {
            putProfile(profile);
            for (String[] params : List.of(new String[0], new String[]{"regionScope", "MY_REGION"},
                    new String[]{"regionScope", "ALL"}, new String[]{"page", "2", "size", "3"})) {
                JsonNode guest = json(guest(PATH, profile, params).andExpect(status().isOk()));
                JsonNode member = json(member(ME_PATH, params).andExpect(status().isOk()));
                assertThat(guest).as(profile + " " + List.of(params)).isEqualTo(member);
            }
            // 개인 대상이 아닌 혜택(C01)의 상세도 같다.
            for (String serviceId : List.of("R01", "R02", "R03", "R05", "R06", "R07", "R08", "N01", "N02", "C01")) {
                JsonNode guest = json(guest(PATH + "/" + serviceId, profile).andExpect(status().isOk()));
                JsonNode member = json(member(ME_PATH + "/" + serviceId).andExpect(status().isOk()));
                assertThat(guest).as(profile + " " + serviceId).isEqualTo(member);
            }
        }
    }

    @Test
    void 상세_비교의_프로필_속성은_보낸_순서가_아니라_저장된_프로필과_같은_순서다() throws Exception {
        Map<String, Object> profile = profile();
        profile.put("lifeAttributes", List.of("JOB_SEEKER", "LIVESTOCK_FARMER", "PREGNANT"));

        JsonNode body = json(guest(PATH + "/R01", profile).andExpect(status().isOk()));

        assertThat(texts(body.get("items").get("lifeAttributes").get("profileAttributes")))
                .containsExactly("PREGNANT", "LIVESTOCK_FARMER", "JOB_SEEKER");
    }

    @Test
    void 프로필_값을_보내지_않아도_목록이_나오고_개인_대상_혜택은_모두_확인_필요다() throws Exception {
        JsonNode member = json(member(ME_PATH).andExpect(status().isOk()));
        List<MockHttpServletRequestBuilder> requests = List.of(
                post(PATH),
                post(PATH).contentType(MediaType.APPLICATION_JSON),
                post(PATH).contentType(MediaType.APPLICATION_JSON).content("{}"),
                post(PATH).contentType(MediaType.APPLICATION_JSON).content("""
                        {"birthDate": null, "lifeAttributes": null}"""));

        for (MockHttpServletRequestBuilder request : requests) {
            JsonNode body = json(mockMvc.perform(request).andExpect(status().isOk()));

            // 프로필을 저장하지 않은 로그인 사용자와 같다.
            assertThat(body).isEqualTo(member);
            assertThat(body.get("regionScope").asString()).isEqualTo("ALL");
            assertThat(ids(body)).hasSize(10).doesNotContain("C01");
            assertThat(results(body)).containsOnly("NEEDS_CHECK");
            assertThat(body.get("items").get(0).get("items").get("age").get("reason").asString())
                    .isEqualTo("PROFILE_MISSING");
        }
        JsonNode detail = json(mockMvc.perform(post(PATH + "/R01")).andExpect(status().isOk()));
        assertThat(detail.get("result").asString()).isEqualTo("NEEDS_CHECK");
        assertThat(detail.get("items").get("age").get("profileAge").isNull()).isTrue();
    }

    @Test
    void 개인_대상이_아닌_혜택은_어느_범위에도_없다() throws Exception {
        for (String scope : List.of("MY_REGION", "ALL")) {
            JsonNode body = json(guest(PATH, profile(), "regionScope", scope, "size", "100")
                    .andExpect(status().isOk()));

            assertThat(ids(body)).doesNotContain("C01");
            assertThat(results(body)).doesNotContain("MISMATCH");
        }
        JsonNode all = json(guest(PATH, profile(), "regionScope", "ALL").andExpect(status().isOk()));
        assertThat(all.get("totalCount").asLong()).isEqualTo(10);
    }

    @Test
    void 쪽_번호와_크기는_검색과_같게_처리한다() throws Exception {
        JsonNode defaults = json(guest(PATH, profile(), "regionScope", "ALL").andExpect(status().isOk()));
        assertThat(defaults.get("page").asInt()).isEqualTo(1);
        assertThat(defaults.get("size").asInt()).isEqualTo(20);
        JsonNode beyond = json(guest(PATH, profile(), "regionScope", "ALL", "page", "99", "size", "100")
                .andExpect(status().isOk()));
        assertThat(beyond.get("totalCount").asLong()).isEqualTo(10);
        assertThat(beyond.get("items").size()).isZero();

        List<List<String>> invalid = List.of(
                List.of("size", "0"), List.of("size", "101"), List.of("size", "abc"),
                List.of("page", "0"), List.of("page", "-1"), List.of("page", "abc"), List.of("page", "99999999999"),
                List.of("page", "0", "size", "101"));
        for (List<String> params : invalid) {
            String[] pairs = params.toArray(String[]::new);
            JsonNode matches = json(guest(PATH, profile(), pairs).andExpect(status().isBadRequest()));
            JsonNode search = json(mockMvc.perform(withParams(get("/api/benefits"), pairs))
                    .andExpect(status().isBadRequest()));

            assertThat(matches.get("code").asString()).as(params.toString()).isEqualTo("INVALID_INPUT");
            assertThat(matches.get("message")).isEqualTo(search.get("message"));
            assertThat(errors(matches)).as(params.toString()).isNotEmpty().isEqualTo(errors(search));
        }
    }

    @Test
    void 지원하지_않는_값과_모순된_입력은_프로필_저장과_같은_항목별_오류다() throws Exception {
        List<Map<String, Object>> invalid = List.of(
                Map.of("sigunguCode", SANGJU),
                Map.of("sidoCode", GYEONGBUK, "sigunguCode", SUYEONG),
                Map.of("sidoCode", "9999999"),
                Map.of("lifeAttributes", List.of()),
                Map.of("lifeAttributes", List.of("NONE", "EMPLOYEE")),
                Map.of("householdAttributes", List.of("UNKNOWN", "SINGLE_PERSON_HOUSEHOLD", "SINGLE_PERSON_HOUSEHOLD")),
                Map.of("birthDate", "2026-10-21"),
                Map.of("birthDate", "1990-02-30"),
                Map.of("gender", "OTHER"),
                Map.of("birthDate", "1900-01-01", "gender", "male", "lifeAttributes", List.of("NONE", "FARMER")));

        for (Map<String, Object> body : invalid) {
            JsonNode saved = json(mockMvc.perform(put("/api/me/profile")
                            .header(HttpHeaders.AUTHORIZATION, token())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(jsonMapper.writeValueAsString(body)))
                    .andExpect(status().isBadRequest()));
            // 잘못된 값이면 서비스 ID가 없어도 400이다.
            for (String path : List.of(PATH, PATH + "/R01", PATH + "/NO-SUCH-SERVICE")) {
                JsonNode guest = json(guest(path, body).andExpect(status().isBadRequest()));

                assertThat(guest.get("code").asString()).as(body + " " + path).isEqualTo("INVALID_INPUT");
                assertThat(guest.get("message")).isEqualTo(saved.get("message"));
                assertThat(errors(guest)).as(body + " " + path).isNotEmpty().isEqualTo(errors(saved));
            }
        }
    }

    @Test
    void 요청_뒤_프로필과_사용자_기록이_생기지_않는다() throws Exception {
        Map<String, Integer> before = rowCounts();
        assertThat(before.get("user_profile")).isZero();

        guest(PATH, profile()).andExpect(status().isOk());
        guest(PATH, profile(), "regionScope", "ALL").andExpect(status().isOk());
        guest(PATH + "/R01", profile()).andExpect(status().isOk());
        guest(PATH + "/NO-SUCH-SERVICE", profile()).andExpect(status().isNotFound());
        guest(PATH, Map.of("sigunguCode", SANGJU)).andExpect(status().isBadRequest());
        // 토큰을 붙여 보내도 프로필을 만들지 않는다.
        mockMvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(profile())))
                .andExpect(status().isOk());

        assertThat(rowCounts()).isEqualTo(before);
    }

    @Test
    void 토큰을_보내도_저장된_프로필이_아니라_본문_값으로_비교한다() throws Exception {
        putProfile(profile());
        Map<String, Object> other = profile();
        other.put("sidoCode", "6260000");
        other.put("sigunguCode", SUYEONG);
        JsonNode anonymous = json(guest(PATH, other).andExpect(status().isOk()));

        JsonNode withToken = json(mockMvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(other)))
                .andExpect(status().isOk()));

        assertThat(withToken).isEqualTo(anonymous);
        assertThat(ids(withToken)).containsExactly("R06", "R07");
        // 공개 경로라도 보낸 토큰은 검증한다(결정 0018).
        JsonNode invalid = json(mockMvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer not-a-token"))
                .andExpect(status().isUnauthorized()));
        assertThat(invalid.get("code").asString()).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void 수집되지_않은_서비스_ID는_찾을_수_없음이다() throws Exception {
        JsonNode body = json(guest(PATH + "/NO-SUCH-SERVICE", profile()).andExpect(status().isNotFound()));

        assertThat(body.get("code").asString()).isEqualTo("NOT_FOUND");
    }

    /**
     * 경상북도 상주시 거주자 기준으로 운영 기관 지역과 지원조건이 다른 혜택을 넣는다.
     */
    private void services() {
        service("R01", "가 상주시", "개인", UNCHANGED, "시군구", SANGJU);
        service("R02", "나 경상북도", "개인", UNCHANGED, "광역시도", GYEONGBUK);
        service("R03", "다 경상북도교육청", "개인", UNCHANGED, "교육청", GYEONGBUK_EDUCATION);
        service("R04", "라 상주시 공기업", "개인", AGE_ON_BOUNDARY, "지방공기업", SANGJU);
        service("R05", "마 포항시", "개인", UNCHANGED, "시군구", POHANG);
        service("R06", "바 중앙", "개인", UNCHANGED, "중앙행정기관", "1741000");
        service("R07", "사 부산 수영구", "개인", UNCHANGED, "시군구", SUYEONG);
        service("R08", "아 지역 모름", "개인", UNCHANGED, "시군구", "9999999");
        service("N01", "자 지원조건 없음", "개인", null, null, null);
        service("N02", "차 사용자구분 없음", null, UNCHANGED, null, null);
        service("C01", "카 상주시 기관 대상", "법인/시설/단체", UNCHANGED, "시군구", SANGJU);
        collectionService.collectAll();
    }

    /**
     * 본 레코드를 세 경로에 복사해 혜택 하나를 stub에 더한다.
     *
     * @param userType   사용자구분. null이면 필드를 뺀다.
     * @param conditions 지원조건 레코드를 바꾸는 함수. null이면 지원조건 레코드를 넣지 않는다(수집되지 않음).
     * @param agencyType 소관기관유형. null이면 본 레코드 값(시군구, 상주시)이다.
     */
    private void service(String serviceId, String name, String userType, Consumer<ObjectNode> conditions,
                         String agencyType, String agencyCode) {
        for (SourceEndpoint endpoint : SourceEndpoint.values()) {
            if (endpoint == SUPPORT_CONDITIONS && conditions == null) {
                continue;
            }
            ObjectNode record = templates.get(endpoint).deepCopy();
            record.put("서비스ID", serviceId);
            record.put("서비스명", name);
            if (endpoint == SERVICE_LIST) {
                if (userType == null) {
                    record.remove("사용자구분");
                } else {
                    record.put("사용자구분", userType);
                }
                if (agencyType != null) {
                    record.put("소관기관유형", agencyType);
                    record.put("소관기관코드", agencyCode);
                }
            }
            if (endpoint == SUPPORT_CONDITIONS) {
                conditions.accept(record);
            }
            stub.records(endpoint).add(record);
        }
    }

    /** 경상북도 상주시에 사는 축산업인. 본 레코드의 지원조건에 부합한다. */
    private static Map<String, Object> profile() {
        Map<String, Object> body = new HashMap<>();
        body.put("birthDate", BIRTH_DATE);
        body.put("gender", "FEMALE");
        body.put("lifeAttributes", List.of("LIVESTOCK_FARMER"));
        body.put("householdAttributes", List.of("NONE"));
        body.put("sidoCode", GYEONGBUK);
        body.put("sigunguCode", SANGJU);
        return body;
    }

    private void putProfile(Map<String, Object> body) throws Exception {
        mockMvc.perform(put("/api/me/profile")
                        .header(HttpHeaders.AUTHORIZATION, token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    /** 토큰 없이 프로필 값을 본문에 담아 보낸다. */
    private ResultActions guest(String path, Map<String, Object> body, String... params) throws Exception {
        return mockMvc.perform(withParams(post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(body)), params));
    }

    /** 저장된 프로필로 비교하는 로그인 사용자의 요청 */
    private ResultActions member(String path, String... params) throws Exception {
        return mockMvc.perform(withParams(get(path).header(HttpHeaders.AUTHORIZATION, token()), params));
    }

    private static MockHttpServletRequestBuilder withParams(MockHttpServletRequestBuilder builder, String... params) {
        for (int i = 0; i < params.length; i += 2) {
            builder.param(params[i], params[i + 1]);
        }
        return builder;
    }

    private String token() {
        Long id = jdbcTemplate.queryForObject("SELECT id FROM user_account WHERE email = ?", Long.class, ALICE);
        return "Bearer " + accessTokenIssuer.issue(id).value();
    }

    private Map<String, Integer> rowCounts() {
        Map<String, Integer> counts = new HashMap<>();
        for (String table : List.of("user_account", "user_profile", "user_profile_attribute")) {
            counts.put(table, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class));
        }
        return counts;
    }

    private static List<String> ids(JsonNode body) {
        List<String> ids = new ArrayList<>();
        body.get("items").forEach(item -> ids.add(item.get("benefit").get("serviceId").asString()));
        return ids;
    }

    private static List<String> results(JsonNode body) {
        List<String> results = new ArrayList<>();
        body.get("items").forEach(item -> results.add(item.get("result").asString()));
        return results;
    }

    /** 오류 항목의 순서는 정해져 있지 않으므로 집합으로 비교한다. */
    private static Set<List<String>> errors(JsonNode body) {
        Set<List<String>> errors = new HashSet<>();
        body.get("errors").forEach(error -> errors.add(List.of(error.get("field").asString(),
                error.get("value").asString(), error.get("reason").asString())));
        return errors;
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
