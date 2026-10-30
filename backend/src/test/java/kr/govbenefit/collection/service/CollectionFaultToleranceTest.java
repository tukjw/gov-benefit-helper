package kr.govbenefit.collection.service;

import static kr.govbenefit.collection.domain.SourceEndpoint.SERVICE_DETAIL;
import static kr.govbenefit.collection.domain.SourceEndpoint.SERVICE_LIST;
import static kr.govbenefit.collection.domain.SourceEndpoint.SUPPORT_CONDITIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import kr.govbenefit.TestcontainersConfiguration;
import kr.govbenefit.benefit.domain.Benefit;
import kr.govbenefit.benefit.domain.DetailStatus;
import kr.govbenefit.benefit.dto.BenefitView;
import kr.govbenefit.benefit.repository.BenefitRepository;
import kr.govbenefit.benefit.service.BenefitQueryService;
import kr.govbenefit.collection.Gov24StubServer;
import kr.govbenefit.collection.domain.CollectionErrorType;
import kr.govbenefit.collection.domain.CollectionFailure;
import kr.govbenefit.collection.domain.CollectionRun;
import kr.govbenefit.collection.domain.CollectionRunStep;
import kr.govbenefit.collection.domain.CollectionStatus;
import kr.govbenefit.collection.domain.SourceEndpoint;
import kr.govbenefit.collection.repository.CollectionFailureRepository;
import kr.govbenefit.collection.repository.CollectionRunRepository;
import kr.govbenefit.collection.repository.CollectionRunStepRepository;
import kr.govbenefit.collection.repository.SourceRecordHistoryRepository;
import mockwebserver3.MockResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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

/**
 * 공공 API 장애를 로컬 stub 서버로 재현해 수집이 호출 예산 안에서 끝나고, 실패가 원인별로 기록되며, 기존 데이터가 바뀌지 않는지 검증한다.
 * 쪽 크기 2로 경로마다 2쪽(목록 [A, B] [ID 없음, C], 상세·지원조건 [A, B] [C])을 받으므로 정상 실행은 6회 호출이다.
 * 재시도는 2회(총 3회 시도), 응답 시간 제한은 300ms, 실행당 호출 예산은 10회다.
 */
