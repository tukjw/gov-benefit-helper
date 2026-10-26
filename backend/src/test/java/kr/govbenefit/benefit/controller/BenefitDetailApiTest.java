package kr.govbenefit.benefit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import kr.govbenefit.TestcontainersConfiguration;
import kr.govbenefit.benefit.domain.ApplyPeriodRule;
import kr.govbenefit.benefit.domain.Benefit;
import kr.govbenefit.benefit.domain.BenefitContent;
import kr.govbenefit.benefit.domain.BenefitDetail;
import kr.govbenefit.benefit.domain.BenefitDetailContent;
import kr.govbenefit.benefit.domain.BenefitNormalization;
import kr.govbenefit.benefit.repository.BenefitDetailRepository;
import kr.govbenefit.benefit.repository.BenefitRepository;
import kr.govbenefit.collection.Gov24StubServer;
import kr.govbenefit.collection.domain.CollectionRun;
import kr.govbenefit.collection.domain.CollectionStatus;
import kr.govbenefit.collection.repository.CollectionRunRepository;
import kr.govbenefit.common.api.ApiTimes;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 혜택 상세 API를 Testcontainers MySQL로 검증한다. 모든 시나리오가 끝날 때 공공 API stub에 요청이 없었는지 확인한다.
 */
@SpringBootTest(properties = "gov24.schedule.enabled=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class BenefitDetailApiTest {

    private static final LocalDateTime SEEN_AT = LocalDateTime.of(2026, 10, 4, 4, 0, 12, 345_000_000);
    private static final LocalDateTime MODIFIED_AT = LocalDateTime.of(2026, 9, 27, 14, 11, 21);
    private static final String GUIDE_URL = "https://www.gov.kr/portal/rcvfvrSvc/dtlEx/";

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
    private BenefitDetailRepository benefitDetailRepository;
    @Autowired
    private CollectionRunRepository collectionRunRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private JsonMapper jsonMapper;

    @BeforeEach
    void setUp() {
        stub.reset();
        for (String table : List.of("bookmark", "benefit_detail", "benefit", "source_record_history", "source_record",
                "collection_quarantine", "collection_failure", "collection_run_step", "collection_run")) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        Benefit full = benefitRepository.save(Benefit.create("FULL", new BenefitContent("청년 월세 지원",
                "청년 주거비 부담 완화", "주거·자립", "개인", "현금", "만 19~34세 무주택 청년", "중위소득 60% 이하",
                "월 최대 20만원, 최대 12개월", "2026.3.1.~2026.12.31.", "방문, 온라인", "02-120",
                "주민센터", "서울특별시", "광역시도", "6110000", "주거정책과", GUIDE_URL + "FULL"),
                new BenefitNormalization(null, MODIFIED_AT, ApplyPeriodRule.DATE), SEEN_AT));
        detail(full, "https://www.bokjiro.go.kr/apply", "주민센터 방문 또는 온라인 신청");

        Benefit nulls = benefitRepository.save(Benefit.create("NULLS", new BenefitContent("빈 값 혜택", null,
                "행정·안전", null, null, "누구나 아님", null, "지원 내용", "1차 3월, 2차 9월", null, null, null,
                null, "지방공기업", null, null, GUIDE_URL + "NULLS"),
                new BenefitNormalization(null, null, ApplyPeriodRule.MULTIPLE_PERIODS), SEEN_AT));
        benefitDetailRepository.save(BenefitDetail.create(nulls.getId(), new BenefitDetailContent(null,
                "우편 접수", "신청서", null, null, null, null, null, null, null, null, null), SEEN_AT));

        benefit("NO-DETAIL");
        detail(benefit("HTTP"), "http://107.kr", "방문");
        detail(benefit("NO-SCHEME"), "www.hrd.go.kr", "방문");
        detail(benefit("BAD-JS"), "javascript:alert(1)", "방문 접수");
        detail(benefit("BAD-TEXT"), "각 시군구 홈페이지 참고", "방문 접수");
        detail(benefit("NO-URL"), null, "방문 접수");
    }

    @AfterEach
    void 상세_조회는_공공_API를_호출하지_않는다() {
        assertThat(stub.requestCount()).isZero();
    }

    @Test
    void 로그인_없이_목록과_상세_항목을_조회한다() throws Exception {
        JsonNode body = detail("FULL");

        assertThat(body.get("serviceId").asString()).isEqualTo("FULL");
        assertThat(body.get("name").asString()).isEqualTo("청년 월세 지원");
        assertThat(body.get("summary").asString()).isEqualTo("청년 주거비 부담 완화");
        assertThat(body.get("field").asString()).isEqualTo("주거·자립");
        assertThat(body.get("agencyName").asString()).isEqualTo("서울특별시");
        assertThat(body.get("agencyType").asString()).isEqualTo("광역시도");
        assertThat(body.get("departmentName").asString()).isEqualTo("주거정책과");
        assertThat(body.get("supportType").asString()).isEqualTo("현금");
        assertThat(body.get("supportContent").asString()).isEqualTo("월 최대 20만원, 최대 12개월");
        assertThat(body.get("target").asString()).isEqualTo("만 19~34세 무주택 청년");
        assertThat(body.get("selectionCriteria").asString()).isEqualTo("중위소득 60% 이하");
        assertThat(body.get("applyPeriod").get("text").asString()).isEqualTo("2026.3.1.~2026.12.31.");
        assertThat(body.get("applyPeriod").get("type").asString()).isEqualTo("FIXED_DATE");
        assertThat(body.get("applyPeriod").get("needsReview").asBoolean()).isFalse();
        assertThat(body.get("applyMethodType").asString()).isEqualTo("방문, 온라인");
        assertThat(body.get("receptionAgency").asString()).isEqualTo("주민센터");
        assertThat(body.get("phoneInquiry").asString()).isEqualTo("02-120");
        assertThat(body.get("guideUrl").asString()).isEqualTo(GUIDE_URL + "FULL");
        assertThat(body.get("sourceModifiedAt").asString()).isEqualTo("2026-09-27T14:11:21+09:00");
        assertThat(body.get("lastSeenAt").asString()).isEqualTo("2026-10-04T04:00:12.345+09:00");
        assertThat(body.has("freshness")).isFalse();

        JsonNode detail = body.get("detail");
        assertThat(detail.get("purpose").asString()).isEqualTo("목적 FULL");
        assertThat(detail.get("applyMethod").asString()).isEqualTo("주민센터 방문 또는 온라인 신청");
        assertThat(detail.get("requiredDocuments").asString()).isEqualTo("서류 FULL");
        assertThat(detail.get("officialCheckDocuments").asString()).isEqualTo("공무원 확인 FULL");
        assertThat(detail.get("selfCheckDocuments").asString()).isEqualTo("본인 확인 FULL");
        assertThat(detail.get("contact").asString()).isEqualTo("문의 FULL");
        assertThat(detail.get("receptionAgencyName").asString()).isEqualTo("접수 FULL");
        assertThat(detail.get("onlineApplyUrl").asString()).isEqualTo("https://www.bokjiro.go.kr/apply");
        assertThat(detail.get("onlineApplyUrlText").asString()).isEqualTo("https://www.bokjiro.go.kr/apply");
    }

    @Test
    void 빈_값은_null로_두고_신청기한_확인_필요를_표시한다() throws Exception {
        JsonNode body = detail("NULLS");

        assertThat(body.get("summary").isNull()).isTrue();
        assertThat(body.get("selectionCriteria").isNull()).isTrue();
        assertThat(body.get("applyMethodType").isNull()).isTrue();
        assertThat(body.get("phoneInquiry").isNull()).isTrue();
        assertThat(body.get("agencyName").isNull()).isTrue();
        assertThat(body.get("applyPeriod").get("text").asString()).isEqualTo("1차 3월, 2차 9월");
        assertThat(body.get("applyPeriod").get("type").asString()).isEqualTo("FIXED_DATE");
        assertThat(body.get("applyPeriod").get("needsReview").asBoolean()).isTrue();

        JsonNode detail = body.get("detail");
        assertThat(detail.isNull()).isFalse();
        assertThat(detail.get("purpose").isNull()).isTrue();
        assertThat(detail.get("contact").isNull()).isTrue();
        assertThat(detail.get("officialCheckDocuments").isNull()).isTrue();
        assertThat(detail.get("applyMethod").asString()).isEqualTo("우편 접수");
        assertThat(detail.get("requiredDocuments").asString()).isEqualTo("신청서");
    }

    @Test
    void 출처_수정_시각이_없으면_비우고_수집_시각은_따로_준다() throws Exception {
        JsonNode body = detail("NULLS");

        assertThat(body.get("sourceModifiedAt").isNull()).isTrue();
        assertThat(body.get("lastSeenAt").asString()).isEqualTo("2026-10-04T04:00:12.345+09:00");
    }

    @Test
    void 온라인_신청_주소는_안내_페이지와_구분하고_링크로_쓸_수_있게_정리한다() throws Exception {
        JsonNode https = detail("FULL");
        assertThat(https.get("guideUrl").asString()).isEqualTo(GUIDE_URL + "FULL");
        assertThat(https.get("detail").get("onlineApplyUrl").asString()).isEqualTo("https://www.bokjiro.go.kr/apply");

        assertOnlineApplyUrl("HTTP", "http://107.kr", "http://107.kr");
        assertOnlineApplyUrl("NO-SCHEME", "https://www.hrd.go.kr", "www.hrd.go.kr");
    }

    @Test
    void 링크로_쓸_수_없거나_없는_온라인_신청_주소는_원문과_접수_방법만_준다() throws Exception {
        assertOnlineApplyUrl("BAD-JS", null, "javascript:alert(1)");
        assertOnlineApplyUrl("BAD-TEXT", null, "각 시군구 홈페이지 참고");
        assertOnlineApplyUrl("NO-URL", null, null);
        for (String serviceId : List.of("BAD-JS", "BAD-TEXT", "NO-URL")) {
            assertThat(detail(serviceId).get("detail").get("applyMethod").asString()).isEqualTo("방문 접수");
        }
    }

    @Test
    void 상세_미수집_혜택은_detail이_null이고_목록_항목은_준다() throws Exception {
        JsonNode body = detail("NO-DETAIL");

        assertThat(body.has("detail")).isTrue();
        assertThat(body.get("detail").isNull()).isTrue();
        assertThat(body.has("detailStatus")).isFalse();
        assertThat(body.get("supportContent").asString()).isEqualTo("지원 내용 NO-DETAIL");
        assertThat(body.get("target").asString()).isEqualTo("대상 NO-DETAIL");
        assertThat(body.get("applyPeriod").get("text").asString()).isEqualTo("상시");
        assertThat(body.get("applyMethodType").asString()).isEqualTo("방문");
        assertThat(body.get("guideUrl").asString()).isEqualTo(GUIDE_URL + "NO-DETAIL");
    }

    @Test
    void 없는_서비스_ID는_찾을_수_없음이다() throws Exception {
        JsonNode body = json(mockMvc.perform(get("/api/benefits/{serviceId}", "NO-SUCH-ID"))
                .andExpect(status().isNotFound()));

        assertThat(body.get("code").asString()).isEqualTo("NOT_FOUND");
        assertThat(body.get("errors").size()).isZero();
    }

    @Test
    void 수집_실행이_없거나_FAILED만_있어도_저장된_상세를_준다() throws Exception {
        String applyMethod = "주민센터 방문 또는 온라인 신청";
        assertThat(detail("FULL").get("detail").get("applyMethod").asString()).isEqualTo(applyMethod);

        run(CollectionStatus.FAILED, now().minusHours(72));
        run(CollectionStatus.FAILED, now().minusMinutes(5));

        assertThat(detail("FULL").get("detail").get("applyMethod").asString()).isEqualTo(applyMethod);
    }

    private Benefit benefit(String serviceId) {
        return benefitRepository.save(Benefit.create(serviceId, new BenefitContent("혜택 " + serviceId, null,
                "생활안정", null, null, "대상 " + serviceId, null, "지원 내용 " + serviceId, "상시", "방문", null,
                null, null, "시군구", null, null, GUIDE_URL + serviceId),
                new BenefitNormalization(null, null, ApplyPeriodRule.ALWAYS), SEEN_AT));
    }

    private void detail(Benefit benefit, String onlineApplyUrl, String applyMethod) {
        String id = benefit.getServiceId();
        benefitDetailRepository.save(BenefitDetail.create(benefit.getId(), new BenefitDetailContent("목적 " + id,
                applyMethod, "서류 " + id, "공무원 확인 " + id, "본인 확인 " + id, "문의 " + id, onlineApplyUrl,
                null, null, null, "접수 " + id, null), SEEN_AT));
    }

    /** 실행 기록을 남긴다. DB 정밀도(마이크로초)에 맞춰 자른다. */
    private void run(CollectionStatus status, LocalDateTime finishedAt) {
        LocalDateTime truncated = finishedAt.truncatedTo(ChronoUnit.MICROS);
        CollectionRun run = CollectionRun.start(truncated.minusMinutes(1));
        run.finish(status, null, null, truncated);
        collectionRunRepository.save(run);
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ApiTimes.ZONE);
    }

    private void assertOnlineApplyUrl(String serviceId, String link, String text) throws Exception {
        JsonNode detail = detail(serviceId).get("detail");
        assertText(detail.get("onlineApplyUrl"), link);
        assertText(detail.get("onlineApplyUrlText"), text);
    }

    private static void assertText(JsonNode node, String expected) {
        if (expected == null) {
            assertThat(node.isNull()).isTrue();
        } else {
            assertThat(node.asString()).isEqualTo(expected);
        }
    }

    private JsonNode detail(String serviceId) throws Exception {
        return json(mockMvc.perform(get("/api/benefits/{serviceId}", serviceId)).andExpect(status().isOk()));
    }

    private JsonNode json(ResultActions result) throws Exception {
        return jsonMapper.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
