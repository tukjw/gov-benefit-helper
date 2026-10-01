status: done

# 구현 계획 — #16 혜택 수집의 외부 장애 대응과 하루 1회 정기 실행

브랜치: `feat/16-collection-fault-tolerance` (develop `9e79c99` 기준)

## 목표 및 범위

- 목표: 공공 API의 지연·일시 장애·호출 제한·인증 실패에서 수집이 호출 예산 안에서 끝나고, 실패한 요청이 기존 정상 데이터를 바꾸지 않으며, 그 결과가 실행 기록에 원인별로 남는다. 이 상태로 하루 1회 자동 수집을 켠다.
- 포함:
  - `Gov24Client`에 연결·응답 시간 제한, 실패 유형 분류, 재시도 상한, 실행당 호출 예산.
  - 실행 상태 `PARTIAL` 추가, 실행 중단·실패 원인(`collection_run.error_type`), 쪽 단위 실패 기록(`collection_failure`).
  - 실패한 쪽은 건너뛰고 다음 쪽·경로를 계속 수집. 계산한 마지막 쪽 전의 `data: []`를 실패로 기록.
  - 혜택의 상세 수집 상태(`COLLECTED`/`NOT_COLLECTED`) 조회.
  - 앱 내 스케줄러로 하루 1회 전체 수집. 수집을 실행하는 HTTP 경로는 만들지 않는다.
  - 결정 문서 0006(API 실패 처리), 0007(실행 상태와 실패 기록, 0004 대체).
- 제외:
  - 실패 구간만 재처리하는 기능(`REQ-DATA-01` P1). 이번에는 재처리 입력이 될 `collection_failure`만 남긴다.
  - 수집 실행이 겹칠 때의 처리(`REQ-QUALITY-01` P1). 단일 인스턴스를 전제한다. 중단된 실행이 `RUNNING`으로 남는 경우의 정리도 여기에 속한다.
  - 정상 응답의 빈 값 처리(정규화 이슈). 정상 응답이 값을 비우면 그대로 반영한다.
  - 수집 이력 조회·수동 실행 API(`DEC-AUTH` 미정), 연속 실패 경보(O3).
  - 서킷 브레이커, 경로별 예산, 시작 전 예산 사전 계산.

## 확인된 사실과 제약

