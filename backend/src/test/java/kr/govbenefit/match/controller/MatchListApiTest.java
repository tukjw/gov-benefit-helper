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
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import kr.govbenefit.FixedClockConfiguration;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
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
 * 조건 비교 결과 순 목록(REQ-MATCH-01, DEC-MATCH)을 Testcontainers MySQL로 검증한다. 혜택과 지원조건은 로컬 stub으로
 * 실제 수집해 넣는다. stub fixture의 한 혜택을 본으로 서비스 ID·이름·사용자구분·지원조건을 바꾼 혜택을 만든다.
 * 혜택 하나의 판정 규칙은 {@code ConditionMatcherTest}와 {@code MatchApiTest}가 검증한다.
 */
@SpringBootTest(properties = {"gov24.schedule.enabled=false", "gov24.retry-delay=10ms"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
class MatchListApiTest {

    /** 서울 날짜 2026-10-20 */
    private static final Instant START = Instant.parse("2026-10-20T01:00:00Z");
    private static final String ALICE = "alice@example.com";
    private static final String BOB = "bob@example.com";
    private static final String PASSWORD = "correct-horse";
    private static final String PATH = "/api/me/matches";
    /**
     * 본으로 쓰는 stub fixture 레코드. 사용자구분 개인, 성별 둘 다 Y, 연령 19~120, 생애는 축산업인만 Y, 가구 전부 Y.
     * {@link #ALICE_PROFILE}이면 해당이다.
     */
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
    private static final Map<String, Object> ALICE_PROFILE = profile("FEMALE", List.of("LIVESTOCK_FARMER"),
            List.of("NONE"));
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
    private final Map<SourceEndpoint, ObjectNode> templates = new EnumMap<>(SourceEndpoint.class);

    @BeforeEach
    void setUp() throws Exception {
        clock.set(START);
        stub.reset();
        for (SourceEndpoint endpoint : SourceEndpoint.values()) {
            templates.put(endpoint, stub.record(endpoint, HANWOO).deepCopy());
        }
        FIXTURE_IDS.forEach(stub::removeService);
        mailSender.clear();
        signUpSteps = new SignUpSteps(mockMvc, jsonMapper, mailSender);
        signUpSteps.signUp(ALICE, PASSWORD);
    }

    @Test
    void 해당_다음_확인_필요_순이고_같은_결과_안에서는_검색과_같은_이름_서비스_ID_순이다() throws Exception {
        // 수집 순서가 정렬 순서와 다르도록 섞어 넣는다.
        service("T07", "가 혜택", "개인", AGE_ON_BOUNDARY);
        service("T03", "ABD 지원", "개인", UNCHANGED);
        service("T06", "나 혜택", "법인/시설/단체", UNCHANGED);
        service("T02", "ABC 지원", "개인", UNCHANGED);
        service("T05", "Abe 지원", "개인", AGE_ON_BOUNDARY);
        service("T04", "가 혜택", "개인", AGE_ON_BOUNDARY);
        service("T01", "abc 지원", "개인", UNCHANGED);
        collectionService.collectAll();
        putProfile(ALICE, ALICE_PROFILE);

        JsonNode body = list(ALICE);

        // DB 콜레이션은 대소문자를 같게 보므로 abc·ABC는 서비스 ID 순이고 ABD가 그 뒤다.
        // Java 문자열 비교로 정렬하면 ABC·ABD·Abe·abc 순이 되어 검색과 달라진다.
        assertThat(ids(body)).containsExactly("T01", "T02", "T03", "T05", "T04", "T07");
        assertThat(results(body)).containsExactly("MATCH", "MATCH", "MATCH", "NEEDS_CHECK", "NEEDS_CHECK",
                "NEEDS_CHECK");
        List<String> searched = searchIds();
        assertThat(ids(body).subList(0, 3)).isEqualTo(searched.stream()
                .filter(List.of("T01", "T02", "T03")::contains).toList());
        assertThat(ids(body).subList(3, 6)).isEqualTo(searched.stream()
                .filter(List.of("T04", "T05", "T07")::contains).toList());

        assertThat(body.get("totalCount").asLong()).isEqualTo(6);
        assertThat(body.get("matchCount").asLong()).isEqualTo(3);
        assertThat(body.get("needsCheckCount").asLong()).isEqualTo(3);
        assertThat(body.get("page").asInt()).isEqualTo(1);
        assertThat(body.get("size").asInt()).isEqualTo(20);
        assertThat(body.get("judgedOn").asString()).isEqualTo("2026-10-20");
        assertThat(texts(body.get("notices"))).containsExactly("CODE_BASED", "GROUP_RULE_INTERPRETED",
                "AGENCY_REGION_BASED", "NOT_FINAL");
        assertThat(body.get("regionScope").asString()).isEqualTo("MY_REGION");
        assertThat(texts(body.get("uncomparedConditions")))
                .containsExactly("INCOME", "RESIDENCE", "OTHER_SOURCE_CONDITIONS");

        JsonNode first = body.get("items").get(0);
        assertThat(first.get("benefit").get("name").asString()).isEqualTo("abc 지원");
        assertThat(first.get("benefit").has("lastSeenAt")).isTrue();
        assertItem(first.get("items").get("personalTarget"), "MET", "PERSONAL_USER_TYPE");
        assertItem(first.get("items").get("lifeAttributes"), "MET", "CODE_INCLUDES");
        assertItem(first.get("items").get("agencyRegion"), "MET", "REGION_INCLUDES");
        assertItem(body.get("items").get(3).get("items").get("age"), "NEEDS_CHECK", "AGE_ON_BOUNDARY");
        // 비교한 값과 원문은 혜택 하나의 조건 비교에서 본다.
        assertThat(first.get("items").get("age").has("ageStart")).isFalse();
        assertThat(first.has("source")).isFalse();
    }

    @Test
    void 개인_대상이_아닌_혜택은_목록에_없고_검색으로는_찾는다() throws Exception {
        service("P01", "개인 혜택", "개인||가구", UNCHANGED);
        service("C01", "기관 혜택", "법인/시설/단체", UNCHANGED);
        service("C02", "소상공인 혜택", "소상공인", UNCHANGED);
        service("C03", "소상공인 기관 혜택", "소상공인||법인/시설/단체", UNCHANGED);
        collectionService.collectAll();
        putProfile(ALICE, ALICE_PROFILE);

        JsonNode body = list(ALICE);

        assertThat(ids(body)).containsExactly("P01");
        assertThat(body.get("totalCount").asLong()).isEqualTo(1);
        assertThat(searchIds()).contains("C01", "C02", "C03");
    }

    @Test
    void 확인_필요_혜택은_숨기지_않는다() throws Exception {
        service("N01", "경계값 혜택", "개인", AGE_ON_BOUNDARY);
        service("N02", "사용자구분 없는 혜택", null, UNCHANGED);
        service("N03", "지원조건 없는 혜택", "개인", null);
        service("N04", "생애 속성 밖 혜택", "개인", conditions -> {
            conditions.putNull("JA0315");
            conditions.put("JA0326", "Y");
        });
        collectionService.collectAll();
        putProfile(ALICE, ALICE_PROFILE);

        JsonNode body = list(ALICE);

        assertThat(ids(body)).containsExactlyInAnyOrder("N01", "N02", "N03", "N04");
        assertThat(results(body)).containsOnly("NEEDS_CHECK");
        assertItem(item(body, "N01").get("age"), "NEEDS_CHECK", "AGE_ON_BOUNDARY");
        assertItem(item(body, "N02").get("personalTarget"), "NEEDS_CHECK", "USER_TYPE_MISSING");
        assertItem(item(body, "N03").get("age"), "NEEDS_CHECK", "CONDITIONS_NOT_COLLECTED");
        assertItem(item(body, "N04").get("lifeAttributes"), "NEEDS_CHECK", "CODE_EXCLUDES");
    }

    @Test
    void 프로필을_저장한_적이_없어도_목록이_나오고_개인_대상_혜택은_모두_확인_필요다() throws Exception {
        service("E01", "가 혜택", "개인", UNCHANGED);
        service("E02", "나 혜택", "개인", AGE_ON_BOUNDARY);
        service("E03", "다 혜택", "법인/시설/단체", UNCHANGED);
        collectionService.collectAll();

        JsonNode body = list(ALICE);

        assertThat(ids(body)).containsExactly("E01", "E02");
        assertThat(results(body)).containsOnly("NEEDS_CHECK");
        for (JsonNode element : body.get("items")) {
            for (String item : List.of("age", "gender", "lifeAttributes", "householdAttributes", "agencyRegion")) {
                assertItem(element.get("items").get(item), "NEEDS_CHECK", "PROFILE_MISSING");
            }
        }
    }

    @Test
    void 목록_항목의_판정은_같은_혜택의_상세_비교와_같다() throws Exception {
        service("D01", "해당 혜택", "개인", UNCHANGED);
        service("D02", "경계값 혜택", "개인", AGE_ON_BOUNDARY);
        service("D03", "사용자구분 없는 혜택", null, UNCHANGED);
        service("D04", "지원조건 없는 혜택", "개인", null);
        service("D05", "성별 밖 혜택", "개인", conditions -> conditions.putNull("JA0102"));
        service("D06", "다른 시도 혜택", "개인", UNCHANGED, "시군구", SUYEONG);
        service("D07", "지역 모름 혜택", "개인", UNCHANGED, "시군구", "9999999");
        service("D08", "중앙 혜택", "개인", UNCHANGED, "중앙행정기관", "1741000");
        collectionService.collectAll();
        putProfile(ALICE, ALICE_PROFILE);

        JsonNode body = list(ALICE, "regionScope", "ALL");

        assertThat(body.get("items").size()).isEqualTo(8);
        for (JsonNode element : body.get("items")) {
            String serviceId = element.get("benefit").get("serviceId").asString();
            JsonNode detail = json(perform(ALICE, PATH + "/" + serviceId).andExpect(status().isOk()));
            assertThat(element.get("result").asString()).as(serviceId).isEqualTo(detail.get("result").asString());
            for (String item : List.of("personalTarget", "age", "gender", "lifeAttributes", "householdAttributes",
                    "agencyRegion")) {
                JsonNode expected = detail.get("items").get(item);
                assertItem(element.get("items").get(item), expected.get("status").asString(),
                        expected.get("reason").asString());
            }
        }
    }

    @Test
    void 프로필을_수정한_직후_조회하면_바뀐_값으로_판정된_순서가_나온다() throws Exception {
        service("U01", "가 혜택", "개인", AGE_ON_BOUNDARY);
        service("U02", "나 혜택", "개인", UNCHANGED);
        collectionService.collectAll();
        putProfile(ALICE, profile("FEMALE", List.of("EMPLOYEE"), List.of("NONE")));
        JsonNode before = list(ALICE);
        assertThat(ids(before)).containsExactly("U01", "U02");
        assertThat(results(before)).containsExactly("NEEDS_CHECK", "NEEDS_CHECK");

        putProfile(ALICE, profile("FEMALE", List.of("EMPLOYEE", "LIVESTOCK_FARMER"), List.of("NONE")));

        JsonNode after = list(ALICE);
        assertThat(ids(after)).containsExactly("U02", "U01");
        assertThat(results(after)).containsExactly("MATCH", "NEEDS_CHECK");
    }

    @Test
    void 쪽_크기는_기본_20건_최대_100건이고_전체_건수는_미해당을_뺀_건수다() throws Exception {
        for (int i = 1; i <= 25; i++) {
            service("S%02d".formatted(i), "혜택 %02d".formatted(i), "개인", UNCHANGED);
        }
        service("C01", "기관 혜택", "법인/시설/단체", UNCHANGED);
        collectionService.collectAll();
        putProfile(ALICE, ALICE_PROFILE);

        JsonNode defaults = list(ALICE);
        assertThat(defaults.get("totalCount").asLong()).isEqualTo(25);
        assertThat(defaults.get("size").asInt()).isEqualTo(20);
        assertThat(defaults.get("items").size()).isEqualTo(20);
        assertThat(list(ALICE, "size", "100").get("items").size()).isEqualTo(25);
        assertThat(list(ALICE, "page", "2").get("items").size()).isEqualTo(5);
    }

    @Test
    void 쪽_경계에서_중복_누락이_없고_마지막_쪽을_넘은_번호는_빈_목록이다() throws Exception {
        service("B01", "같은 이름", "개인", UNCHANGED);
        service("B02", "같은 이름", "개인", AGE_ON_BOUNDARY);
        service("B03", "같은 이름", "개인", UNCHANGED);
        service("B04", "같은 이름", "개인", AGE_ON_BOUNDARY);
        service("B05", "다른 이름", "개인", UNCHANGED);
        service("B06", "기관 혜택", "법인/시설/단체", UNCHANGED);
        collectionService.collectAll();
        putProfile(ALICE, ALICE_PROFILE);

        List<String> all = ids(list(ALICE, "size", "100"));
        assertThat(all).containsExactly("B01", "B03", "B05", "B02", "B04");
        List<String> paged = new ArrayList<>();
        for (int page = 1; page <= 3; page++) {
            paged.addAll(ids(list(ALICE, "page", String.valueOf(page), "size", "2")));
        }
        assertThat(paged).containsExactlyElementsOf(all).doesNotHaveDuplicates();

        JsonNode beyond = list(ALICE, "page", "4", "size", "2");
        assertThat(beyond.get("totalCount").asLong()).isEqualTo(5);
        assertThat(beyond.get("items").size()).isZero();
        JsonNode farBeyond = list(ALICE, "page", String.valueOf(Integer.MAX_VALUE), "size", "100");
        assertThat(farBeyond.get("totalCount").asLong()).isEqualTo(5);
        assertThat(farBeyond.get("items").size()).isZero();
    }

    @Test
    void 잘못된_쪽_번호와_크기는_검색과_같은_오류다() throws Exception {
        List<List<String>> invalid = List.of(
                List.of("size", "0"), List.of("size", "101"), List.of("size", "abc"),
                List.of("page", "0"), List.of("page", "-1"), List.of("page", "abc"), List.of("page", "99999999999"),
                List.of("page", "0", "size", "101"));
        for (List<String> params : invalid) {
            String[] pairs = params.toArray(String[]::new);
            JsonNode matches = json(perform(ALICE, PATH, pairs).andExpect(status().isBadRequest()));
            JsonNode search = json(mockMvc.perform(withParams(get("/api/benefits"), pairs))
                    .andExpect(status().isBadRequest()));

            assertThat(matches.get("code").asString()).as(params.toString()).isEqualTo("INVALID_INPUT");
            assertThat(matches.get("message")).isEqualTo(search.get("message"));
            assertThat(errors(matches)).as(params.toString()).isNotEmpty().isEqualTo(errors(search));
        }
    }

    @Test
    void 사용자마다_자기_프로필로_판정한다() throws Exception {
        service("A01", "가 혜택", "개인", UNCHANGED);
        collectionService.collectAll();
        signUpSteps.signUp(BOB, PASSWORD);
        putProfile(ALICE, ALICE_PROFILE);
        putProfile(BOB, profile("MALE", List.of("EMPLOYEE"), null));

        assertThat(results(list(ALICE))).containsExactly("MATCH");
        JsonNode bob = list(BOB);
        assertThat(results(bob)).containsExactly("NEEDS_CHECK");
        assertItem(bob.get("items").get(0).get("items").get("householdAttributes"), "NEEDS_CHECK", "PROFILE_MISSING");

        // 요청에 다른 사용자의 ID를 넣어도 토큰 주인의 프로필로 판정한다(결정 0018).
        JsonNode asBob = json(perform(ALICE, PATH, "userId", String.valueOf(userId(BOB)))
                .andExpect(status().isOk()));
        assertThat(results(asBob)).containsExactly("MATCH");
    }

    @Test
    void 인증되지_않은_요청은_거절한다() throws Exception {
        assertError(mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized()), "UNAUTHENTICATED");
        assertError(mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer not-a-token"))
                .andExpect(status().isUnauthorized()), "UNAUTHENTICATED");

        // 탈퇴 뒤 남은 액세스 토큰도 401이다.
        String token = token(ALICE);
        mockMvc.perform(post("/api/me/withdrawal")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("password", PASSWORD))))
                .andExpect(status().isNoContent());
        assertError(mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isUnauthorized()), "UNAUTHENTICATED");
    }

    @Test
    void 기본_범위는_내_지역_기관과_지역_없는_기관이고_전체_지역은_개인_대상_혜택_전체다() throws Exception {
        regionServices();
        putProfile(ALICE, ALICE_PROFILE);

        JsonNode mine = list(ALICE);
        assertThat(mine.get("regionScope").asString()).isEqualTo("MY_REGION");
        assertThat(ids(mine)).containsExactly("R01", "R02", "R03", "R04", "R06");
        assertThat(results(mine)).containsOnly("MATCH");
        assertThat(mine.get("totalCount").asLong()).isEqualTo(5);
        assertThat(mine.get("matchCount").asLong()).isEqualTo(5);
        assertThat(mine.get("needsCheckCount").asLong()).isEqualTo(0);

        JsonNode all = list(ALICE, "regionScope", "ALL");
        assertThat(all.get("regionScope").asString()).isEqualTo("ALL");
        // 다른 지역과 지역 모름은 확인 필요라 해당 뒤에 온다. 개인 대상이 아닌 혜택은 어느 범위에도 없다.
        assertThat(ids(all)).containsExactly("R01", "R02", "R03", "R04", "R06", "R05", "R07", "R08");
        assertThat(all.get("totalCount").asLong()).isEqualTo(8);
        // 건수는 쪽과 관계없이 지역 범위 전체의 결과별 수다.
        assertThat(all.get("matchCount").asLong()).isEqualTo(5);
        assertThat(all.get("needsCheckCount").asLong()).isEqualTo(3);
        assertItem(item(all, "R01").get("agencyRegion"), "MET", "REGION_INCLUDES");
        assertItem(item(all, "R03").get("agencyRegion"), "MET", "REGION_INCLUDES");
        assertItem(item(all, "R06").get("agencyRegion"), "MET", "NO_RESTRICTION");
        assertItem(item(all, "R05").get("agencyRegion"), "NEEDS_CHECK", "REGION_EXCLUDES");
        assertItem(item(all, "R07").get("agencyRegion"), "NEEDS_CHECK", "REGION_EXCLUDES");
        assertItem(item(all, "R08").get("agencyRegion"), "NEEDS_CHECK", "AGENCY_REGION_UNKNOWN");
        // 다른 지역 혜택도 나머지 항목은 모두 충족이다. 지역 때문에 확인 필요다.
        assertItem(item(all, "R07").get("age"), "MET", "CODE_INCLUDES");
        assertThat(searchIds()).contains("C01");
    }

    @Test
    void 시도만_입력했으면_기본_범위에_도내_시군구_기관이_모두_든다() throws Exception {
        regionServices();
        Map<String, Object> profile = new HashMap<>(ALICE_PROFILE);
        profile.put("sigunguCode", null);
        putProfile(ALICE, profile);

        JsonNode body = list(ALICE);

        assertThat(body.get("regionScope").asString()).isEqualTo("MY_REGION");
        // 시도 기관과 지역 없는 기관은 해당이고, 도내 시군구 기관은 비교할 수 없어 확인 필요다.
        assertThat(ids(body)).containsExactly("R02", "R03", "R06", "R01", "R04", "R05");
        assertItem(item(body, "R01").get("agencyRegion"), "NEEDS_CHECK", "PROFILE_MISSING");
        assertItem(item(body, "R05").get("agencyRegion"), "NEEDS_CHECK", "PROFILE_MISSING");
        assertItem(item(body, "R02").get("agencyRegion"), "MET", "REGION_INCLUDES");
    }

    @Test
    void 거주지가_없으면_요청과_관계없이_전체_지역이다() throws Exception {
        regionServices();
        Map<String, Object> profile = new HashMap<>(ALICE_PROFILE);
        profile.put("sidoCode", null);
        profile.put("sigunguCode", null);
        putProfile(ALICE, profile);

        for (String[] params : List.of(new String[0], new String[]{"regionScope", "MY_REGION"},
                new String[]{"regionScope", "ALL"})) {
            JsonNode body = list(ALICE, params);

            assertThat(body.get("regionScope").asString()).isEqualTo("ALL");
            assertThat(body.get("totalCount").asLong()).isEqualTo(8);
            // 지역 없는 기관만 해당이다.
            assertThat(ids(body).getFirst()).isEqualTo("R06");
            assertThat(results(body)).containsExactly("MATCH", "NEEDS_CHECK", "NEEDS_CHECK", "NEEDS_CHECK",
                    "NEEDS_CHECK", "NEEDS_CHECK", "NEEDS_CHECK", "NEEDS_CHECK");
        }
    }

    @Test
    void 지역_범위를_바꿔도_순서와_쪽_처리는_같다() throws Exception {
        regionServices();
        putProfile(ALICE, ALICE_PROFILE);

        for (String scope : List.of("MY_REGION", "ALL")) {
            List<String> whole = ids(list(ALICE, "regionScope", scope, "size", "100"));
            List<String> paged = new ArrayList<>();
            for (int page = 1; page <= 4; page++) {
                JsonNode body = list(ALICE, "regionScope", scope, "size", "2", "page", String.valueOf(page));
                assertThat(body.get("totalCount").asLong()).isEqualTo(whole.size());
                paged.addAll(ids(body));
            }
            assertThat(paged).isEqualTo(whole);
        }
        List<String> all = ids(list(ALICE, "regionScope", "ALL"));
        List<String> mine = ids(list(ALICE));
        assertThat(all.stream().filter(mine::contains).toList()).isEqualTo(mine);
    }

    @Test
    void 프로필의_거주지를_바꾸면_다음_목록에_바로_반영된다() throws Exception {
        regionServices();
        putProfile(ALICE, ALICE_PROFILE);
        assertThat(ids(list(ALICE))).contains("R01").doesNotContain("R07");

        Map<String, Object> busan = new HashMap<>(ALICE_PROFILE);
        busan.put("sidoCode", "6260000");
        busan.put("sigunguCode", SUYEONG);
        putProfile(ALICE, busan);

        JsonNode body = list(ALICE);
        assertThat(ids(body)).containsExactly("R06", "R07");
        assertItem(item(body, "R07").get("agencyRegion"), "MET", "REGION_INCLUDES");
    }

    @Test
    void 잘못된_지역_범위는_잘못된_입력이다() throws Exception {
        putProfile(ALICE, ALICE_PROFILE);

        JsonNode body = json(perform(ALICE, PATH, "regionScope", "NEARBY").andExpect(status().isBadRequest()));

        assertThat(body.get("code").asString()).isEqualTo("INVALID_INPUT");
        assertThat(errors(body)).containsExactly(List.of("regionScope", "NEARBY", "형식이 올바르지 않습니다."));
    }

    /**
     * 경상북도 상주시 거주자 기준으로 운영 기관 지역이 다른 개인 대상 혜택을 넣는다. 지원조건은 모두 {@link #ALICE_PROFILE}에 해당한다.
     */
    private void regionServices() {
        service("R01", "가 상주시", "개인", UNCHANGED, "시군구", SANGJU);
        service("R02", "나 경상북도", "개인", UNCHANGED, "광역시도", GYEONGBUK);
        service("R03", "다 경상북도교육청", "개인", UNCHANGED, "교육청", GYEONGBUK_EDUCATION);
        service("R04", "라 상주시 공기업", "개인", UNCHANGED, "지방공기업", SANGJU);
        service("R05", "마 포항시", "개인", UNCHANGED, "시군구", POHANG);
        service("R06", "바 중앙", "개인", UNCHANGED, "중앙행정기관", "1741000");
        service("R07", "사 부산 수영구", "개인", UNCHANGED, "시군구", SUYEONG);
        service("R08", "아 지역 모름", "개인", UNCHANGED, "시군구", "9999999");
        service("C01", "자 상주시 기관 대상", "법인/시설/단체", UNCHANGED, "시군구", SANGJU);
        collectionService.collectAll();
    }

    /**
     * 본 레코드를 세 경로에 복사해 혜택 하나를 stub에 더한다. 운영 기관은 본 레코드(경상북도 상주시)다.
     *
     * @param userType   사용자구분. null이면 필드를 뺀다.
     * @param conditions 지원조건 레코드를 바꾸는 함수. null이면 지원조건 레코드를 넣지 않는다(수집되지 않음).
     */
    private void service(String serviceId, String name, String userType, Consumer<ObjectNode> conditions) {
        service(serviceId, name, userType, conditions, null, null);
    }

    /**
     * @param agencyType 소관기관유형. null이면 본 레코드 값이다.
     * @param agencyCode 소관기관코드. null이면 본 레코드 값이다.
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

    private static Map<String, Object> profile(String gender, List<String> life, List<String> household) {
        Map<String, Object> body = new HashMap<>();
        body.put("birthDate", BIRTH_DATE);
        body.put("gender", gender);
        body.put("lifeAttributes", life);
        body.put("householdAttributes", household);
        body.put("sidoCode", GYEONGBUK);
        body.put("sigunguCode", SANGJU);
        return body;
    }

    private void putProfile(String email, Map<String, Object> body) throws Exception {
        mockMvc.perform(put("/api/me/profile")
                        .header(HttpHeaders.AUTHORIZATION, token(email))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    private JsonNode list(String email, String... params) throws Exception {
        return json(perform(email, PATH, params).andExpect(status().isOk()));
    }

    private ResultActions perform(String email, String path, String... params) throws Exception {
        return mockMvc.perform(withParams(get(path).header(HttpHeaders.AUTHORIZATION, token(email)), params));
    }

    private static MockHttpServletRequestBuilder withParams(MockHttpServletRequestBuilder builder, String... params) {
        for (int i = 0; i < params.length; i += 2) {
            builder.param(params[i], params[i + 1]);
        }
        return builder;
    }

    /** 로그인 없이 검색한 전체 혜택의 서비스 ID(이름·서비스 ID 순) */
    private List<String> searchIds() throws Exception {
        JsonNode body = json(mockMvc.perform(get("/api/benefits").param("size", "100")).andExpect(status().isOk()));
        List<String> ids = new ArrayList<>();
        body.get("items").forEach(item -> ids.add(item.get("serviceId").asString()));
        return ids;
    }

    /** 요청마다 토큰 주인을 바꿔 쓰므로 그때그때 발급한다. */
    private String token(String email) {
        return "Bearer " + accessTokenIssuer.issue(userId(email)).value();
    }

    private Long userId(String email) {
        return jdbcTemplate.queryForObject("SELECT id FROM user_account WHERE email = ?", Long.class, email);
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

    /** 목록에서 서비스 ID로 찾은 항목의 항목별 판정 */
    private static JsonNode item(JsonNode body, String serviceId) {
        for (JsonNode element : body.get("items")) {
            if (element.get("benefit").get("serviceId").asString().equals(serviceId)) {
                return element.get("items");
            }
        }
        throw new AssertionError("목록에 없다: " + serviceId);
    }

    /** 오류 항목의 순서는 정해져 있지 않으므로 집합으로 비교한다. */
    private static Set<List<String>> errors(JsonNode body) {
        Set<List<String>> errors = new HashSet<>();
        body.get("errors").forEach(error -> errors.add(List.of(error.get("field").asString(),
                error.get("value").asString(), error.get("reason").asString())));
        return errors;
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
