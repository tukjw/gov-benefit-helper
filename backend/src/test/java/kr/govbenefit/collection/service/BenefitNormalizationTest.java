package kr.govbenefit.collection.service;

import static kr.govbenefit.collection.domain.SourceEndpoint.SERVICE_DETAIL;
import static kr.govbenefit.collection.domain.SourceEndpoint.SERVICE_LIST;
import static kr.govbenefit.collection.domain.SourceEndpoint.SUPPORT_CONDITIONS;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import kr.govbenefit.TestcontainersConfiguration;
import kr.govbenefit.benefit.domain.ApplyPeriodRule;
import kr.govbenefit.benefit.domain.ApplyPeriodType;
import kr.govbenefit.benefit.domain.Benefit;
import kr.govbenefit.benefit.repository.BenefitRepository;
import kr.govbenefit.collection.Gov24StubServer;
import kr.govbenefit.collection.repository.SourceRecordRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 수집한 목록 원문을 해석해 저장하는 값을 로컬 stub 서버와 Testcontainers MySQL로 검증한다.
 * fixture는 {@link CollectionServiceTest}와 같은 A·B·C이고, 시나리오마다 stub 레코드를 바꾼다.
 */
@SpringBootTest(properties = {
        "gov24.schedule.enabled=false",
        "gov24.retry-delay=10ms"
})
@Import(TestcontainersConfiguration.class)
class BenefitNormalizationTest {

    private static final String A = "511000000138";
    private static final String B = "403000000173";
    private static final String C = "O00024000031";
    private static final String NORMALIZED_COLUMNS = "service_id, source_registered_at, source_modified_at, "
            + "apply_period_type, apply_period_rule, apply_period_needs_review";

    private static final Gov24StubServer stub = Gov24StubServer.start();

    @DynamicPropertySource
    static void gov24Properties(DynamicPropertyRegistry registry) {
        registry.add("gov24.base-url", stub::baseUrl);
        registry.add("gov24.service-key", () -> "stub-service-key-2b7e91");
        registry.add("gov24.per-page", () -> 2);
    }

    @AfterAll
    static void closeStub() {
        stub.close();
    }

