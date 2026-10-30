package kr.govbenefit.collection.service;

import static kr.govbenefit.collection.domain.SourceEndpoint.SERVICE_DETAIL;
import static kr.govbenefit.collection.domain.SourceEndpoint.SERVICE_LIST;
import static kr.govbenefit.collection.domain.SourceEndpoint.SUPPORT_CONDITIONS;
import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import kr.govbenefit.TestcontainersConfiguration;
import kr.govbenefit.benefit.domain.Benefit;
import kr.govbenefit.benefit.domain.BenefitDetail;
import kr.govbenefit.benefit.repository.BenefitDetailRepository;
import kr.govbenefit.benefit.repository.BenefitRepository;
import kr.govbenefit.collection.Gov24StubServer;
import kr.govbenefit.collection.domain.CollectionErrorType;
import kr.govbenefit.collection.domain.CollectionQuarantine;
import kr.govbenefit.collection.domain.CollectionRun;
import kr.govbenefit.collection.domain.CollectionRunStep;
import kr.govbenefit.collection.domain.CollectionStatus;
import kr.govbenefit.collection.domain.QuarantineReason;
import kr.govbenefit.collection.domain.SourceEndpoint;
import kr.govbenefit.collection.domain.SourceRecord;
import kr.govbenefit.collection.domain.SourceRecordHistory;
import kr.govbenefit.collection.repository.CollectionFailureRepository;
import kr.govbenefit.collection.repository.CollectionQuarantineRepository;
import kr.govbenefit.collection.repository.CollectionRunRepository;
import kr.govbenefit.collection.repository.CollectionRunStepRepository;
import kr.govbenefit.collection.repository.SourceRecordHistoryRepository;
import kr.govbenefit.collection.repository.SourceRecordRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 로컬 stub 서버와 Testcontainers MySQL로 수집 전체 흐름을 검증한다.
 * fixture는 T0 스냅샷의 실제 레코드 3건(A·B·C)이고, 목록에는 A·B 뒤에 서비스ID가 없는 레코드 1건이 있다.
 * 쪽 크기를 2로 두어 경로마다 2쪽을 받는다.
 */