- 전체 수집은 경로당 11쪽, 33회 호출·29초이고 쪽 응답은 최대 1.1초였다. 개발계정 트래픽은 10,000이며 기간 단위(일·월)는 확인하지 않았다([데이터 검증 보고서 §1](../research/gov24-api-data.md#1-인증과-페이지-조회), [§9](../research/gov24-api-data.md#9-결정별-선택지와-근거)).
- 관찰한 실패 응답: 인증 실패는 401 `{"code":-401|-4,"msg":...}`, `perPage`가 너무 크면 200 `{"code":0,"msg":"정상"}`(`data` 없음), 마지막 쪽 뒤는 200 `data: []`. 호출 제한 초과·시간 초과·5xx·403은 관찰하지 않았다([§2](../research/gov24-api-data.md#2-실패-응답)). 호출 제한은 HTTP 429로 온다고 가정하고 판정을 한곳에 모은다.
- 현재 구조(#15): 쪽 하나가 트랜잭션 하나이고, 요청이 하나라도 실패하면 이후 경로를 받지 않고 실행·경로를 `FAILED`로 끝낸다. 실패한 쪽은 저장되지 않으므로 기존 행은 바뀌지 않는다. 중간 쪽의 `data: []`는 끝으로 보고 `SUCCEEDED`가 된다.
- 목록에서 빠진 혜택은 삭제하지 않고 `last_seen_at`만 이전 값으로 남는다. 목록에 없는 서비스ID의 상세는 원문만 저장하고 `unlinked_count`로 센다([0003](../decisions/0003-source-record-retention.md), [#15 플랜](15-policy-collection.md)).
- Spring Framework 7.0.8의 `spring-core`에 `org.springframework.core.retry.RetryTemplate`·`RetryPolicy`(최대 재시도, 지연, 배수, 예외 판정 `predicate`)가 있다. 추가 의존성이 필요 없다. `RetryTemplate.execute`는 실패 시 `RetryException`(마지막 예외·재시도 횟수 포함)을 던진다.
- `RestClient.builder()`의 기본 요청 팩토리는 이 클래스패스에서 `JdkClientHttpRequestFactory`다. `setReadTimeout(Duration)`은 JDK `HttpRequest.timeout`으로 응답 헤더를 받을 때까지만 적용되고, 연결 시간 제한은 `HttpClient.Builder.connectTimeout`으로 건다. 시간 초과는 `ResourceAccessException`의 원인 `java.net.http.HttpTimeoutException`(연결 시간 초과는 하위 타입 `HttpConnectTimeoutException`)으로 나온다.
- MockWebServer 5.5.0 `MockResponse.Builder`에 `headersDelay`, `bodyDelay`, `throttleBody`가 있다. 응답 지연은 `headersDelay`로 재현한다.
- 테스트 fixture 순서: 목록 `A, B, (서비스ID null), C`, 상세 `A, B, C`, 지원조건 `A, B, C`. `per-page=2`에서 목록 2쪽은 `[null, C]`, 상세·지원조건 2쪽은 `[C]`다.
- `CollectionStatus`·`error_type`은 `VARCHAR` 컬럼이라 값 추가에 컬럼 변경이 필요 없다. 새 시각 컬럼은 [0002](../decisions/0002-db-timezone.md)를 따른다.
- 채택 결정 [0004](../decisions/0004-collection-run-status.md)는 상태를 `RUNNING`/`SUCCEEDED`/`FAILED`로 두고 "실패 상태는 요청 실패에만 쓴다"고 정했다. 이번에 쪽 저장 실패(DB 오류)도 실패로 기록하므로 0004를 대체한다. [0005](../decisions/0005-gov24-client-and-stub.md)는 "시간 제한·재시도·호출 예산은 `Gov24Client`에 더한다"고 예고했으며 이번 변경과 충돌하지 않는다.

## 설계 결정

1. **실패 유형을 9가지로 나누고, 유형마다 재시도와 중단 여부를 정한다.** 결정 문서 0006.

   | `error_type` | 판정 | 재시도 | 처리 |
   | --- | --- | --- | --- |
   | `API_TIMEOUT` | 연결·응답 시간 제한 초과 | 예 | 상한까지 재시도, 소진하면 그 쪽 실패 |
   | `API_UNAVAILABLE` | 연결 실패, 5xx | 예 | 같음 |
   | `API_BAD_RESPONSE` | 200인데 `data` 없음, 본문 해석 불가, 401·403·429 외 4xx | 아니오 | 그 쪽 실패 |
   | `API_TRUNCATED` | 계산한 마지막 쪽 전에 `data: []` | 아니오 | 그 경로 순회를 끝내고 그 쪽을 실패로 기록 |
   | `API_AUTH` | 401, 403 | 아니오 | 실행 중단 |
   | `API_RATE_LIMIT` | 429 | 아니오 | 실행 중단 |
   | `BUDGET_EXCEEDED` | 다음 시도가 실행 호출 예산을 넘김 | - | 실행 중단 |
   | `DB_ERROR` | 쪽 저장 중 `DataAccessException`·`TransactionException`·`PersistenceException` | 아니오 | 그 쪽 실패 |
   | `INTERNAL` | 위에 해당하지 않는 예외 | 아니오 | 실행 `FAILED` |

   - 인증 실패는 이후 요청도 모두 실패하므로 중단한다.
   - 호출 제한은 대기 후 재시도하지 않고 즉시 중단한다(사용자 확인 2026-10-01). 개발계정 한도가 일·월 단위라면 기다려도 풀리지 않고, `Retry-After`를 보내는지 관찰한 적이 없다. 하루 1회 실행이라 다음 날 다시 돈다. 실제 응답이 관찰되면 판정 함수(`Gov24ErrorClassifier`) 한 곳만 바꾼다.
   - `DB_ERROR`는 그 쪽만 건너뛴다. 레코드 하나의 값이 컬럼보다 길어 나는 오류처럼 데이터에 따라 나는 DB 오류로 실행 전체를 매일 멈추면, 그 쪽 뒤의 모든 쪽이 계속 갱신되지 않는다. DB 자체가 내려간 경우에는 실패 기록 저장도 실패하므로, 그때는 로그를 남기고 실행을 끝낸다. 다시 받는 API 호출은 하지 않는다.
   - `API_` 접두어로 공공 API 오류와 DB 오류를 구분한다. 로그에는 `errorType=<값>`을 남긴다.
2. **재시도는 Spring 7 내장 `RetryTemplate`으로 `Gov24Client.fetchPage` 안에서 한다.** 대안: 직접 반복문(백오프·예외 분기를 직접 관리), Resilience4j(Boot 4 호환 미검증, 하루 1회 배치에 서킷 브레이커는 과함), spring-retry(유지보수 모드, 내장 기능이 대체). `RetryPolicy`는 `predicate`로 재시도 가능한 유형만 고르고, 최대 재시도 2회(총 3회 시도), 지연 1초·배수 2(1초, 2초 대기)를 기본값으로 둔다. 테스트에서는 지연을 10ms로 줄인다.
3. **시간 제한은 연결 5초, 응답(헤더까지) 10초를 기본값으로 `JdkClientHttpRequestFactory`에 건다.** 실측 최대 1.1초의 약 10배다. 본문이 아주 천천히 오는 경우는 끊지 못한다. 이를 막으려면 호출 전체 시간 상한을 따로 둬야 하므로 이번에는 두지 않는다(완료 조건의 응답 지연은 헤더 지연으로 재현한다).
4. **호출 예산은 실행마다 `Gov24CallBudget`을 만들고, 재시도를 포함한 실제 HTTP 시도마다 하나씩 차감한다.** 시도 전에 확인해 다음 시도가 예산을 넘기면 `BUDGET_EXCEEDED`로 중단한다. 기본값은 정상 33회의 약 3배인 100회다. 대안: 시작 전 `totalCount`로 필요 호출을 계산(경로마다 첫 쪽을 받아야 알 수 있어 로직이 늘어남), 경로별 예산(설정이 세 배). 예산 객체는 실행 하나가 한 스레드에서만 쓴다.
5. **실패한 쪽은 건너뛰고 다음 쪽을 계속 받는다.** 대안: 실패 시 경로 중단(1쪽 실패로 나머지 쪽을 버림), 연속 N쪽 실패 시 경로 중단(설정 추가). 장애가 계속되면 예산이 상한이 된다. 경로의 1쪽이 실패하면 마지막 쪽을 알 수 없으므로 그 경로는 거기서 끝낸다.
6. **실행·경로 상태 규칙.** 결정 문서 0007.
   - 실행: 목록 1쪽을 받아 저장하지 못하면 `FAILED`이고 이후 경로를 받지 않는다(사용자 확인 2026-10-01). 그 밖에 실패한 쪽이 하나라도 있거나 실행이 중단되면 `PARTIAL`, 없으면 `SUCCEEDED`다. `INTERNAL`은 `FAILED`다. 목록 중간 쪽 실패를 `PARTIAL`로 두는 이유: 빠진 쪽의 기존 혜택은 지워지지 않고 `last_seen_at`만 이전 값으로 남으며, 그 쪽의 신규 혜택 상세는 미연결 원문으로 남았다가 다음 실행에서 연결된다. 실행 상태는 "이번 실행으로 갱신이 됐는가"를 기준으로 나눈다.
   - 경로: 1쪽을 저장하지 못하면 `FAILED`, 1쪽 이후 실패한 쪽이 있거나 도중에 실행이 중단되면 `PARTIAL`, 없으면 `SUCCEEDED`. 중단 뒤의 경로는 경로 행을 만들지 않는다(#15와 같음).
7. **실패 원인은 두 곳에 나눠 남긴다.** 결정 문서 0007. `collection_run.error_type`·`error_message`에는 실행을 `FAILED`로 만들거나 중단시킨 원인만 둔다. 끝까지 돈 `PARTIAL`은 둘 다 `NULL`이다. 쪽 단위 실패는 모두 `collection_failure`(경로, 쪽, 유형, 시도 횟수, 메시지)에 남긴다(사용자 확인 2026-10-01). 실패한 쪽이 여럿이고 원인이 다를 수 있으며, 실패 구간 재처리(P1)가 이 테이블을 입력으로 쓴다. 경로 행에는 원인 컬럼을 두지 않는다(실패 테이블과 중복). 실패 기록은 쪽 트랜잭션과 별도로 커밋한다.
8. **기존 데이터 보존은 쪽 트랜잭션으로만 보장하고 필드 단위 보호는 하지 않는다.** 실패한 요청·저장은 그 쪽 전체가 쓰이지 않는다. 정상 응답에서 빈 값이 오면 기존 값을 지키는 방식은 원천이 실제로 값을 지운 경우를 숨기고 정규화 이슈와 겹친다.
9. **상세 수집 상태는 저장하지 않고 `benefit_detail` 행 존재로 파생한다.** 대안 `benefit.detail_status` 컬럼은 인덱스로 거를 수 있지만 상세 저장과 따로 갱신해야 해서 어긋날 수 있다. 검색 API에서 이 조건으로 걸러야 하면 그때 다시 본다. 상세를 한 번 받은 혜택은 이후 상세 요청이 실패해도 이전 상세를 유지하며 `COLLECTED`다.
10. **정기 실행은 앱 내 `@Scheduled`로 한다.** cron 기본값 매일 04:00(`Asia/Seoul`). 대안: 외부 cron·k8s CronJob(배포 인프라 미정이라 지금 검증할 수 없음), Quartz·Spring Batch(현재 규모에 과함). 앱이 그 시각에 꺼져 있으면 그날 실행은 건너뛴다. `gov24.schedule.enabled`로 켜고 끄며 기본값은 `true`, `local` 프로필과 테스트는 `false`다. 인증키가 없으면 실행을 만들지 않고 경고 로그만 남긴다. 여러 인스턴스로 늘리면 ShedLock 같은 실행 잠금이 필요하다(겹침 처리 P1).
11. **수집 실행 권한은 HTTP 경로를 만들지 않는 것으로 막는다.** 트리거는 스케줄러와 `collect` 프로필 실행기뿐이다. 관리자 경로는 `DEC-AUTH`가 정해진 뒤에 둔다. 테스트는 등록된 HTTP 핸들러 중 `kr.govbenefit.collection` 패키지 소속이 없는지 확인한다.

## 스키마

`backend/src/main/resources/db/migration/V2__add_collection_failure.sql`.

```sql
-- 실행을 FAILED로 만들거나 중단시킨 원인. 끝까지 돈 실행은 NULL
ALTER TABLE collection_run ADD COLUMN error_type VARCHAR(30) NULL AFTER status;

-- 수집 실행에서 실패한 쪽. error_type은 결정 0006의 값
CREATE TABLE collection_failure (
    id         BIGINT        NOT NULL AUTO_INCREMENT,
    run_id     BIGINT        NOT NULL,
    endpoint   VARCHAR(30)   NOT NULL,
    page       INT           NOT NULL,
    error_type VARCHAR(30)   NOT NULL,
    attempts   INT           NOT NULL,
    message    VARCHAR(1000) NULL,
    created_at DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_collection_failure_run FOREIGN KEY (run_id) REFERENCES collection_run (id)
);
```

`attempts`는 그 쪽에 보낸 HTTP 시도 횟수다(`DB_ERROR`는 응답을 받기까지의 시도 횟수). `message`에는 경로·쪽·상태 코드·응답의 `code`·`msg` 또는 예외 클래스 이름만 담고 인증키·요청 헤더는 담지 않는다. 상태 값 `PARTIAL`은 `VARCHAR` 컬럼이라 마이그레이션이 필요 없고, V1의 상태 주석은 고치지 않는다(적용된 마이그레이션은 바꾸지 않는다).

## 단계별 구현 계획

1. **설정**
   - `Gov24Properties`에 `Duration connectTimeout`, `Duration readTimeout`, `int maxRetries`, `Duration retryDelay`, `int maxCallsPerRun`을 추가한다. `toString`은 새 항목을 포함하고 키는 계속 가린다.
   - `application.yaml`: `gov24.connect-timeout: 5s`, `read-timeout: 10s`, `max-retries: 2`, `retry-delay: 1s`, `max-calls-per-run: 100`, `gov24.schedule.enabled: true`, `gov24.schedule.cron: "0 0 4 * * *"`.
   - `application-local.yaml`: `gov24.schedule.enabled: false`.
2. **마이그레이션**: `V2__add_collection_failure.sql`(위 스키마).
3. **실패 유형·기록 도메인** `kr.govbenefit.collection.domain`
   - `CollectionErrorType`(enum, 설계 1의 9개 값, `retryable()`, `abortsRun()`).
   - `CollectionStatus`에 `PARTIAL` 추가.
   - `CollectionRun`: `errorType` 필드, `succeed`·`fail`을 `finish(CollectionStatus, CollectionErrorType, String message, LocalDateTime)`로 바꾼다.
   - `CollectionRunStep`: `partial(now)` 추가.
   - `CollectionFailure`(엔티티, `of(runId, endpoint, page, errorType, attempts, message, now)`), `repository.CollectionFailureRepository`(`findAllByRunIdOrderById`).
4. **API 클라이언트** `kr.govbenefit.collection.client`
   - `Gov24ApiException`에 `CollectionErrorType type`과 `int attempts`를 더한다.
   - `Gov24ErrorClassifier`(정적 메서드): 상태 코드·응답 본문·`ResourceAccessException` 원인으로 유형을 정한다. 401·403 → `API_AUTH`, 429 → `API_RATE_LIMIT`, 5xx → `API_UNAVAILABLE`, 기타 4xx·`data` 없음·해석 불가 → `API_BAD_RESPONSE`, `HttpTimeoutException` 원인 → `API_TIMEOUT`, 그 밖의 연결 실패 → `API_UNAVAILABLE`.
   - `Gov24CallBudget`(실행당 하나, `acquire()`가 남은 예산이 없으면 `Gov24ApiException(BUDGET_EXCEEDED)`), `used()`.
   - `Gov24Client`: 생성자에서 `HttpClient.newBuilder().connectTimeout(...)`과 `JdkClientHttpRequestFactory.setReadTimeout(...)`으로 `RestClient`를 만들고, `RetryPolicy.builder().maxRetries(...).delay(...).multiplier(2).predicate(재시도 가능 유형)`로 `RetryTemplate`을 만든다. `fetchPage(endpoint, page, perPage, budget)`은 시도마다 `budget.acquire()` 후 요청하고, 실패한 시도는 `errorType`·시도 번호와 함께 WARN 로그를 남긴다. `RetryException`에서 마지막 `Gov24ApiException`을 꺼내 시도 횟수를 붙여 다시 던진다.
5. **수집 서비스** `kr.govbenefit.collection.service.CollectionService`
   - `collectAll()`: 실행 시작 → `Gov24CallBudget` 생성 → 경로 순회. 각 경로 결과(성공/부분/실패/중단)를 모아 설계 6의 규칙으로 실행 상태를 정한다. 반환값은 실행 ID.
   - `collectEndpoint(...)`: 쪽마다 `fetchPage` → `pageWriter.write`. `Gov24ApiException`(`abortsRun` 아님)·DB 예외는 `recordFailure`로 기록하고 다음 쪽으로 간다. 1쪽 실패는 경로 `FAILED`. 계산한 마지막 쪽 전의 `data: []`는 `API_TRUNCATED`로 기록하고 순회를 끝낸다. `abortsRun` 유형은 기록 후 실행 중단 신호로 올린다.
   - `recordFailure(...)`: `collection_failure` 저장과 `log.warn("수집 쪽 실패 runId={} endpoint={} page={} errorType={} attempts={}", ...)`. 저장 자체가 DB 오류로 실패하면 `errorType=DB_ERROR` 로그를 남기고 실행을 끝낸다(실행 행 갱신도 시도하고, 실패하면 로그만 남는다).
6. **상세 수집 상태 조회** `kr.govbenefit.benefit`
   - `domain.DetailStatus`(enum `COLLECTED`, `NOT_COLLECTED`).
   - `dto.BenefitView(String serviceId, String name, DetailStatus detailStatus)`.
   - `service.BenefitQueryService.findByServiceId(String)` → `Optional<BenefitView>`. `benefitDetailRepository.existsById(benefit.getId())`로 상태를 정한다(`@Transactional(readOnly = true)`).
7. **정기 실행** `kr.govbenefit.collection`
   - `config.CollectionConfig`에 `@EnableScheduling`.
   - `service.CollectionScheduler`(`@ConditionalOnBooleanProperty("gov24.schedule.enabled")`): `@Scheduled(cron = "${gov24.schedule.cron}", zone = "Asia/Seoul") collectDaily()`가 `collectAll()`을 호출한다. 인증키가 없어 `IllegalStateException`이 나면 경고 로그만 남긴다.
8. **테스트 stub 확장** `Gov24StubServer`
   - `override(endpoint, page, MockResponse)` 오버로드(지연 응답용), `overrideTimes(endpoint, page, times, MockResponse)`(정해진 횟수만 바꿔 끼우고 이후 정상 응답), `requestCount()`·`requestCount(endpoint, page)`.
   - `reset()`이 위 상태도 지운다.
9. **테스트**
   - `CollectionServiceTest`: 속성에 `gov24.schedule.enabled=false`, `gov24.retry-delay=10ms`를 더한다. `data가_없는_200_응답은_실패로_기록하고_이전_쪽은_남긴다`를 목록 2쪽 `API_BAD_RESPONSE` → 실행 `PARTIAL`, 상세·지원조건까지 수집되는 기대값으로 바꾼다. `setUp`의 삭제 대상에 `collection_failure`를 더한다.
   - `CollectionFaultToleranceTest`(새 클래스, `@SpringBootTest`, Testcontainers, 별도 stub): `per-page=2`, `max-retries=2`, `retry-delay=10ms`, `read-timeout=300ms`, `max-calls-per-run=10`, `schedule.enabled=false`. 정상 실행은 6회 호출이고, 예산 초과 시나리오를 빼면 가장 많이 호출하는 경우(목록 2쪽·상세 1쪽 실패)가 9회다. 시나리오는 완료 조건 매핑에 적는다.
   - `CollectionSchedulerTest`: `schedule.enabled=true`에서 `ScheduledTaskHolder`에 cron `0 0 4 * * *` 작업이 등록되고, `collectDaily()`가 stub으로 실행을 하나 만든다. 인증키가 비어 있으면 실행 행을 만들지 않는다. `schedule.enabled=false`면 `CollectionScheduler` 빈이 없다.
   - `CollectionAccessTest`: `RequestMappingHandlerMapping.getHandlerMethods()`에 `kr.govbenefit.collection` 패키지 소속 핸들러가 없다.
   - `GovBenefitHelperApplicationTests`: `gov24.schedule.enabled=false`.
   - `Gov24PropertiesTest`: 생성자 인자 변경 반영, `toString`에 키가 없는지 유지.
   - `Gov24ErrorClassifierTest`(단위): 상태 코드·예외 원인별 유형.
10. **결정 문서**
    - `docs/decisions/0006-gov24-failure-handling.md`: 실패 유형 9가지와 재시도·중단 규칙, 시간 제한, 재시도 상한·백오프, 실행당 호출 예산(시도마다 차감), 호출 제한 판정을 한곳에 모은다는 것. 0005를 보완한다.
    - `docs/decisions/0007-collection-run-status-and-failures.md`: 0004를 대체한다. 0004의 유효한 내용(실행·경로 기록, 격리, 쪽 단위 커밋, DB 조회, 비공개)을 옮기고, `PARTIAL`, 실행·경로 상태 규칙(설계 6), `error_type`과 `collection_failure`(설계 7), 쪽 저장 실패도 실패로 기록한다는 것을 더한다.
    - `0004-collection-run-status.md`의 상태 줄을 `대체됨 → [0007](0007-collection-run-status-and-failures.md)`로 바꾸고 `docs/decisions/README.md` 목록 표를 고친다.

## 완료 조건 매핑

시나리오는 모두 `CollectionFaultToleranceTest`이며, 각 시나리오에서 stub이 받은 요청 수가 예산 10 이하인지 함께 확인한다(`@AfterEach`).

- [ ] 다음 상황을 로컬 stub 서버로 재현하고, 각각 호출 예산 안에서 종료된다.
  - 응답 지연: 시간 제한으로 끊긴다. → 설계 3, 4·9단계. 상세 1쪽에 `headersDelay` 1초(시간 제한 300ms). 그 쪽 요청 3회, `collection_failure`에 `API_TIMEOUT`·`attempts=3`, 실행 `PARTIAL`.
  - 일시 장애: 재시도 상한까지만 다시 시도한다. → 설계 2, 4·9단계. 상세 1쪽 503 두 번 후 정상: 그 쪽 요청 3회, 실행 `SUCCEEDED`, 실패 0건. 상세 1쪽 계속 503: 요청 3회에서 멈추고 `API_UNAVAILABLE`·`attempts=3`.
  - 호출 제한·인증 실패: 무한히 재시도하지 않는다. → 설계 1, 4·5·9단계. 상세 1쪽 429: 그 쪽 요청 1회, 지원조건 요청 0회, 실행 `PARTIAL`·`error_type=API_RATE_LIMIT`. 상세 1쪽 401: 같은 방식으로 `API_AUTH`. 목록 1쪽 401: 전체 요청 1회, 실행 `FAILED`·`API_AUTH`.
- [ ] 호출 예산을 넘기게 되면 수집이 중단되고, 실행 상태에 그 사실이 남는다. → 설계 4·6·7, 4·5·9단계. 세 경로의 2쪽 503: 목록 1+3, 상세 1+3, 지원조건 1+1 = 10회 뒤 지원조건 2쪽의 재시도가 예산을 넘겨 중단. stub 요청 10회, 실행 `PARTIAL`·`error_type=BUDGET_EXCEEDED`, `error_message`에 예산 값, 지원조건 2쪽 실패는 `BUDGET_EXCEEDED`(시도 1회). 경로 1쪽이 실패하면 그 경로를 끝내므로 2쪽 실패로 예산을 소진시킨다.
- [ ] 일부 상세·지원조건 요청이 실패한 실행은 부분 실패로, 목록 수집 자체가 실패한 실행은 실패로 기록된다. → 설계 5·6, 5·9단계. 상세 2쪽 503: 실행 `PARTIAL`, 상세 경로 `PARTIAL`, 지원조건 `SUCCEEDED`. 목록 1쪽 503: 실행 `FAILED`·`API_UNAVAILABLE`, 경로 행은 목록 하나(`FAILED`), 상세·지원조건 요청 0회.
- [ ] 부분 실패한 실행 뒤에도 이전에 정상 저장한 값이 오류나 빈 값으로 바뀌지 않는다. → 설계 8, 5·9단계. 기본 수집 후 stub에서 A의 상세 구비서류와 C의 목록 지원내용을 바꾸고 상세 1쪽·목록 2쪽을 503으로 둔다. 두 번째 실행은 `PARTIAL`이고, A의 `benefit_detail`·상세 `source_record` 해시, C의 `benefit` 내용 컬럼·`updated_at`·`last_seen_at`·목록 `source_record` 해시가 첫 실행 후와 같으며 이력은 0건이다.
- [ ] 상세가 없는 신규 혜택은 정보 미수집 상태로 조회된다. → 설계 9, 6·9단계. 빈 DB에서 상세 2쪽(C) 503으로 수집: `BenefitQueryService.findByServiceId(C)`가 `NOT_COLLECTED`, A·B는 `COLLECTED`.
- [ ] 공공 API 오류와 DB 오류가 로그와 실행 기록에서 구분된다. → 설계 1·7, 5·9단계. 목록 2쪽 C의 서비스명을 300자(`VARCHAR(255)` 초과)로 바꾸고 상세 2쪽을 503으로 둔다. `collection_failure`에 목록 2쪽 `DB_ERROR`와 상세 2쪽 `API_UNAVAILABLE`이 각각 남고, `OutputCaptureExtension`으로 로그에 `errorType=DB_ERROR`·`errorType=API_UNAVAILABLE`가 있는지 확인한다. 로그와 실패 메시지에 인증키가 없다. Testcontainers MySQL 8.4의 기본 strict 모드에서 길이 초과가 실제 DB 오류로 나는 것을 확인했다.
- [ ] 수집이 하루 1회 자동으로 실행되고, 일반 사용자에게 공개된 경로로는 수집을 실행할 수 없다. → 설계 10·11, 7·9단계. `CollectionSchedulerTest`(cron 등록, `collectDaily()`로 실행 생성, 키 없음·비활성 처리), `CollectionAccessTest`(collection 패키지 HTTP 핸들러 없음).

## 검증 명령

- `backend/`에서 `./gradlew test`
- 실제 API 수동 확인(기본 테스트에 포함하지 않음): 로컬 MySQL을 띄우고 `backend/`에서 다음을 실행한다.
  - 1회 수집: `set -a; . <인증키 env 파일>; set +a; SPRING_PROFILES_ACTIVE=local,collect ./gradlew bootRun`
  - 스케줄 동작: 같은 환경에서 `SPRING_PROFILES_ACTIVE=local ./gradlew bootRun --args='--gov24.schedule.enabled=true --gov24.schedule.cron="0 */2 * * * *"'`로 2분 안에 실행이 하나 생기는지 본다.
  - 실행 기록 확인:
    ```sql
    SELECT id, status, error_type, error_message, started_at, finished_at FROM collection_run ORDER BY id DESC LIMIT 5;
    SELECT run_id, endpoint, page, error_type, attempts, message FROM collection_failure ORDER BY id DESC LIMIT 20;
    ```
  - 정상 실행이 `SUCCEEDED`이고 실패 0건이며, 로그에 인증키가 없는지 본다.

## 결정 필요

없음