@SpringBootTest(properties = {
        "gov24.per-page=2",
        "gov24.max-retries=2",
        "gov24.retry-delay=10ms",
        "gov24.read-timeout=300ms",
        "gov24.max-calls-per-run=" + CollectionFaultToleranceTest.BUDGET,
        "gov24.schedule.enabled=false"
})
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class CollectionFaultToleranceTest {

    static final int BUDGET = 10;
    private static final String SERVICE_KEY = "stub-service-key-93ab2e";
    private static final String A = "511000000138";
    private static final String B = "403000000173";
    private static final String C = "O00024000031";
    private static final MockResponse UNAVAILABLE = Gov24StubServer.response(503, "{\"code\":-1,\"msg\":\"일시 장애\"}");

    private static final Gov24StubServer stub = Gov24StubServer.start();

    @DynamicPropertySource
    static void gov24Properties(DynamicPropertyRegistry registry) {
        registry.add("gov24.base-url", stub::baseUrl);
        registry.add("gov24.service-key", () -> SERVICE_KEY);
    }

    @AfterAll
    static void closeStub() {
        stub.close();
    }

    @Autowired
    private CollectionService collectionService;
    @Autowired
    private BenefitQueryService benefitQueryService;
    @Autowired
    private BenefitRepository benefitRepository;
    @Autowired
    private SourceRecordHistoryRepository historyRepository;
    @Autowired
    private CollectionRunRepository runRepository;
    @Autowired
    private CollectionRunStepRepository stepRepository;
    @Autowired
    private CollectionFailureRepository failureRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        stub.reset();
    }

    @AfterEach
    void withinBudget() {
        // 어떤 장애에서도 실행 한 번이 예산보다 많이 호출하지 않는다.
        assertThat(stub.requestCount()).isLessThanOrEqualTo(BUDGET);
    }

    @Test
    void 응답이_지연되면_시간_제한으로_끊고_재시도_상한에서_멈춘다() {
        stub.override(SERVICE_DETAIL, 1, new MockResponse.Builder()
                .code(200)
                .headersDelay(1, TimeUnit.SECONDS)
                .body("{}")
                .build());

        Long runId = collectionService.collectAll();

        assertThat(stub.requestCount(SERVICE_DETAIL, 1)).isEqualTo(3);
        assertThat(failures(runId)).extracting(CollectionFailure::getEndpoint, CollectionFailure::getPage,
                        CollectionFailure::getErrorType, CollectionFailure::getAttempts)
                .containsExactly(tuple(SERVICE_DETAIL, 1, CollectionErrorType.API_TIMEOUT, 3));
        assertThat(run(runId).getStatus()).isEqualTo(CollectionStatus.PARTIAL);
        assertThat(stepStatuses(runId)).containsExactly(CollectionStatus.SUCCEEDED, CollectionStatus.FAILED,
                CollectionStatus.SUCCEEDED);
    }

    @Test
    void 일시_장애는_재시도해_회복한다() {
        stub.overrideTimes(SERVICE_DETAIL, 1, 2, UNAVAILABLE);

        Long runId = collectionService.collectAll();

        assertThat(stub.requestCount(SERVICE_DETAIL, 1)).isEqualTo(3);
        assertThat(run(runId).getStatus()).isEqualTo(CollectionStatus.SUCCEEDED);
        assertThat(failures(runId)).isEmpty();
        assertThat(benefitQueryService.findByServiceId(A)).map(BenefitView::detailStatus)
                .contains(DetailStatus.COLLECTED);
    }

    @Test
    void 일시_장애가_계속되면_재시도_상한에서_멈추고_부분_실패로_기록한다() {
        stub.override(SERVICE_DETAIL, 2, UNAVAILABLE);

        Long runId = collectionService.collectAll();

        assertThat(stub.requestCount(SERVICE_DETAIL, 2)).isEqualTo(3);
        assertThat(failures(runId)).extracting(CollectionFailure::getEndpoint, CollectionFailure::getPage,
                        CollectionFailure::getErrorType, CollectionFailure::getAttempts)
                .containsExactly(tuple(SERVICE_DETAIL, 2, CollectionErrorType.API_UNAVAILABLE, 3));
        CollectionRun run = run(runId);
        assertThat(run.getStatus()).isEqualTo(CollectionStatus.PARTIAL);
        assertThat(run.getErrorType()).isNull();
        assertThat(stepStatuses(runId)).containsExactly(CollectionStatus.SUCCEEDED, CollectionStatus.PARTIAL,
                CollectionStatus.SUCCEEDED);
    }

    @Test
    void 호출_제한은_재시도하지_않고_실행을_중단한다() {
        stub.override(SERVICE_DETAIL, 1, 429, "{\"code\":-429,\"msg\":\"호출 한도 초과\"}");

        Long runId = collectionService.collectAll();

        assertThat(stub.requestCount(SERVICE_DETAIL, 1)).isEqualTo(1);
        assertThat(stub.requestCount(SUPPORT_CONDITIONS)).isZero();
        CollectionRun run = run(runId);
        assertThat(run.getStatus()).isEqualTo(CollectionStatus.PARTIAL);
        assertThat(run.getErrorType()).isEqualTo(CollectionErrorType.API_RATE_LIMIT);
        assertThat(run.getFinishedAt()).isNotNull();
        assertThat(stepStatuses(runId)).containsExactly(CollectionStatus.SUCCEEDED, CollectionStatus.FAILED);
        assertThat(failures(runId)).extracting(CollectionFailure::getErrorType, CollectionFailure::getAttempts)
                .containsExactly(tuple(CollectionErrorType.API_RATE_LIMIT, 1));
    }

    @Test
    void 인증_실패는_재시도하지_않고_실행을_중단한다() {
        stub.override(SERVICE_DETAIL, 1, 401, "{\"code\":-4,\"msg\":\"등록되지 않은 인증키 입니다.\"}");

        Long runId = collectionService.collectAll();

        assertThat(stub.requestCount(SERVICE_DETAIL, 1)).isEqualTo(1);
        assertThat(stub.requestCount(SUPPORT_CONDITIONS)).isZero();
        CollectionRun run = run(runId);
        assertThat(run.getStatus()).isEqualTo(CollectionStatus.PARTIAL);
        assertThat(run.getErrorType()).isEqualTo(CollectionErrorType.API_AUTH);
        assertThat(run.getErrorMessage()).contains("HTTP 401", "code=-4").doesNotContain(SERVICE_KEY);
    }

    @Test
    void 목록_1쪽에서_인증에_실패하면_한_번만_요청하고_실행을_실패로_끝낸다() {
        stub.override(SERVICE_LIST, 1, 401, "{\"code\":-4,\"msg\":\"등록되지 않은 인증키 입니다.\"}");

        Long runId = collectionService.collectAll();

        assertThat(stub.requestCount()).isEqualTo(1);
        CollectionRun run = run(runId);
        assertThat(run.getStatus()).isEqualTo(CollectionStatus.FAILED);
        assertThat(run.getErrorType()).isEqualTo(CollectionErrorType.API_AUTH);
        assertThat(stepStatuses(runId)).containsExactly(CollectionStatus.FAILED);
    }

    @Test
    void 호출_예산을_넘기게_되면_수집을_중단하고_실행에_남긴다() {
        // 목록 1 + 3, 상세 1 + 3, 지원조건 1 + 1 = 10회 뒤, 지원조건 2쪽의 재시도가 예산을 넘긴다.
        stub.override(SERVICE_LIST, 2, UNAVAILABLE);
        stub.override(SERVICE_DETAIL, 2, UNAVAILABLE);
        stub.override(SUPPORT_CONDITIONS, 2, UNAVAILABLE);

        Long runId = collectionService.collectAll();

        assertThat(stub.requestCount()).isEqualTo(BUDGET);
        CollectionRun run = run(runId);
        assertThat(run.getStatus()).isEqualTo(CollectionStatus.PARTIAL);
        assertThat(run.getErrorType()).isEqualTo(CollectionErrorType.BUDGET_EXCEEDED);
        assertThat(run.getErrorMessage()).contains(BUDGET + "회");
        assertThat(failures(runId)).extracting(CollectionFailure::getEndpoint, CollectionFailure::getPage,
                        CollectionFailure::getErrorType, CollectionFailure::getAttempts)
                .containsExactly(
                        tuple(SERVICE_LIST, 2, CollectionErrorType.API_UNAVAILABLE, 3),
                        tuple(SERVICE_DETAIL, 2, CollectionErrorType.API_UNAVAILABLE, 3),
                        tuple(SUPPORT_CONDITIONS, 2, CollectionErrorType.BUDGET_EXCEEDED, 1));
        assertThat(stepStatuses(runId)).containsExactly(CollectionStatus.PARTIAL, CollectionStatus.PARTIAL,
                CollectionStatus.PARTIAL);
    }

    @Test
    void 목록_1쪽을_받지_못하면_실행을_실패로_끝내고_다음_경로를_받지_않는다() {
        stub.override(SERVICE_LIST, 1, UNAVAILABLE);

        Long runId = collectionService.collectAll();

        assertThat(stub.requestCount()).isEqualTo(3);
        CollectionRun run = run(runId);
        assertThat(run.getStatus()).isEqualTo(CollectionStatus.FAILED);
        assertThat(run.getErrorType()).isEqualTo(CollectionErrorType.API_UNAVAILABLE);
        assertThat(run.getErrorMessage()).contains("/serviceList page=1", "HTTP 503");
        assertThat(stepStatuses(runId)).containsExactly(CollectionStatus.FAILED);
        assertThat(meterRegistry.get("collection.last.run.status").tag("status", "FAILED").gauge().value())
                .isEqualTo(1);
        // 시작하지 못한 경로의 처리 건수는 0이다.
        assertThat(meterRegistry.get("collection.last.run.records")
                .tags("endpoint", SERVICE_DETAIL.name(), "result", "received").gauge().value()).isZero();
    }

    @Test
    void 마지막_쪽_전에_data가_비면_부분_실패로_기록한다() {
        stub.override(SERVICE_LIST, 2, 200,
                "{\"currentCount\":0,\"data\":[],\"matchCount\":4,\"page\":2,\"perPage\":2,\"totalCount\":4}");

        Long runId = collectionService.collectAll();

        assertThat(failures(runId)).extracting(CollectionFailure::getEndpoint, CollectionFailure::getPage,
                        CollectionFailure::getErrorType)
                .containsExactly(tuple(SERVICE_LIST, 2, CollectionErrorType.API_TRUNCATED));
        assertThat(run(runId).getStatus()).isEqualTo(CollectionStatus.PARTIAL);
        assertThat(stepStatuses(runId)).containsExactly(CollectionStatus.PARTIAL, CollectionStatus.SUCCEEDED,
                CollectionStatus.SUCCEEDED);
    }

    @Test
    void 부분_실패한_실행_뒤에도_이전에_저장한_값은_바뀌지_않는다() {
        collectionService.collectAll();
        Long benefitIdA = benefitRepository.findByServiceId(A).orElseThrow().getId();
        Map<String, Object> detailA = row("SELECT * FROM benefit_detail WHERE benefit_id = ?", benefitIdA);
        Map<String, Object> benefitC = row("SELECT * FROM benefit WHERE service_id = ?", C);
        Map<String, Object> sourceDetailA = sourceRecord(A, SERVICE_DETAIL);
        Map<String, Object> sourceListC = sourceRecord(C, SERVICE_LIST);
        // 예산 확인이 두 번째 실행의 요청만 세도록 요청 수를 지운다. 레코드는 첫 실행과 같은 fixture로 돌아간다.
        stub.reset();
        // 원천 내용이 바뀌었지만 그 쪽 요청이 실패한다.
        stub.record(SERVICE_DETAIL, A).put("구비서류", "변경된 구비서류");
        stub.record(SERVICE_LIST, C).put("지원내용", "변경된 지원내용");
        stub.override(SERVICE_DETAIL, 1, UNAVAILABLE);
        stub.override(SERVICE_LIST, 2, UNAVAILABLE);

        Long runId = collectionService.collectAll();

        assertThat(run(runId).getStatus()).isEqualTo(CollectionStatus.PARTIAL);
        assertThat(row("SELECT * FROM benefit_detail WHERE benefit_id = ?", benefitIdA)).isEqualTo(detailA);
        assertThat(row("SELECT * FROM benefit WHERE service_id = ?", C)).isEqualTo(benefitC);
        assertThat(sourceRecord(A, SERVICE_DETAIL)).isEqualTo(sourceDetailA);
        assertThat(sourceRecord(C, SERVICE_LIST)).isEqualTo(sourceListC);
        assertThat(historyRepository.count()).isZero();
    }

    @Test
    void 상세가_없는_신규_혜택은_정보_미수집으로_조회된다() {
        stub.override(SERVICE_DETAIL, 2, UNAVAILABLE);

        collectionService.collectAll();

        assertThat(benefitQueryService.findByServiceId(C)).map(BenefitView::detailStatus)
                .contains(DetailStatus.NOT_COLLECTED);
        assertThat(benefitQueryService.findByServiceId(A)).map(BenefitView::detailStatus)
                .contains(DetailStatus.COLLECTED);
        assertThat(benefitQueryService.findByServiceId(B)).map(BenefitView::detailStatus)
                .contains(DetailStatus.COLLECTED);
    }

    @Test
    void 공공_API_오류와_DB_오류를_로그와_실행_기록에서_구분한다(CapturedOutput output) {
        // 서비스명 컬럼(VARCHAR(255))보다 긴 값이라 목록 2쪽 저장이 DB 오류로 실패한다.
        String tooLongName = "가".repeat(300);
        stub.record(SERVICE_LIST, C).put("서비스명", tooLongName);
        stub.override(SERVICE_DETAIL, 2, UNAVAILABLE);

        Long runId = collectionService.collectAll();

        assertThat(failures(runId)).extracting(CollectionFailure::getEndpoint, CollectionFailure::getPage,
                        CollectionFailure::getErrorType, CollectionFailure::getAttempts)
                .containsExactly(
                        tuple(SERVICE_LIST, 2, CollectionErrorType.DB_ERROR, 1),
                        tuple(SERVICE_DETAIL, 2, CollectionErrorType.API_UNAVAILABLE, 3));
        assertThat(failures(runId).get(0).getMessage()).contains("저장 실패").doesNotContain(tooLongName);
        assertThat(run(runId).getStatus()).isEqualTo(CollectionStatus.PARTIAL);
        assertThat(benefitRepository.findAll()).extracting(Benefit::getServiceId).containsExactlyInAnyOrder(A, B);
        assertThat(output.getAll()).contains("errorType=DB_ERROR", "errorType=API_UNAVAILABLE")
                .doesNotContain(SERVICE_KEY);
    }

    private CollectionRun run(Long runId) {
        return runRepository.findById(runId).orElseThrow();
    }

    private List<CollectionFailure> failures(Long runId) {
        return failureRepository.findAllByRunIdOrderById(runId);
    }

    private List<CollectionStatus> stepStatuses(Long runId) {
        return stepRepository.findAllByRunIdOrderById(runId).stream().map(CollectionRunStep::getStatus).toList();
    }

    private Map<String, Object> row(String sql, Object arg) {
        return jdbcTemplate.queryForMap(sql, arg);
    }

    private Map<String, Object> sourceRecord(String serviceId, SourceEndpoint endpoint) {
        return jdbcTemplate.queryForMap("SELECT * FROM source_record WHERE service_id = ? AND endpoint = ?",
                serviceId, endpoint.name());
    }
}