    @Autowired
    private CollectionService collectionService;
    @Autowired
    private BenefitRepository benefitRepository;
    @Autowired
    private SourceRecordRepository sourceRecordRepository;
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
    }

    @Test
    void 출처_수정_등록_시각이_없으면_수집_시각으로_채우지_않는다() {
        stub.record(SERVICE_LIST, B).putNull("수정일시");
        stub.record(SERVICE_LIST, B).remove("등록일시");
        stub.record(SERVICE_LIST, C).put("수정일시", "2026-08-12");

        collectionService.collectAll();

        Benefit a = benefit(A);
        assertThat(a.getSourceRegisteredAt()).isEqualTo(LocalDateTime.of(2021, 9, 23, 12, 34, 56));
        assertThat(a.getSourceModifiedAt()).isEqualTo(LocalDateTime.of(2026, 9, 14, 11, 43, 46));
        Benefit b = benefit(B);
        assertThat(b.getSourceRegisteredAt()).isNull();
        assertThat(b.getSourceModifiedAt()).isNull();
        assertThat(b.getLastSeenAt()).isNotNull();
        Benefit c = benefit(C);
        assertThat(c.getSourceRegisteredAt()).isEqualTo(LocalDateTime.of(2022, 7, 15, 0, 0));
        assertThat(c.getSourceModifiedAt()).isNull();
        assertThat(jdbcTemplate.queryForList(
                "SELECT service_id FROM benefit WHERE source_modified_at IS NULL ORDER BY service_id", String.class))
                .containsExactly(B, C);
    }

    @Test
    void 복수_기간이나_애매한_문구는_원문과_함께_확인_필요로_저장한다() {
        String multiple = "1차 2026.3.2.~3.13., 2차 2026.9.1.~9.12.";
        String earlyClose = "2026. 4. 1.~ 예산 소진 시";
        String other = "무인민원발급기 운영시간";
        stub.record(SERVICE_LIST, A).put("신청기한", multiple);
        stub.record(SERVICE_LIST, B).put("신청기한", earlyClose);
        stub.record(SERVICE_LIST, C).put("신청기한", other);

        collectionService.collectAll();

        assertApplyPeriod(A, multiple, ApplyPeriodRule.MULTIPLE_PERIODS, ApplyPeriodType.FIXED_DATE);
        assertApplyPeriod(B, earlyClose, ApplyPeriodRule.DATE_WITH_EARLY_CLOSE, ApplyPeriodType.FIXED_DATE);
        assertApplyPeriod(C, other, ApplyPeriodRule.OTHER, ApplyPeriodType.UNKNOWN);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM benefit WHERE apply_period_needs_review = TRUE", Integer.class)).isEqualTo(3);
    }

    @Test
    void 지역과_조건이_빈_혜택은_빈_값으로_저장하고_기본값을_채우지_않는다() {
        ObjectNode list = stub.record(SERVICE_LIST, B);
        list.putNull("소관기관명");
        list.putNull("선정기준");
        list.put("지원대상", "  ");
        stub.record(SERVICE_DETAIL, B).put("자치법규", "");
        ObjectNode conditions = stub.record(SUPPORT_CONDITIONS, B);
        conditions.propertyNames().stream().filter(name -> !name.equals("서비스ID")).toList()
                .forEach(conditions::putNull);

        collectionService.collectAll();

        Map<String, Object> benefit = jdbcTemplate.queryForMap("SELECT * FROM benefit WHERE service_id = ?", B);
        assertThat(benefit.get("agency_name")).isNull();
        assertThat(benefit.get("selection_criteria")).isNull();
        assertThat(benefit.get("target")).isNull();
        Map<String, Object> detail = jdbcTemplate.queryForMap(
                "SELECT d.* FROM benefit_detail d JOIN benefit b ON b.id = d.benefit_id WHERE b.service_id = ?", B);
        assertThat(detail.get("local_laws")).isNull();
        // fixture B의 원문에는 '전국'·'누구나'가 없으므로 저장된 어느 값에도 없어야 한다.
        assertThat(benefit.values()).noneMatch(BenefitNormalizationTest::isDefaultFill);
        assertThat(detail.values()).noneMatch(BenefitNormalizationTest::isDefaultFill);

        JsonNode storedConditions = jsonMapper.readTree(
                sourceRecordRepository.findByServiceIdAndEndpoint(B, SUPPORT_CONDITIONS).orElseThrow().getPayload());
        assertThat(storedConditions).isEqualTo(conditions);
        assertThat(storedConditions.properties()).allSatisfy(field -> {
            if (!field.getKey().equals("서비스ID")) {
                assertThat(field.getValue().isNull()).isTrue();
            }
        });
    }

    @Test
    void 정규화된_혜택에서_근거가_된_목록_원문을_조회한다() {
        stub.record(SERVICE_LIST, A).put("신청기한", "2026. 4. 1.~ 예산 소진 시");
        stub.record(SERVICE_LIST, B).putNull("수정일시");

        collectionService.collectAll();

        for (String serviceId : List.of(A, B, C)) {
            Benefit benefit = benefit(serviceId);
            JsonNode source = jsonMapper.readTree(sourceRecordRepository
                    .findByServiceIdAndEndpoint(benefit.getServiceId(), SERVICE_LIST).orElseThrow().getPayload());

            assertThat(source).isEqualTo(stub.record(SERVICE_LIST, serviceId));
            assertThat(benefit.getApplyPeriodText()).isEqualTo(source.get("신청기한").asString());
            assertThat(benefit.normalization()).isEqualTo(Gov24RecordMapper.toBenefitNormalization(source));
            assertThat(benefit.getApplyPeriodType()).isEqualTo(benefit.getApplyPeriodRule().type());
            assertThat(benefit.getApplyPeriodNeedsReview()).isEqualTo(benefit.getApplyPeriodRule().needsReview());
        }
    }

    @Test
    void 같은_원문을_다시_정규화하면_결과가_같다() {
        stub.record(SERVICE_LIST, A).put("신청기한", "1차 2026.3.2.~3.13., 2차 2026.9.1.~9.12.");
        stub.record(SERVICE_LIST, B).putNull("수정일시");
        collectionService.collectAll();
        List<Map<String, Object>> first = normalizedColumns();
        List<Map<String, Object>> firstUpdatedAt = jdbcTemplate.queryForList(
                "SELECT service_id, updated_at FROM benefit ORDER BY service_id");

        collectionService.collectAll();

        assertThat(normalizedColumns()).isEqualTo(first);
        assertThat(jdbcTemplate.queryForList("SELECT service_id, updated_at FROM benefit ORDER BY service_id"))
                .isEqualTo(firstUpdatedAt);

        // 마이그레이션 직후처럼 해석 값이 빈 행도 다음 수집에서 같은 값으로 채워진다.
        jdbcTemplate.update("UPDATE benefit SET source_registered_at = NULL, source_modified_at = NULL, "
                + "apply_period_type = NULL, apply_period_rule = NULL, apply_period_needs_review = NULL");
        collectionService.collectAll();

        assertThat(normalizedColumns()).isEqualTo(first);
    }

    private Benefit benefit(String serviceId) {
        return benefitRepository.findByServiceId(serviceId).orElseThrow();
    }

    private void assertApplyPeriod(String serviceId, String text, ApplyPeriodRule rule, ApplyPeriodType type) {
        Benefit benefit = benefit(serviceId);
        assertThat(benefit.getApplyPeriodText()).isEqualTo(text);
        assertThat(benefit.getApplyPeriodRule()).isEqualTo(rule);
        assertThat(benefit.getApplyPeriodType()).isEqualTo(type);
        assertThat(benefit.getApplyPeriodNeedsReview()).isTrue();
    }

    private List<Map<String, Object>> normalizedColumns() {
        return jdbcTemplate.queryForList("SELECT " + NORMALIZED_COLUMNS + " FROM benefit ORDER BY service_id");
    }

    private static boolean isDefaultFill(Object value) {
        return value instanceof String text && (text.contains("전국") || text.contains("누구나"));
    }
}