@SpringBootTest(properties = {
        "logging.level.kr.govbenefit=DEBUG",
        "logging.level.org.springframework.web.client=DEBUG",
        "gov24.schedule.enabled=false",
        "gov24.retry-delay=10ms"
})
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class CollectionServiceTest {

    private static final String SERVICE_KEY = "stub-service-key-6d1f0c";
    private static final String A = "511000000138";
    private static final String B = "403000000173";
    private static final String C = "O00024000031";
    private static final String NOT_FOUND = "{\"code\":0,\"msg\":\"정상\"}";

    private static final Gov24StubServer stub = Gov24StubServer.start();

    @DynamicPropertySource
    static void gov24Properties(DynamicPropertyRegistry registry) {
        registry.add("gov24.base-url", stub::baseUrl);
        registry.add("gov24.service-key", () -> SERVICE_KEY);
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
    private BenefitDetailRepository benefitDetailRepository;
    @Autowired
    private SourceRecordRepository sourceRecordRepository;
    @Autowired
    private SourceRecordHistoryRepository historyRepository;
    @Autowired
    private CollectionRunRepository runRepository;
    @Autowired
    private CollectionRunStepRepository stepRepository;
    @Autowired
    private CollectionQuarantineRepository quarantineRepository;
    @Autowired
    private CollectionFailureRepository failureRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private JsonMapper jsonMapper;
    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        stub.reset();
    }

    @Test
    void 목록_상세_지원조건을_서비스ID로_연결해_저장한다() {
        collectionService.collectAll();

        assertThat(benefitRepository.findAll()).extracting(Benefit::getServiceId).containsExactlyInAnyOrder(A, B, C);
        for (String serviceId : List.of(A, B, C)) {
            Benefit benefit = benefitRepository.findByServiceId(serviceId).orElseThrow();
            ObjectNode listRecord = stub.record(SERVICE_LIST, serviceId);
            ObjectNode detailRecord = stub.record(SERVICE_DETAIL, serviceId);
            assertThat(benefit.getName()).isEqualTo(listRecord.get("서비스명").asString());
            assertThat(benefit.getSupportContent()).isEqualTo(listRecord.get("지원내용").asString());
            assertThat(benefit.getDetailUrl()).isEqualTo(listRecord.get("상세조회URL").asString());

            BenefitDetail detail = benefitDetailRepository.findById(benefit.getId()).orElseThrow();
            assertThat(detail.getRequiredDocuments()).isEqualTo(detailRecord.get("구비서류").asString());
            assertThat(detail.getContact()).isEqualTo(detailRecord.get("문의처").asString());

            for (SourceEndpoint endpoint : SourceEndpoint.values()) {
                SourceRecord source = sourceRecordRepository.findByServiceIdAndEndpoint(serviceId, endpoint)
                        .orElseThrow();
                assertThat(jsonMapper.readTree(source.getPayload())).isEqualTo(stub.record(endpoint, serviceId));
            }
        }
        assertThat(historyRepository.count()).isZero();
    }

    @Test
    void 실행마다_시작_종료_시각과_상태_처리_건수를_남긴다() {
        Long runId = collectionService.collectAll();

        CollectionRun run = runRepository.findById(runId).orElseThrow();
        assertThat(run.getStatus()).isEqualTo(CollectionStatus.SUCCEEDED);
        assertThat(run.getStartedAt()).isNotNull();
        assertThat(run.getFinishedAt()).isAfterOrEqualTo(run.getStartedAt());
        assertThat(run.getErrorMessage()).isNull();

        List<CollectionRunStep> steps = stepRepository.findAllByRunIdOrderById(runId);
        assertThat(steps).extracting(CollectionRunStep::getEndpoint)
                .containsExactly(SERVICE_LIST, SERVICE_DETAIL, SUPPORT_CONDITIONS);
        assertThat(steps).allSatisfy(step -> {
            assertThat(step.getStatus()).isEqualTo(CollectionStatus.SUCCEEDED);
            assertThat(step.getFinishedAt()).isNotNull();
            assertThat(step.getPageCount()).isEqualTo(2);
            assertThat(step.getCreatedCount()).isEqualTo(3);
            assertThat(step.getChangedCount()).isZero();
            assertThat(step.getUnlinkedCount()).isZero();
        });
        assertThat(steps.get(0).getReceivedCount()).isEqualTo(4);
        assertThat(steps.get(0).getQuarantinedCount()).isEqualTo(1);
        assertThat(steps.get(1).getReceivedCount()).isEqualTo(3);
        assertThat(steps.get(2).getReceivedCount()).isEqualTo(3);
    }

    @Test
    void 실행이_끝나면_상태와_소요_시간_처리_건수를_지표로_남긴다() {
        double succeededBefore = succeededRuns();

        collectionService.collectAll();

        // 실행 수는 같은 컨텍스트를 쓰는 테스트끼리 누적되므로 차이로 본다.
        assertThat(succeededRuns()).isEqualTo(succeededBefore + 1);
        assertThat(lastRunStatus(CollectionStatus.SUCCEEDED)).isEqualTo(1);
        assertThat(lastRunStatus(CollectionStatus.PARTIAL)).isZero();
        assertThat(lastRunStatus(CollectionStatus.FAILED)).isZero();
        assertThat(meterRegistry.get("collection.last.run.finished").gauge().value()).isPositive();
        assertThat(meterRegistry.get("collection.last.run.duration").gauge().value()).isNotNegative();
        assertThat(lastRunRecords(SERVICE_LIST, "received")).isEqualTo(4);
        assertThat(lastRunRecords(SERVICE_LIST, "quarantined")).isEqualTo(1);
        assertThat(lastRunRecords(SERVICE_DETAIL, "created")).isEqualTo(3);
        assertThat(lastRunRecords(SUPPORT_CONDITIONS, "pages")).isEqualTo(2);
    }

    @Test
    void 필수_식별자가_없는_레코드는_격리하고_나머지는_저장한다() {
        Long runId = collectionService.collectAll();

        List<CollectionQuarantine> quarantines = quarantineRepository.findAllByRunId(runId);
        assertThat(quarantines).singleElement().satisfies(quarantine -> {
            assertThat(quarantine.getEndpoint()).isEqualTo(SERVICE_LIST);
            assertThat(quarantine.getPage()).isEqualTo(2);
            assertThat(quarantine.getReason()).isEqualTo(QuarantineReason.MISSING_SERVICE_ID);
            assertThat(jsonMapper.readTree(quarantine.getPayload()).get("서비스ID").isNull()).isTrue();
        });
        // 격리한 레코드와 같은 쪽의 C까지 저장되고, 실행은 성공으로 끝난다.
        assertThat(benefitRepository.findAll()).extracting(Benefit::getServiceId).containsExactlyInAnyOrder(A, B, C);
        assertThat(runRepository.findById(runId).orElseThrow().getStatus()).isEqualTo(CollectionStatus.SUCCEEDED);
    }

    @Test
    void 같은_응답으로_다시_수집하면_중복_생성도_변경도_없다() {
        collectionService.collectAll();
        List<Map<String, Object>> before = storedContent();

        Long secondRunId = collectionService.collectAll();

        assertThat(storedContent()).isEqualTo(before);
        assertThat(historyRepository.count()).isZero();
        assertThat(stepRepository.findAllByRunIdOrderById(secondRunId)).allSatisfy(step -> {
            assertThat(step.getCreatedCount()).isZero();
            assertThat(step.getChangedCount()).isZero();
            assertThat(step.getUnchangedCount()).isEqualTo(3);
        });
    }

    @Test
    void 조회수만_바뀌면_이력을_만들지_않고_최신_원문만_갱신한다() {
        collectionService.collectAll();
        SourceRecord before = sourceRecordRepository.findByServiceIdAndEndpoint(A, SERVICE_LIST).orElseThrow();
        Benefit benefitBefore = benefitRepository.findByServiceId(A).orElseThrow();
        ObjectNode record = stub.record(SERVICE_LIST, A);
        int viewCount = record.get("조회수").asInt() + 100;
        record.put("조회수", viewCount);

        collectionService.collectAll();

        SourceRecord after = sourceRecordRepository.findByServiceIdAndEndpoint(A, SERVICE_LIST).orElseThrow();
        assertThat(historyRepository.count()).isZero();
        assertThat(jsonMapper.readTree(after.getPayload()).get("조회수").asInt()).isEqualTo(viewCount);
        assertThat(after.getContentHash()).isEqualTo(before.getContentHash());
        assertThat(after.getContentChangedAt()).isEqualTo(before.getContentChangedAt());
        assertThat(benefitRepository.findByServiceId(A).orElseThrow().getUpdatedAt())
                .isEqualTo(benefitBefore.getUpdatedAt());
    }

    @Test
    void 내용이_바뀌면_이전_원문으로_이력을_하나_만든다() {
        collectionService.collectAll();
        SourceRecord before = sourceRecordRepository.findByServiceIdAndEndpoint(A, SERVICE_LIST).orElseThrow();
        Benefit benefitBefore = benefitRepository.findByServiceId(A).orElseThrow();
        String oldContent = benefitBefore.getSupportContent();
        stub.record(SERVICE_LIST, A).put("지원내용", oldContent + "\r\n○ 지원 금액 변경");

        Long runId = collectionService.collectAll();

        List<SourceRecordHistory> histories = historyRepository.findAllByServiceIdAndEndpointOrderById(A,
                SERVICE_LIST);
        assertThat(histories).singleElement().satisfies(history -> {
            assertThat(history.getContentHash()).isEqualTo(before.getContentHash());
            assertThat(jsonMapper.readTree(history.getPayload()).get("지원내용").asString()).isEqualTo(oldContent);
            assertThat(history.getReplacedByRunId()).isEqualTo(runId);
        });
        assertThat(historyRepository.count()).isEqualTo(1);

        SourceRecord after = sourceRecordRepository.findByServiceIdAndEndpoint(A, SERVICE_LIST).orElseThrow();
        assertThat(after.getContentHash()).isNotEqualTo(before.getContentHash());
        assertThat(after.getContentChangedAt()).isAfter(before.getContentChangedAt());
        Benefit benefitAfter = benefitRepository.findByServiceId(A).orElseThrow();
        assertThat(benefitAfter.getSupportContent()).endsWith("지원 금액 변경");
        assertThat(benefitAfter.getUpdatedAt()).isAfter(benefitBefore.getUpdatedAt());
        assertThat(stepRepository.findAllByRunIdOrderById(runId).get(0).getChangedCount()).isEqualTo(1);
    }

    @Test
    void 목록에서_빠진_혜택은_남고_마지막_확인_시각을_유지한다() {
        collectionService.collectAll();
        LocalDateTime lastSeenA = benefitRepository.findByServiceId(A).orElseThrow().getLastSeenAt();
        LocalDateTime lastSeenC = benefitRepository.findByServiceId(C).orElseThrow().getLastSeenAt();
        stub.removeService(C);

        collectionService.collectAll();

        Benefit droppedC = benefitRepository.findByServiceId(C).orElseThrow();
        assertThat(droppedC.getLastSeenAt()).isEqualTo(lastSeenC);
        assertThat(benefitDetailRepository.findById(droppedC.getId())).isPresent();
        for (SourceEndpoint endpoint : SourceEndpoint.values()) {
            assertThat(sourceRecordRepository.findByServiceIdAndEndpoint(C, endpoint)).isPresent();
        }
        assertThat(benefitRepository.findByServiceId(A).orElseThrow().getLastSeenAt()).isAfter(lastSeenA);
    }

    @Test
    void 목록에_없는_서비스ID의_상세는_원문만_저장한다() {
        stub.records(SERVICE_LIST).removeIf(record -> C.equals(record.path("서비스ID").asString(null)));

        Long runId = collectionService.collectAll();

        assertThat(benefitRepository.findByServiceId(C)).isEmpty();
        assertThat(sourceRecordRepository.findByServiceIdAndEndpoint(C, SERVICE_DETAIL)).isPresent();
        List<CollectionRunStep> steps = stepRepository.findAllByRunIdOrderById(runId);
        assertThat(steps.get(1).getUnlinkedCount()).isEqualTo(1);
        assertThat(steps.get(2).getUnlinkedCount()).isEqualTo(1);
    }

    @Test
    void data가_없는_200_응답은_그_쪽만_실패로_기록하고_나머지는_저장한다() {
        stub.override(SERVICE_LIST, 2, 200, NOT_FOUND);

        Long runId = collectionService.collectAll();

        CollectionRun run = runRepository.findById(runId).orElseThrow();
        assertThat(run.getStatus()).isEqualTo(CollectionStatus.PARTIAL);
        assertThat(run.getFinishedAt()).isNotNull();
        assertThat(run.getErrorType()).isNull();
        assertThat(failureRepository.findAllByRunIdOrderById(runId)).singleElement().satisfies(failure -> {
            assertThat(failure.getEndpoint()).isEqualTo(SERVICE_LIST);
            assertThat(failure.getPage()).isEqualTo(2);
            assertThat(failure.getErrorType()).isEqualTo(CollectionErrorType.API_BAD_RESPONSE);
            assertThat(failure.getAttempts()).isEqualTo(1);
            assertThat(failure.getMessage()).contains("/serviceList page=2", "data가 없습니다");
        });
        List<CollectionRunStep> steps = stepRepository.findAllByRunIdOrderById(runId);
        assertThat(steps).extracting(CollectionRunStep::getStatus).containsExactly(CollectionStatus.PARTIAL,
                CollectionStatus.SUCCEEDED, CollectionStatus.SUCCEEDED);
        assertThat(steps.get(0).getFinishedAt()).isNotNull();
        assertThat(steps.get(0).getPageCount()).isEqualTo(1);
        assertThat(steps.get(0).getCreatedCount()).isEqualTo(2);
        assertThat(benefitRepository.findAll()).extracting(Benefit::getServiceId).containsExactlyInAnyOrder(A, B);
        // 목록에 없는 C의 상세는 원문만 남는다.
        assertThat(steps.get(1).getUnlinkedCount()).isEqualTo(1);
    }

    @Test
    void 인증키는_헤더로_보내고_로그와_실행_기록에_남기지_않는다(CapturedOutput output) {
        collectionService.collectAll();

        assertThat(stub.authorizations()).isNotEmpty().allMatch(("Infuser " + SERVICE_KEY)::equals);

        stub.override(SERVICE_LIST, 1, 401, "{\"code\":-4,\"msg\":\"등록되지 않은 인증키 입니다.\"}");
        Long failedRunId = collectionService.collectAll();

        CollectionRun failed = runRepository.findById(failedRunId).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(CollectionStatus.FAILED);
        assertThat(failed.getErrorType()).isEqualTo(CollectionErrorType.API_AUTH);
        assertThat(failed.getErrorMessage()).contains("HTTP 401", "code=-4").doesNotContain(SERVICE_KEY);
        assertThat(output.getAll()).contains("수집 실패").doesNotContain(SERVICE_KEY);
    }

    /**
     * 수집마다 바뀌는 확인 시각(last_seen_at, collected_at)을 뺀 저장 내용.
     */
    private List<Map<String, Object>> storedContent() {
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        rows.addAll(withoutColumns(jdbcTemplate.queryForList("SELECT * FROM benefit ORDER BY service_id"),
                "last_seen_at"));
        rows.addAll(jdbcTemplate.queryForList("SELECT * FROM benefit_detail ORDER BY benefit_id"));
        rows.addAll(withoutColumns(
                jdbcTemplate.queryForList("SELECT * FROM source_record ORDER BY service_id, endpoint"),
                "collected_at"));
        return rows;
    }

    private static List<Map<String, Object>> withoutColumns(List<Map<String, Object>> rows, String column) {
        rows.forEach(row -> row.remove(column));
        return rows;
    }

    private double succeededRuns() {
        Counter counter = meterRegistry.find("collection.runs").tag("status", "SUCCEEDED").counter();
        return counter == null ? 0 : counter.count();
    }

    private double lastRunStatus(CollectionStatus status) {
        return meterRegistry.get("collection.last.run.status").tag("status", status.name()).gauge().value();
    }

    private double lastRunRecords(SourceEndpoint endpoint, String result) {
        return meterRegistry.get("collection.last.run.records")
                .tags("endpoint", endpoint.name(), "result", result).gauge().value();
    }
}
