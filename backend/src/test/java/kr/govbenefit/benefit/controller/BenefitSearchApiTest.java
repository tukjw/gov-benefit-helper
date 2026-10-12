package kr.govbenefit.benefit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import kr.govbenefit.TestcontainersConfiguration;
import kr.govbenefit.benefit.domain.ApplyPeriodRule;
import kr.govbenefit.benefit.domain.Benefit;
import kr.govbenefit.benefit.domain.BenefitContent;
import kr.govbenefit.benefit.domain.BenefitNormalization;
import kr.govbenefit.benefit.repository.BenefitRepository;
import kr.govbenefit.collection.Gov24StubServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 혜택 검색 API를 Testcontainers MySQL로 검증한다. 정렬·LIKE 비교는 실제 정렬 규칙(결정 0001)을 따른다.
 * 모든 시나리오가 끝날 때 공공 API stub에 요청이 없었는지 확인한다.
 */
@SpringBootTest(properties = "gov24.schedule.enabled=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class BenefitSearchApiTest {

    private static final LocalDateTime SEEN_AT = LocalDateTime.of(2026, 10, 4, 4, 0, 12, 345_000_000);
    private static final int FIXTURE_COUNT = 15;

    private static final Gov24StubServer stub = Gov24StubServer.start();

    @DynamicPropertySource
    static void gov24Properties(DynamicPropertyRegistry registry) {
        registry.add("gov24.base-url", stub::baseUrl);
        registry.add("gov24.service-key", () -> "stub-service-key-2b7e91");
    }

    @AfterAll
    static void closeStub() {
        stub.close();
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private BenefitRepository benefitRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private JsonMapper jsonMapper;

    @BeforeEach
    void setUp() {
        stub.reset();
        jdbcTemplate.update("DELETE FROM benefit_detail");
        jdbcTemplate.update("DELETE FROM benefit");
        // 같은 이름 5건. 서비스 ID를 저장 순서와 다르게 섞는다.
        benefit("SAME-3", "같은 이름 지원", "생활안정", "시군구");
        benefit("SAME-1", "같은 이름 지원", "보육·교육", "시군구");
        benefit("SAME-5", "같은 이름 지원", "생활안정", "광역시도");
        benefit("SAME-2", "같은 이름 지원", "보건·의료", "중앙행정기관");
        benefit("SAME-4", "같은 이름 지원", "생활안정", "시군구");
        benefit("Y-RENT", "청년 월세 지원", "생활안정", "시군구");
        benefit("Y-RENT2", "월세 지원 청년 특별", "생활안정", "광역시도");
        benefit("Y-JOB", "청년 취업 지원", "고용·창업", "중앙행정기관");
        benefit("RENT", "월세 바우처", "주거·자립", "지방출자_출연기관");
        benefit("PCT", "소득 100% 지원", "생활안정", "공공기관");
        benefit("K1000", "소득 1000 지원", "생활안정", "공공기관");
        benefit("UND", "a_b 사업", "보건·의료", "지방출자_출연기관");
        benefit("AXB", "axb 사업", "보건·의료", "교육청");
        save("NULLS", "빈 값 혜택", null, "행정·안전", "지방공기업", null, null, ApplyPeriodRule.NO_VALUE);
        benefit("GONE", "사라진 혜택", "문화·환경", "시군구");
        jdbcTemplate.update("UPDATE benefit SET last_seen_at = '2026-09-01 10:00:00' WHERE service_id = 'GONE'");
    }

    @AfterEach
    void 검색은_공공_API를_호출하지_않는다() {
        assertThat(stub.requestCount()).isZero();
    }

    @Test
    void 로그인_없이_전체_목록을_검색한다() throws Exception {
        JsonNode body = search();

        assertThat(body.get("totalCount").asLong()).isEqualTo(FIXTURE_COUNT);
        assertThat(body.get("page").asInt()).isEqualTo(1);
        assertThat(body.get("size").asInt()).isEqualTo(20);
        assertThat(body.get("items").size()).isEqualTo(FIXTURE_COUNT);
    }

    @Test
    void 키워드의_단어를_모두_포함한_혜택만_어순과_공백에_관계없이_찾는다() throws Exception {
        List<String> expected = List.of("Y-RENT2", "Y-RENT");

        assertThat(ids(search("keyword", "청년 월세"))).containsExactlyElementsOf(expected);
        assertThat(ids(search("keyword", "월세 청년"))).containsExactlyElementsOf(expected);
        assertThat(ids(search("keyword", "  청년　월세 "))).containsExactlyElementsOf(expected);
        assertThat(ids(search("keyword", "청년"))).containsExactlyInAnyOrder("Y-RENT", "Y-RENT2", "Y-JOB");
    }

    @Test
    void 빈_키워드와_공백만_있는_키워드는_전체_목록이다() throws Exception {
        assertThat(search("keyword", "").get("totalCount").asLong()).isEqualTo(FIXTURE_COUNT);
        assertThat(search("keyword", "   ").get("totalCount").asLong()).isEqualTo(FIXTURE_COUNT);
        assertThat(search("keyword", "　").get("totalCount").asLong()).isEqualTo(FIXTURE_COUNT);
    }

    @Test
    void 키워드의_LIKE_특수_문자는_글자_그대로_찾는다() throws Exception {
        assertThat(ids(search("keyword", "100%"))).containsExactly("PCT");
        assertThat(ids(search("keyword", "a_b"))).containsExactly("UND");
    }

    @Test
    void 키워드는_100자_단어_10개까지다() throws Exception {
        assertThat(search("keyword", "가".repeat(100)).get("totalCount").asLong()).isZero();
        assertThat(search("keyword", "가 나 다 라 마 바 사 아 자 차").get("totalCount").asLong()).isZero();

        assertInvalidInput("keyword", "keyword", "가".repeat(101));
        assertInvalidInput("keyword", "keyword", "가 나 다 라 마 바 사 아 자 차 카");
    }

    @Test
    void 한_필터_안의_값은_OR_필터_사이와_키워드는_AND다() throws Exception {
        assertThat(ids(search("field", "보육·교육", "field", "보건·의료")))
                .containsExactlyInAnyOrder("SAME-1", "SAME-2", "UND", "AXB");
        assertThat(ids(search("field", "생활안정", "agencyType", "시군구")))
                .containsExactlyInAnyOrder("SAME-3", "SAME-4", "Y-RENT");
        assertThat(ids(search("field", "생활안정", "agencyType", "시군구", "agencyType", "광역시도")))
                .containsExactlyInAnyOrder("SAME-3", "SAME-4", "SAME-5", "Y-RENT", "Y-RENT2");
        assertThat(ids(search("field", "생활안정", "agencyType", "시군구", "keyword", "청년")))
                .containsExactly("Y-RENT");
        assertThat(ids(search("agencyType", "지방출자_출연기관"))).containsExactlyInAnyOrder("RENT", "UND");
    }

    @Test
    void 모르는_필터_값은_빈_목록이고_빈_필터_값은_무시한다() throws Exception {
        JsonNode unknown = search("field", "없는 분야");
        assertThat(unknown.get("totalCount").asLong()).isZero();
        assertThat(unknown.get("items").size()).isZero();

        assertThat(search("field", "", "agencyType", " ").get("totalCount").asLong()).isEqualTo(FIXTURE_COUNT);
    }

    @Test
    void 이름순이고_같은_이름은_서비스_ID_순이다() throws Exception {
        List<String> ids = ids(search("size", "100"));

        assertThat(ids).containsExactlyElementsOf(jdbcTemplate.queryForList(
                "SELECT service_id FROM benefit ORDER BY name, service_id", String.class));
        int first = ids.indexOf("SAME-1");
        assertThat(ids.subList(first, first + 5)).containsExactly("SAME-1", "SAME-2", "SAME-3", "SAME-4", "SAME-5");
    }

    @Test
    void 같은_이름이_페이지_경계에_걸쳐도_중복_누락이_없다() throws Exception {
        List<String> all = ids(search("size", "100"));

        List<String> paged = new ArrayList<>();
        for (int page = 1; page <= 8; page++) {
            JsonNode body = search("page", String.valueOf(page), "size", "2");
            assertThat(ids(search("page", String.valueOf(page), "size", "2"))).isEqualTo(ids(body));
            paged.addAll(ids(body));
        }

        assertThat(paged).containsExactlyElementsOf(all).doesNotHaveDuplicates();
    }

    @Test
    void 쪽_크기는_기본_20건_최대_100건이다() throws Exception {
        for (int i = 1; i <= 10; i++) {
            benefit("EXTRA-%02d".formatted(i), "추가 혜택 " + i, "생활안정", "시군구");
        }

        JsonNode defaults = search();
        assertThat(defaults.get("totalCount").asLong()).isEqualTo(FIXTURE_COUNT + 10);
        assertThat(defaults.get("items").size()).isEqualTo(20);
        assertThat(search("size", "100").get("items").size()).isEqualTo(FIXTURE_COUNT + 10);
    }

    @Test
    void 범위를_벗어난_크기와_잘못된_페이지_번호는_잘못된_입력이다() throws Exception {
        assertInvalidInput("size", "size", "0");
        assertInvalidInput("size", "size", "101");
        assertInvalidInput("size", "size", "abc");
        assertInvalidInput("page", "page", "0");
        assertInvalidInput("page", "page", "-1");
        assertInvalidInput("page", "page", "abc");
        assertInvalidInput("page", "page", "99999999999");
    }

    @Test
    void 잘못된_입력은_항목별_오류를_담는다() throws Exception {
        JsonNode body = json(mockMvc.perform(request("size", "101")).andExpect(status().isBadRequest()));

        assertThat(body.get("code").asString()).isEqualTo("INVALID_INPUT");
        assertThat(body.get("message").asString()).isEqualTo("요청 값이 올바르지 않습니다.");
        assertThat(body.get("errors").size()).isEqualTo(1);
        JsonNode error = body.get("errors").get(0);
        assertThat(error.get("field").asString()).isEqualTo("size");
        assertThat(error.get("value").asString()).isEqualTo("101");
        assertThat(error.get("reason").asString()).isEqualTo("1 이상 100 이하여야 합니다.");
    }

    @Test
    void 여러_항목이_잘못되면_오류를_모두_담는다() throws Exception {
        JsonNode body = json(mockMvc.perform(request("page", "0", "size", "101", "keyword", "가".repeat(101)))
                .andExpect(status().isBadRequest()));

        assertThat(elements(body, "errors")).extracting(error -> error.get("field").asString())
                .containsExactlyInAnyOrder("page", "size", "keyword");
    }

    @Test
    void 형식이_잘못된_값은_받은_값과_고정_문구를_담는다() throws Exception {
        JsonNode body = json(mockMvc.perform(request("page", "abc")).andExpect(status().isBadRequest()));

        assertThat(body.get("errors").size()).isEqualTo(1);
        JsonNode error = body.get("errors").get(0);
        assertThat(error.get("field").asString()).isEqualTo("page");
        assertThat(error.get("value").asString()).isEqualTo("abc");
        assertThat(error.get("reason").asString()).isEqualTo("형식이 올바르지 않습니다.");
    }

    @Test
    void 결과_없음과_마지막_쪽을_넘은_번호는_빈_목록이다() throws Exception {
        JsonNode none = search("keyword", "존재하지 않는 혜택");
        assertThat(none.get("totalCount").asLong()).isZero();
        assertThat(none.get("items").size()).isZero();

        JsonNode beyond = search("page", "9", "size", "2");
        assertThat(beyond.get("totalCount").asLong()).isEqualTo(FIXTURE_COUNT);
        assertThat(beyond.get("items").size()).isZero();

        JsonNode farBeyond = search("page", String.valueOf(Integer.MAX_VALUE), "size", "100", "field", "생활안정");
        assertThat(farBeyond.get("totalCount").asLong()).isEqualTo(7);
        assertThat(farBeyond.get("items").size()).isZero();
    }

    @Test
    void 결과_건수는_조건에_맞는_전체_건수다() throws Exception {
        assertThat(search("field", "생활안정", "size", "2").get("totalCount").asLong()).isEqualTo(7);
        assertThat(search("keyword", "청년", "size", "1").get("totalCount").asLong()).isEqualTo(3);
    }

    @Test
    void 항목은_원문_값을_담고_없는_값은_채우지_않는다() throws Exception {
        JsonNode item = search("keyword", "청년 월세 지원", "agencyType", "시군구").get("items").get(0);

        assertThat(item.propertyNames()).containsExactly("serviceId", "name", "summary", "agencyName",
                "agencyType", "field", "applyPeriodText", "applyPeriodType", "applyPeriodNeedsReview", "lastSeenAt");
        assertThat(item.get("serviceId").asString()).isEqualTo("Y-RENT");
        assertThat(item.get("name").asString()).isEqualTo("청년 월세 지원");
        assertThat(item.get("summary").asString()).isEqualTo("요약 Y-RENT");
        assertThat(item.get("agencyName").asString()).isEqualTo("기관 Y-RENT");
        assertThat(item.get("agencyType").asString()).isEqualTo("시군구");
        assertThat(item.get("field").asString()).isEqualTo("생활안정");
        assertThat(item.get("applyPeriodText").asString()).isEqualTo("상시");
        assertThat(item.get("applyPeriodType").asString()).isEqualTo("ALWAYS");
        assertThat(item.get("applyPeriodNeedsReview").asBoolean()).isFalse();
        assertThat(item.get("lastSeenAt").asString()).isEqualTo("2026-10-04T04:00:12.345+09:00");

        JsonNode empty = search("keyword", "빈 값 혜택").get("items").get(0);
        assertThat(empty.get("summary").isNull()).isTrue();
        assertThat(empty.get("agencyName").isNull()).isTrue();
        assertThat(empty.get("applyPeriodText").isNull()).isTrue();
        assertThat(empty.get("applyPeriodType").asString()).isEqualTo("UNKNOWN");
    }

    @Test
    void 목록에서_사라진_혜택도_나오고_마지막_확인_시각으로_구분한다() throws Exception {
        JsonNode body = search("keyword", "혜택");

        assertThat(ids(body)).contains("GONE", "NULLS");
        for (JsonNode item : items(body)) {
            String expected = item.get("serviceId").asString().equals("GONE")
                    ? "2026-09-01T10:00:00+09:00" : "2026-10-04T04:00:12.345+09:00";
            assertThat(item.get("lastSeenAt").asString()).isEqualTo(expected);
        }
    }

    @Test
    void 없는_경로와_지원하지_않는_메서드도_같은_오류_형식이다() throws Exception {
        JsonNode notFound = json(mockMvc.perform(get("/api/unknown")).andExpect(status().isNotFound()));
        assertThat(notFound.get("code").asString()).isEqualTo("NOT_FOUND");
        assertThat(notFound.get("errors").size()).isZero();

        JsonNode notAllowed = json(mockMvc.perform(post("/api/benefits"))
                .andExpect(status().isMethodNotAllowed()));
        assertThat(notAllowed.get("code").asString()).isEqualTo("REQUEST_FAILED");
        assertThat(notAllowed.get("errors").size()).isZero();
    }

    private void benefit(String serviceId, String name, String field, String agencyType) {
        save(serviceId, name, "요약 " + serviceId, field, agencyType, "기관 " + serviceId, "상시",
                ApplyPeriodRule.ALWAYS);
    }

    private void save(String serviceId, String name, String summary, String field, String agencyType,
                      String agencyName, String applyPeriodText, ApplyPeriodRule rule) {
        BenefitContent content = new BenefitContent(name, summary, field, null, null, null, null, null,
                applyPeriodText, null, null, null, agencyName, agencyType, null, null, null);
        benefitRepository.save(Benefit.create(serviceId, content, new BenefitNormalization(null, null, rule),
                SEEN_AT));
    }

    private JsonNode search(String... params) throws Exception {
        return json(mockMvc.perform(request(params)).andExpect(status().isOk()));
    }

    private void assertInvalidInput(String field, String... params) throws Exception {
        JsonNode body = json(mockMvc.perform(request(params)).andExpect(status().isBadRequest()));
        assertThat(body.get("code").asString()).isEqualTo("INVALID_INPUT");
        assertThat(body.get("errors").size()).isEqualTo(1);
        assertThat(body.get("errors").get(0).get("field").asString()).isEqualTo(field);
    }

    private static MockHttpServletRequestBuilder request(String... params) {
        MockHttpServletRequestBuilder builder = get("/api/benefits");
        for (int i = 0; i < params.length; i += 2) {
            builder.param(params[i], params[i + 1]);
        }
        return builder;
    }

    private JsonNode json(ResultActions result) throws Exception {
        return jsonMapper.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static List<JsonNode> items(JsonNode body) {
        return elements(body, "items");
    }

    private static List<JsonNode> elements(JsonNode body, String arrayName) {
        List<JsonNode> elements = new ArrayList<>();
        body.get(arrayName).forEach(elements::add);
        return elements;
    }

    private static List<String> ids(JsonNode body) {
        return items(body).stream().map(item -> item.get("serviceId").asString()).toList();
    }
}
