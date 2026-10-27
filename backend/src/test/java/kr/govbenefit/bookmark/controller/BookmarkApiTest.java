package kr.govbenefit.bookmark.controller;

import static kr.govbenefit.collection.domain.SourceEndpoint.SERVICE_LIST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
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
import kr.govbenefit.collection.Gov24StubServer;
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

/**
 * 북마크 저장·해제·목록(REQ-SAVE-01의 관심 목록)을 Testcontainers MySQL로 검증한다.
 * 혜택은 대부분 저장소로 직접 넣고, 다시 수집되거나 수집 목록에서 사라지는 경우는 로컬 stub으로 실제 수집을 돌린다.
 * 저장 시각과 수집 시각은 고정한 시계로 옮긴다.
 */
@SpringBootTest(properties = {"gov24.schedule.enabled=false", "gov24.retry-delay=10ms"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
class BookmarkApiTest {

    private static final Instant START = Instant.parse("2026-10-20T01:00:00Z");
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final Duration COOLDOWN = Duration.ofSeconds(60);
    private static final String ALICE = "alice@example.com";
    private static final String BOB = "bob@example.com";
    private static final String PASSWORD = "correct-horse";
    private static final String PATH = "/api/me/bookmarks";
    /** stub fixture의 실제 서비스 ID */
    private static final String A = "511000000138";
    private static final String B = "403000000173";

    private static final Gov24StubServer stub = Gov24StubServer.start();

    @DynamicPropertySource
    static void gov24Properties(DynamicPropertyRegistry registry) {
        registry.add("gov24.base-url", stub::baseUrl);
        registry.add("gov24.service-key", () -> "stub-service-key-5c0d7a");
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
    private BenefitRepository benefitRepository;
    @Autowired
    private CollectionService collectionService;

    private SignUpSteps signUpSteps;
    private String alice;

    @BeforeEach
    void setUp() throws Exception {
        clock.set(START);
        stub.reset();
        mailSender.clear();
        signUpSteps = new SignUpSteps(mockMvc, jsonMapper, mailSender);
        alice = signUp(ALICE);
    }

    @Test
    void 저장하면_목록에_나타나고_해제하면_사라진다() throws Exception {
        benefit("S1");

        assertThat(save(alice, "S1").andExpect(status().isNoContent())
                .andReturn().getResponse().getContentAsString()).isEmpty();

        JsonNode list = list(alice);
        assertThat(list.get("totalCount").asLong()).isEqualTo(1);
        JsonNode item = list.get("items").get(0);
        assertThat(OffsetDateTime.parse(item.get("bookmarkedAt").asString())).isEqualTo(START.atZone(ZONE)
                .toOffsetDateTime());
        assertThat(item.get("bookmarkedAt").asString()).endsWith("+09:00");
        assertThat(item.get("benefit").get("serviceId").asString()).isEqualTo("S1");
        assertThat(item.get("benefit").get("name").asString()).isEqualTo("혜택 S1");

        assertThat(remove(alice, "S1").andExpect(status().isNoContent())
                .andReturn().getResponse().getContentAsString()).isEmpty();

        assertThat(serviceIds(list(alice))).isEmpty();
        assertThat(list(alice).get("totalCount").asLong()).isZero();
    }

    @Test
    void 다시_저장하면_한_건이고_저장_시각이_바뀌어_맨_위로_온다() throws Exception {
        benefit("S1");
        benefit("S2");
        save(alice, "S1").andExpect(status().isNoContent());
        clock.advance(Duration.ofMinutes(1));
        save(alice, "S2").andExpect(status().isNoContent());
        assertThat(serviceIds(list(alice))).containsExactly("S2", "S1");

        clock.advance(Duration.ofMinutes(1));
        save(alice, "S1").andExpect(status().isNoContent());

        JsonNode list = list(alice);
        assertThat(serviceIds(list)).containsExactly("S1", "S2");
        assertThat(list.get("totalCount").asLong()).isEqualTo(2);
        assertThat(OffsetDateTime.parse(list.get("items").get(0).get("bookmarkedAt").asString()))
                .isEqualTo(START.plus(Duration.ofMinutes(2)).atZone(ZONE).toOffsetDateTime());
        assertThat(bookmarkRows()).isEqualTo(2);
    }

    @Test
    void 저장하지_않았거나_이미_해제했거나_수집되지_않은_혜택을_해제해도_204다() throws Exception {
        benefit("S1");
        benefit("S2");
        save(alice, "S1").andExpect(status().isNoContent());

        remove(alice, "S2").andExpect(status().isNoContent());
        remove(alice, "NO-SUCH").andExpect(status().isNoContent());
        assertThat(serviceIds(list(alice))).containsExactly("S1");

        remove(alice, "S1").andExpect(status().isNoContent());
        remove(alice, "S1").andExpect(status().isNoContent());
        assertThat(serviceIds(list(alice))).isEmpty();
    }

    @Test
    void 수집되지_않은_서비스_ID는_저장을_거절하고_목록이_그대로다() throws Exception {
        benefit("S1");
        save(alice, "S1").andExpect(status().isNoContent());

        assertError(save(alice, "NO-SUCH").andExpect(status().isNotFound()), "NOT_FOUND");

        assertThat(serviceIds(list(alice))).containsExactly("S1");
        assertThat(bookmarkRows()).isEqualTo(1);
    }

    @Test
    void 다시_수집되어_혜택_정보가_바뀌면_목록에_바뀐_정보가_보인다() throws Exception {
        collectionService.collectAll();
        save(alice, A).andExpect(status().isNoContent());
        String before = list(alice).get("items").get(0).get("benefit").get("name").asString();

        stub.record(SERVICE_LIST, A).put("서비스명", "바뀐 혜택 이름");
        clock.advance(Duration.ofDays(1));
        collectionService.collectAll();
        alice = token(ALICE);

        JsonNode item = list(alice).get("items").get(0);
        assertThat(before).isNotEqualTo("바뀐 혜택 이름");
        assertThat(item.get("benefit").get("name").asString()).isEqualTo("바뀐 혜택 이름");
        // 다시 수집해도 저장 시각은 그대로다.
        assertThat(OffsetDateTime.parse(item.get("bookmarkedAt").asString()))
                .isEqualTo(START.atZone(ZONE).toOffsetDateTime());
    }

    @Test
    void 수집_목록에서_사라지거나_수집이_실패해도_북마크가_남는다() throws Exception {
        collectionService.collectAll();
        save(alice, A).andExpect(status().isNoContent());
        save(alice, B).andExpect(status().isNoContent());

        Instant secondRun = START.plus(Duration.ofDays(1));
        clock.set(secondRun);
        stub.removeService(A);
        collectionService.collectAll();
        alice = token(ALICE);

        assertThat(serviceIds(list(alice))).containsExactlyInAnyOrder(A, B);
        assertThat(lastSeenAt(list(alice), A)).isEqualTo(START.atZone(ZONE).toOffsetDateTime());
        assertThat(lastSeenAt(list(alice), B)).isEqualTo(secondRun.atZone(ZONE).toOffsetDateTime());

        clock.advance(Duration.ofDays(1));
        stub.override(SERVICE_LIST, 1, 503, "{\"code\":-1,\"msg\":\"일시 장애\"}");
        Long runId = collectionService.collectAll();
        alice = token(ALICE);

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM collection_run WHERE id = ?", String.class, runId))
                .isEqualTo("FAILED");
        assertThat(serviceIds(list(alice))).containsExactlyInAnyOrder(A, B);
        assertThat(bookmarkRows()).isEqualTo(2);
    }

    @Test
    void 목록은_마지막으로_저장한_순서이고_번호와_크기로_나눈다() throws Exception {
        for (String serviceId : List.of("S1", "S2", "S3")) {
            benefit(serviceId);
            save(alice, serviceId).andExpect(status().isNoContent());
            clock.advance(Duration.ofMinutes(1));
        }

        JsonNode first = list(alice, Map.of("page", "1", "size", "2"));
        assertThat(serviceIds(first)).containsExactly("S3", "S2");
        assertThat(first.get("totalCount").asLong()).isEqualTo(3);
        assertThat(first.get("page").asInt()).isEqualTo(1);
        assertThat(first.get("size").asInt()).isEqualTo(2);
        assertThat(serviceIds(list(alice, Map.of("page", "2", "size", "2")))).containsExactly("S1");
        JsonNode beyond = list(alice, Map.of("page", "3", "size", "2"));
        assertThat(serviceIds(beyond)).isEmpty();
        assertThat(beyond.get("totalCount").asLong()).isEqualTo(3);

        JsonNode defaults = list(alice);
        assertThat(defaults.get("page").asInt()).isEqualTo(1);
        assertThat(defaults.get("size").asInt()).isEqualTo(20);
        assertThat(serviceIds(defaults)).containsExactly("S3", "S2", "S1");

        for (Map<String, String> params : List.of(Map.of("page", "0"), Map.of("size", "0"), Map.of("size", "101"))) {
            assertError(perform(get(PATH), alice, params).andExpect(status().isBadRequest()), "INVALID_INPUT");
        }
    }

    @Test
    void 한_사용자의_저장_해제는_다른_사용자에게_영향을_주지_않는다() throws Exception {
        String bob = signUp(BOB);
        String bobId = String.valueOf(userId(BOB));
        for (String serviceId : List.of("S1", "S2", "S3")) {
            benefit(serviceId);
        }
        save(alice, "S1").andExpect(status().isNoContent());
        save(alice, "S2").andExpect(status().isNoContent());
        save(bob, "S1").andExpect(status().isNoContent());

        remove(alice, "S1").andExpect(status().isNoContent());

        assertThat(serviceIds(list(alice))).containsExactly("S2");
        assertThat(serviceIds(list(bob))).containsExactly("S1");

        // 요청에 다른 사용자의 ID를 넣어도 토큰 주인의 북마크만 읽고 바꾼다(결정 0018).
        Map<String, String> asBob = Map.of("userId", bobId);
        perform(put(PATH + "/S3"), alice, asBob).andExpect(status().isNoContent());
        perform(delete(PATH + "/S1"), alice, asBob).andExpect(status().isNoContent());
        assertThat(serviceIds(list(alice, asBob))).containsExactlyInAnyOrder("S2", "S3");
        assertThat(serviceIds(list(bob))).containsExactly("S1");
    }

    @Test
    void 토큰_없는_요청은_거절하고_북마크를_바꾸지_않는다() throws Exception {
        benefit("S1");
        save(alice, "S1").andExpect(status().isNoContent());

        assertUnauthenticated(mockMvc.perform(put(PATH + "/S1")));
        assertUnauthenticated(mockMvc.perform(delete(PATH + "/S1")));
        assertUnauthenticated(mockMvc.perform(get(PATH)));

        assertThat(serviceIds(list(alice))).containsExactly("S1");
    }

    @Test
    void 저장_여부는_저장한_혜택만_요청_순서로_돌려준다() throws Exception {
        for (String serviceId : List.of("S1", "S2", "S3")) {
            benefit(serviceId);
        }
        save(alice, "S1").andExpect(status().isNoContent());
        save(alice, "S3").andExpect(status().isNoContent());

        assertThat(saved(alice, "S3", "S2", "S1", "S3")).containsExactly("S3", "S1");
        assertThat(saved(alice, "S2")).isEmpty();
    }

    @Test
    void 저장_여부는_다른_사용자의_북마크를_보지_않는다() throws Exception {
        String bob = signUp(BOB);
        benefit("S1");
        benefit("S2");
        save(bob, "S1").andExpect(status().isNoContent());
        save(alice, "S2").andExpect(status().isNoContent());

        assertThat(saved(alice, "S1", "S2")).containsExactly("S2");
        assertThat(saved(bob, "S1", "S2")).containsExactly("S1");
    }

    @Test
    void 저장_여부는_수집되지_않은_서비스_ID를_무시한다() throws Exception {
        benefit("S1");
        save(alice, "S1").andExpect(status().isNoContent());

        assertThat(saved(alice, "NOPE", "S1")).containsExactly("S1");
    }

    @Test
    void 저장_여부의_서비스_ID가_없거나_100개를_넘으면_400이다() throws Exception {
        assertInvalidServiceIds(perform(get(PATH + "/saved"), alice, Map.of()));

        MockHttpServletRequestBuilder tooMany = get(PATH + "/saved");
        for (int i = 0; i < 101; i++) {
            tooMany.param("serviceId", "S" + i);
        }
        assertInvalidServiceIds(perform(tooMany, alice, Map.of()));

        MockHttpServletRequestBuilder max = get(PATH + "/saved");
        for (int i = 0; i < 100; i++) {
            max.param("serviceId", "S" + i);
        }
        perform(max, alice, Map.of()).andExpect(status().isOk());
    }

    @Test
    void 저장_여부는_토큰이_없으면_401이다() throws Exception {
        assertUnauthenticated(mockMvc.perform(get(PATH + "/saved").param("serviceId", "S1")));
    }

    @Test
    void 탈퇴하면_북마크가_지워지고_다시_가입한_사용자의_목록은_비어_있다() throws Exception {
        benefit("S1");
        benefit("S2");
        save(alice, "S1").andExpect(status().isNoContent());
        Long aliceId = userId(ALICE);

        mockMvc.perform(post("/api/me/withdrawal")
                        .header(HttpHeaders.AUTHORIZATION, alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("password", PASSWORD))))
                .andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bookmark WHERE user_id = ?", Integer.class,
                aliceId)).isZero();
        // 남은 액세스 토큰의 저장은 FK 위반(500)이 아니라 401이다.
        assertUnauthenticated(save(alice, "S2"));
        assertUnauthenticated(remove(alice, "S1"));
        assertUnauthenticated(perform(get(PATH), alice, Map.of()));
        assertThat(bookmarkRows()).isZero();

        clock.advance(COOLDOWN);
        String again = signUp(ALICE);

        JsonNode list = list(again);
        assertThat(serviceIds(list)).isEmpty();
        assertThat(list.get("totalCount").asLong()).isZero();
    }

    private String signUp(String email) throws Exception {
        signUpSteps.signUp(email, PASSWORD);
        return token(email);
    }

    /** 액세스 토큰은 15분 뒤 만료되므로 시계를 옮긴 뒤에는 다시 발급한다. */
    private String token(String email) {
        return "Bearer " + accessTokenIssuer.issue(userId(email)).value();
    }

    private Long userId(String email) {
        return jdbcTemplate.queryForObject("SELECT id FROM user_account WHERE email = ?", Long.class, email);
    }

    private void benefit(String serviceId) {
        benefitRepository.save(Benefit.create(serviceId, new BenefitContent("혜택 " + serviceId, null, "생활안정",
                null, null, null, null, null, "상시", null, null, null, "기관 " + serviceId, "시군구", null, null,
                "https://www.gov.kr/portal/rcvfvrSvc/dtlEx/" + serviceId),
                new BenefitNormalization(null, null, ApplyPeriodRule.ALWAYS), START.atZone(ZONE).toLocalDateTime()));
    }

    private ResultActions save(String token, String serviceId) throws Exception {
        return perform(put(PATH + "/" + serviceId), token, Map.of());
    }

    private ResultActions remove(String token, String serviceId) throws Exception {
        return perform(delete(PATH + "/" + serviceId), token, Map.of());
    }

    private JsonNode list(String token) throws Exception {
        return list(token, Map.of());
    }

    private JsonNode list(String token, Map<String, String> params) throws Exception {
        return json(perform(get(PATH), token, params).andExpect(status().isOk()));
    }

    private List<String> saved(String token, String... serviceIds) throws Exception {
        MockHttpServletRequestBuilder request = get(PATH + "/saved").param("serviceId", serviceIds);
        List<String> ids = new ArrayList<>();
        json(perform(request, token, Map.of()).andExpect(status().isOk()))
                .get("serviceIds").forEach(id -> ids.add(id.asString()));
        return ids;
    }

    private void assertInvalidServiceIds(ResultActions result) throws Exception {
        JsonNode body = json(result.andExpect(status().isBadRequest()));
        assertThat(body.get("code").asString()).isEqualTo("INVALID_INPUT");
        assertThat(body.get("errors").get(0).get("field").asString()).isEqualTo("serviceIds");
        assertThat(body.get("errors").get(0).get("reason").asString()).isEqualTo("1개 이상 100개 이하여야 합니다.");
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, String token, Map<String, String> params)
            throws Exception {
        params.forEach(request::param);
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, token));
    }

    private int bookmarkRows() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bookmark", Integer.class);
    }

    private static List<String> serviceIds(JsonNode list) {
        List<String> ids = new ArrayList<>();
        list.get("items").forEach(item -> ids.add(item.get("benefit").get("serviceId").asString()));
        return ids;
    }

    private static OffsetDateTime lastSeenAt(JsonNode list, String serviceId) {
        for (JsonNode item : list.get("items")) {
            if (item.get("benefit").get("serviceId").asString().equals(serviceId)) {
                return OffsetDateTime.parse(item.get("benefit").get("lastSeenAt").asString());
            }
        }
        throw new AssertionError("목록에 없다: " + serviceId);
    }

    private void assertUnauthenticated(ResultActions result) throws Exception {
        assertError(result.andExpect(status().isUnauthorized()), "UNAUTHENTICATED");
    }

    private void assertError(ResultActions result, String code) throws Exception {
        assertThat(json(result).get("code").asString()).isEqualTo(code);
    }

    private JsonNode json(ResultActions result) throws Exception {
        return jsonMapper.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
