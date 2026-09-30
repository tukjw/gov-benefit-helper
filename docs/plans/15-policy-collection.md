status: in-progress

# 구현 계획 — #15 공공서비스 혜택 전체 수집과 저장·실행 이력 구현

브랜치: `feat/15-policy-collection` (develop `cdb9300` 기준)

## 목표 및 범위

- 목표: 목록·상세·지원조건 전체를 받아 출처 서비스 ID로 연결해 저장하고, 원문 최신본·변경 이력·실행 이력을 남긴다. 같은 응답을 반복 수집해도 중복·손상이 없다.
- 포함:
  - Flyway 첫 마이그레이션: 혜택(`benefit`, `benefit_detail`), 원문(`source_record`, `source_record_history`), 수집 실행(`collection_run`, `collection_run_step`), 격리(`collection_quarantine`).
  - 공공서비스 API 클라이언트, 경로별 쪽 단위 수집·저장, 내용 해시 기반 변경 감지.
  - 로컬 수동 실행용 프로필 한정 실행기.
  - 다음 수집 이슈(#16, #17 등)가 따라야 할 결정 3개를 `docs/decisions/`에 결정 문서로 올린다.
  - MockWebServer stub과 T0 스냅샷에서 고른 fixture로 완료 조건 검증.
- 제외:
  - 시간 제한·재시도·호출 예산, 부분 실패 처리, 정기 실행(외부 장애 대응 이슈). 이번에는 요청 하나가 실패하면 실행을 `FAILED`로 끝낸다.
  - 신청기한·수정 시각·등록 시각 해석, 온라인신청 URL 정리, 빈 값 처리(정규화 이슈). 수정·등록 일시는 원문에만 두고 컬럼을 만들지 않는다.
  - 지원조건 코드의 구조화 저장(조건 비교 이슈). 이번에는 원문으로만 저장한다.
  - 비노출·종료 판단(`REQ-DATA-01` P1, #6).
  - 실행 이력 조회 API. 실행 이력은 DB 조회로 확인한다.
  - 수집 실행이 겹칠 때의 처리(`REQ-QUALITY-01` P1). `service_id` 유니크 제약만 최종 방어선으로 둔다.

## 확인된 사실과 제약

- 기본 주소 `https://api.odcloud.kr/api/gov24/v3`, 경로 `/serviceList`, `/serviceDetail`, `/supportConditions`. 쪽 인자는 `page`, `perPage`. 응답 최상위는 `currentCount`, `data`, `matchCount`, `page`, `perPage`, `totalCount`다([데이터 검증 보고서 §1](../research/gov24-api-data.md#1-인증과-페이지-조회)).
- 인증은 `Authorization: Infuser <Decoding 키>` 헤더로 한다. 키가 URL에 남지 않는다. 접두어가 없으면 401이다.
- 200인데 `data` 키가 없으면 응답이 너무 큰 경우로, 실패로 다룬다. `data: []`는 마지막 쪽 뒤다. 인증 실패는 401 `{"code":-401|-4, "msg":...}`다([§2](../research/gov24-api-data.md#2-실패-응답)).
- T0 기준 세 경로 모두 10,941건, 서비스ID 누락·중복·경로 간 불일치 0건, 서비스ID는 모두 12자다. 1,000건씩 경로당 11쪽이다.
- 목록과 상세에 같은 이름으로 있는 서비스명·지원대상·선정기준·지원내용·신청기한·소관기관명은 T0에서 값이 모두 같았다. 신청방법·지원유형은 이름만 같고 다른 값이다([§3](../research/gov24-api-data.md#3-세-경로의-연결)).
- 조회수(`조회수`, 정수)는 목록에만 있다. 상세·지원조건에는 비내용 필드가 관찰되지 않았다.
- 필드 최대 길이(T0, 문자): 서비스명 85, 서비스목적요약 96, 신청기한 220, 상세조회URL 54, 온라인신청사이트URL 174, 긴 텍스트(지원대상·선정기준·지원내용·구비서류·문의처·전화문의 등) 약 2,060자·최대 4,801바이트. `TEXT`(65,535바이트)에 들어간다.
- Boot 4.1.1의 JSON 라이브러리는 Jackson 3(`tools.jackson`, 3.1.5)이다.
- MockWebServer(`com.squareup.okhttp3:mockwebserver3`, 최신 5.5.0)는 Boot BOM이 버전을 관리하지 않으므로 버전을 명시한다. Kotlin 표준 라이브러리가 딸려 오며 Boot BOM이 Kotlin을 2.3.21로 맞춘다. 호환은 1단계에서 테스트로 확인한다.
- 테스트는 Testcontainers MySQL 8.4(`TestcontainersConfiguration`)에서 실행한다. 아직 Flyway 마이그레이션이 없다.
- PR은 CI `test` 체크가 통과해야 머지된다(#14). 채택된 결정 문서는 [0001](../decisions/0001-db-collation.md)(정렬 규칙), [0002](../decisions/0002-db-timezone.md)(시간대)이며 새 시각 컬럼은 0002를 따른다.
- Hibernate `ddl-auto: validate`라 엔티티 컬럼 타입이 마이그레이션과 맞아야 한다.

## 설계 결정

1. **원문은 최신 테이블과 이력 테이블로 나눈다.** 결정 문서 [0003](../decisions/0003-source-record-retention.md). `source_record`는 (서비스ID, 경로)마다 최신 1행, `source_record_history`는 내용이 바뀔 때 교체되기 전 버전을 옮겨 둔다. 첫 수집은 이력을 만들지 않고, 내용 변경 한 번에 이력 한 행이 생긴다. 버전 테이블 하나(최신 조회에 max 쿼리 필요)나 혜택 행의 JSON 컬럼(목록에 없는 ID의 원문을 둘 곳이 없음)보다 `DEC-DATA`의 "최신 1벌 + 변경 이력"과 바로 대응한다. `source_record`는 `benefit`에 FK를 걸지 않아, 목록에 없는 ID의 상세·지원조건 원문도 잃지 않는다.
2. **원문 컬럼은 MySQL `JSON`, 변경 감지는 앱에서 계산한 해시로 한다.** 결정 문서 [0003](../decisions/0003-source-record-retention.md). 해시는 레코드에서 `조회수`를 빼고 키를 정렬해 직렬화한 UTF-8 바이트의 SHA-256이다(레코드는 평평한 객체). `JSON` 타입은 원본 바이트·키 순서를 보존하지 않지만 형식을 검증하고 #6의 비교 분석에 `JSON_EXTRACT`를 쓸 수 있다. 재검증에는 의미가 같은 원문이면 충분하다고 본다.
3. **조회수만 바뀐 경우 최신 원문은 새 값으로 덮어쓰고 이력·내용 변경 시각은 바꾸지 않는다.** 최신 1벌이 실제 최신 응답이 되도록 한다.
4. **완료 조건의 "저장된 데이터가 바뀌지 않는다"는 혜택의 내용 컬럼, 원문 해시·내용 변경 시각, 원문 이력에 적용한다.** `benefit.last_seen_at`과 `source_record.collected_at`(원문을 마지막으로 받은 시각)은 수집마다 갱신되는 확인 기록이라 대상이 아니다. 같은 응답이면 `payload`도 같으므로 결과적으로 바뀌지 않는다.
5. **조회용 혜택은 `benefit`(목록) + `benefit_detail`(상세, 1:1)로 나눈다.** 상세 행이 없으면 상세 미수집 상태로 구분된다. 목록과 같은 값인 상세 필드(서비스명·지원대상·선정기준·지원내용·신청기한·소관기관명)는 목록 값만 컬럼으로 두고 상세 값은 원문에만 둔다. 해석이 필요 없는 항목은 원문 문자열 그대로 저장한다.
6. **`benefit_detail`은 `benefit`이 있을 때만 만든다.** 목록에 없는 서비스ID의 상세는 원문만 저장하고 `unlinked_count`로 센다. 다음 실행에서 목록에 나타나면 그때 연결된다. 지원조건은 이번에 원문만 저장하므로 같은 건수만 센다.
7. **수집은 경로 순서(목록 → 상세 → 지원조건)로 쪽 단위 스트리밍, 1쪽 = 1 트랜잭션이다.** 메모리가 쪽 크기로 제한되고, 실패해도 앞서 커밋한 쪽은 남는다. 오케스트레이터(`CollectionService`)는 트랜잭션 없이 돌고 쪽 저장만 별도 빈(`CollectionPageWriter`)의 `@Transactional` 메서드로 한다. 실행 시작 행은 쪽 처리 전에 저장·커밋되어 도중에 죽어도 남는다.
8. **쪽 순회는 첫 응답의 `totalCount`와 `perPage`로 마지막 쪽을 계산하고, 중간에 `data: []`가 오면 멈춘다.** `data` 키가 없는 200, 4xx·5xx, 연결 실패는 해당 경로·실행을 `FAILED`로 끝내고 이후 경로는 실행하지 않는다(재시도·부분 실패는 다음 이슈).
9. **멱등 저장은 JPA로 한다.** 쪽마다 서비스ID 목록으로 기존 행을 한 번에 조회(`findAllBy...In`)하고 해시를 비교해 신규는 insert, 변경은 update + 이력 insert, 동일은 확인 시각만 갱신한다. `INSERT … ON DUPLICATE KEY UPDATE`는 "바뀐 경우에만 이력"을 affected-rows에 기대야 해서 쓰지 않는다.
10. **필수 식별자가 없는 레코드는 `collection_quarantine`에 원문과 사유를 남기고 건너뛴다.** 필수 식별자는 `서비스ID`이며, 없거나 문자열이 아니거나 공백이면 격리한다. 격리가 있어도 실행 상태는 `SUCCEEDED`이고 건수만 기록한다. 요청 실패만 부분 실패·실패의 대상이다.
11. **실행 이력은 `collection_run`(실행) + `collection_run_step`(경로별)로 둔다.** 10과 함께 결정 문서 [0004](../decisions/0004-collection-run-status.md). 다음 이슈의 "목록 성공, 상세 실패 → 부분 실패"를 경로별 상태로 표현할 수 있다. 상태는 문자열 컬럼(`RUNNING`, `SUCCEEDED`, `FAILED`)이며 `PARTIAL`은 부분 실패 이슈에서 추가한다. 조회는 DB로 한다.
12. **HTTP 클라이언트는 Spring `RestClient`, 테스트 stub은 MockWebServer다.** 13과 함께 결정 문서 [0005](../decisions/0005-gov24-client-and-stub.md). 실제 HTTP 서버라 완료 조건의 "로컬 stub 서버"를 그대로 충족하고, 다음 이슈에서 시간 초과·연결 실패도 같은 stub으로 재현할 수 있다. WireMock은 Jetty 버전이 Boot BOM과 충돌할 위험이 있고, `MockRestServiceServer`는 실제 서버가 아니다.
13. **인증키는 `GOV24_SERVICE_KEY` 환경 변수(또는 `backend/.env`)로 받는다.** 앱 기동에는 필요 없고, 수집을 시작할 때 비어 있으면 실행을 만들지 않고 예외로 끝낸다. 요청·응답 헤더를 로그로 남기지 않고, 설정 객체의 `toString`은 키를 가린다. 실패 메시지에는 상태 코드와 응답 본문의 `code`·`msg`만 남긴다.
14. **실행 트리거는 서비스 메서드(`CollectionService.collectAll()`)다.** 로컬 수동 확인용으로 `collect` 프로필에서만 켜지는 `ApplicationRunner`를 둔다. 공개 HTTP 엔드포인트는 만들지 않는다(`REQ-DATA-01` 실행 권한 비공개, `DEC-AUTH` 미정).

## 스키마

`backend/src/main/resources/db/migration/V1__create_benefit_collection_tables.sql`. 시각 컬럼은 `DATETIME(6)`(KST, 기존 시간대 설정과 같음).

| 테이블 | 컬럼 | 제약 |
| --- | --- | --- |
| `collection_run` | `id`, `started_at`, `finished_at` NULL, `status` VARCHAR(20), `error_message` VARCHAR(1000) NULL | |
| `collection_run_step` | `id`, `run_id`, `endpoint` VARCHAR(30), `status`, `started_at`, `finished_at` NULL, `page_count`, `received_count`, `created_count`, `changed_count`, `unchanged_count`, `quarantined_count`, `unlinked_count` | FK `run_id`, UNIQUE(`run_id`, `endpoint`) |
| `collection_quarantine` | `id`, `run_id`, `endpoint`, `page`, `reason` VARCHAR(50), `payload` JSON, `created_at` | FK `run_id` |
| `source_record` | `id`, `service_id` VARCHAR(20), `endpoint`, `payload` JSON, `content_hash` CHAR(64), `collected_at`, `content_changed_at`, `created_at` | UNIQUE(`service_id`, `endpoint`) |
| `source_record_history` | `id`, `service_id`, `endpoint`, `payload` JSON, `content_hash`, `collected_at`(그 버전을 마지막으로 받은 시각), `replaced_at`, `replaced_by_run_id` | INDEX(`service_id`, `endpoint`), FK `replaced_by_run_id` |
| `benefit` | `id`, `service_id`, 아래 목록 필드, `last_seen_at`, `created_at`, `updated_at` | UNIQUE(`service_id`) |
| `benefit_detail` | `benefit_id`, 아래 상세 필드, `created_at`, `updated_at` | PK·FK `benefit_id` |

`endpoint` 값은 `SERVICE_LIST`, `SERVICE_DETAIL`, `SUPPORT_CONDITIONS`. `benefit.updated_at`·`benefit_detail.updated_at`은 내용 컬럼이 바뀔 때만 갱신한다.

`benefit` 필드(목록 원문 → 컬럼):

| 원문 | 컬럼 | 타입 |
| --- | --- | --- |
| 서비스명 | `name` | VARCHAR(255) |
| 서비스목적요약 | `purpose_summary` | VARCHAR(500) |
| 서비스분야 | `service_field` | VARCHAR(50) |
| 사용자구분 | `user_type` | VARCHAR(100) |
| 지원유형 | `support_type` | VARCHAR(255) |
| 지원대상 | `target` | TEXT |
| 선정기준 | `selection_criteria` | TEXT NULL |
| 지원내용 | `support_content` | TEXT |
| 신청기한 | `apply_period_text` | VARCHAR(1000) |
| 신청방법 | `apply_method_type` | VARCHAR(255) NULL |
| 전화문의 | `phone_inquiry` | TEXT NULL |
| 접수기관 | `reception_agency` | VARCHAR(255) NULL |
| 소관기관명·소관기관유형·소관기관코드 | `agency_name`, `agency_type`, `agency_code` | VARCHAR(100), VARCHAR(50), VARCHAR(20) |
| 부서명 | `department_name` | VARCHAR(100) NULL |
| 상세조회URL | `detail_url` | VARCHAR(500) |

`benefit_detail` 필드(상세 원문 → 컬럼): 서비스목적 `purpose` TEXT, 신청방법 `apply_method` TEXT, 구비서류 `required_documents` TEXT, 공무원확인구비서류 `official_check_documents` TEXT, 본인확인필요구비서류 `self_check_documents` TEXT, 문의처 `contact` TEXT, 온라인신청사이트URL `online_apply_url` VARCHAR(500), 법령 `laws` TEXT, 자치법규 `local_laws` TEXT, 행정규칙 `admin_rules` TEXT, 접수기관명 `reception_agency_name` VARCHAR(255), 지원유형 `support_type` VARCHAR(255). 모두 NULL 허용이다(원문 결측을 그대로 둔다).

`benefit`의 내용 컬럼도 모두 NULL 허용이다. 필수 식별자가 아닌 필드의 결측 하나로 쪽 전체가 DB 오류로 실패하지 않게 하고, 빈 값 처리는 정규화 이슈에 맡긴다.

## 단계별 구현 계획

1. **의존성·설정**
   - `backend/build.gradle`: `testImplementation 'com.squareup.okhttp3:mockwebserver3:5.5.0'`.
   - `application.yaml`: `gov24.base-url`(기본 `https://api.odcloud.kr/api/gov24/v3`), `gov24.service-key: ${GOV24_SERVICE_KEY:}`, `gov24.per-page: 1000`.
   - `backend/.env.example`: `#GOV24_SERVICE_KEY=` 안내 한 줄(공공데이터포털 Decoding 키, 수집할 때만 필요).
2. **마이그레이션**: `db/migration/V1__create_benefit_collection_tables.sql`(위 스키마).
3. **실행 이력 도메인** `kr.govbenefit.collection`
   - `domain`: `CollectionRun`, `CollectionRunStep`, `CollectionQuarantine`, `SourceEndpoint`(enum, 경로 문자열 포함), `CollectionStatus`(enum).
   - `repository`: `CollectionRunRepository`, `CollectionRunStepRepository`, `CollectionQuarantineRepository`.
4. **원문 도메인** `kr.govbenefit.collection`
   - `domain`: `SourceRecord`(`payload`는 `@JdbcTypeCode(SqlTypes.JSON) String`), `SourceRecordHistory`.
   - `repository`: `SourceRecordRepository.findAllByEndpointAndServiceIdIn(...)`, `SourceRecordHistoryRepository`.
   - `service`: `ContentHasher.hash(ObjectNode record)` — `조회수` 제외, 키 정렬, SHA-256 hex.
5. **혜택 도메인** `kr.govbenefit.benefit`
   - `domain`: `Benefit`(`create`, `confirm(BenefitContent, now)` — 내용이 바뀐 경우에만 `updated_at` 갱신, `last_seen_at`은 항상 갱신), `BenefitDetail`(`create`, `update`). 항목 값은 `BenefitContent`·`BenefitDetailContent` record로 옮기고, 원문 한글 필드와의 대응은 `collection.service.Gov24RecordMapper`에 둔다.
   - `repository`: `BenefitRepository.findAllByServiceIdIn(...)`, `BenefitDetailRepository`.
6. **API 클라이언트** `kr.govbenefit.collection.client`
   - `Gov24Properties`(`@ConfigurationProperties("gov24")`, record, `toString`에서 키 마스킹).
   - `Gov24Client.fetchPage(SourceEndpoint, int page, int perPage)` → `Gov24Page(totalCount, List<JsonNode> data)`. `RestClient`에 `Authorization: Infuser <키>` 기본 헤더. `data` 키 없음·4xx·5xx·연결 실패는 `Gov24ApiException`(상태 코드와 `code`·`msg`만 담음).
7. **수집 서비스** `kr.govbenefit.collection.service`
   - `CollectionPageWriter`(`@Transactional` 메서드): `write(runId, endpoint, page, records, now)`. 흐름: 서비스ID 검증·격리 → `source_record` upsert(신규/변경+이력/조회수만 변경/동일) → 목록은 `benefit` upsert와 `last_seen_at` 갱신, 상세는 `benefit_detail` upsert 또는 미연결 집계 → 쪽 결과 건수 반환.
   - `CollectionService.collectAll()`: 키 확인 → `collection_run` 저장(`RUNNING`) → 경로마다 `collection_run_step` 저장, 쪽 순회, 건수 누적, 종료 시 `SUCCEEDED`/`FAILED` → 실행 종료 시각·상태 기록. 반환값은 실행 ID.
   - 시각은 `Clock` 빈으로 얻는다. `kr.govbenefit.collection.config.CollectionConfig`가 `Clock`(`Asia/Seoul`)을 등록하고 `Gov24Properties`를 활성화한다.
8. **로컬 실행기**: `CollectionRunner`(`@Profile("collect")`, `ApplicationRunner`)가 기동 시 `collectAll()`을 한 번 호출하고 실행 ID와 건수를 로그로 남긴다.
9. **테스트 fixture·stub**
   - T0 스냅샷에서 짧은 레코드 3건(서비스 A `511000000138`, B `403000000173`, C `O00024000031`)을 골라 경로별 레코드 배열로 `src/test/resources/gov24/{serviceList,serviceDetail,supportConditions}.json`에 둔다. 목록에는 A·B 뒤에 서비스ID를 `null`로 바꾼 레코드 1건을 넣는다.
   - `Gov24StubServer`(테스트 헬퍼): MockWebServer `Dispatcher`가 경로별 레코드 목록을 `page`·`perPage`로 잘라 실제 응답 형식(`totalCount` 등 포함)으로 돌려주고, 받은 `Authorization` 헤더를 기록한다. 경로·쪽 단위로 응답을 바꿔 끼울 수 있다.
   - 시나리오는 테스트에서 레코드를 바꿔 만든다: 기본(A·B·C + ID 없음 1건), 조회수만 변경(A), 내용 변경(A의 지원내용), C 제외(세 경로 모두), 목록 2쪽 `data` 누락(`{"code":0,"msg":"정상"}`), 401(`{"code":-4,...}`).
10. **테스트** `CollectionServiceTest`(`@SpringBootTest`, Testcontainers, `@DynamicPropertySource`로 stub 주소·`per-page=2`·테스트 키 주입), `ContentHasherTest`, `Gov24PropertiesTest`(단위).
11. **결정 문서**: `docs/decisions/0003-source-record-retention.md`(원문 최신본·이력·변경 감지), `0004-collection-run-status.md`(실행·경로 상태와 격리), `0005-gov24-client-and-stub.md`(호출 방식·인증키·테스트 stub). `docs/decisions/README.md` 목록 표에 추가한다.

## 완료 조건 매핑

- [ ] 로컬 stub 서버의 목록·상세·지원조건 응답이 출처 서비스 ID로 연결되어 저장된다 → 7·9·10단계. `base` 수집 후 A·B·C의 `benefit`·`benefit_detail`과 세 경로의 `source_record`가 같은 서비스ID로 조회된다.
- [ ] 같은 응답으로 수집을 반복하거나 전체를 다시 실행해도 혜택이 중복 생성되지 않고, 저장된 데이터가 바뀌지 않는다 → 설계 4·9, 10단계. `base`를 두 번 수집한 뒤 행 수, 내용 컬럼, `updated_at`, 원문 해시·`content_changed_at`, 이력 수가 첫 실행 후와 같다.
- [ ] 필수 식별자가 없는 응답은 오류 기록과 함께 격리되고, 나머지 혜택의 수집은 계속 진행된다 → 설계 10, 10단계. 격리 1건(원문·사유), A·B·C 정상 저장, 실행 `SUCCEEDED`, `quarantined_count=1`.
- [ ] 조회수만 바뀐 응답은 원문 이력을 만들지 않고, 내용이 바뀐 응답만 이력을 하나 만든다 → 설계 2·3, 10단계. `view-count-changed` 후 이력 0건·최신 원문의 조회수 갱신, `content-changed` 후 A 목록 이력 1건.
- [ ] 목록에서 빠진 혜택이 삭제되거나 종료 처리되지 않고, 마지막 확인 시각은 이전 값으로 유지된다 → 7단계, 10단계. `c-dropped` 후 C의 행이 남고 `last_seen_at`은 이전 값, A·B는 갱신된다.
- [ ] 실행마다 시작·종료 시각, 성공 상태, 처리 건수를 조회할 수 있다 → 설계 11, 10단계. 각 실행의 `collection_run`과 경로별 `collection_run_step` 건수를 리포지토리로 조회해 확인한다. `data` 없는 200을 주는 시나리오로 `FAILED`와 종료 시각 기록도 확인한다.
- [ ] 인증키는 저장소에 없고 실행 환경에서 주입되며, 로그에 남지 않는다 → 설계 13, 1·6단계, 10단계. stub이 받은 `Authorization`이 `Infuser <테스트 키>`이고, `OutputCaptureExtension`으로 성공·401 실패 실행의 로그와 `error_message`에 키 문자열이 없는지 확인한다. 저장소에는 `.env.example`의 빈 안내만 있다.

## 검증 명령

- `backend/`에서 `./gradlew test`
- 실제 API 수동 확인(기본 테스트에 포함하지 않음): 로컬 MySQL을 띄우고 `backend/`에서 `set -a; . <인증키 env 파일>; set +a; SPRING_PROFILES_ACTIVE=local,collect ./gradlew bootRun`. 이후 실행 이력 확인:
  ```sql
  SELECT r.id, r.status, r.started_at, r.finished_at, s.endpoint, s.received_count, s.created_count, s.changed_count, s.unchanged_count, s.quarantined_count, s.unlinked_count
  FROM collection_run r JOIN collection_run_step s ON s.run_id = r.id ORDER BY r.id DESC, s.id;
  ```
  두 번 실행해 두 번째 실행의 `created_count`·`changed_count`가 0에 가까운지(원천 변경분 제외), 로그에 키가 없는지 본다.

## 결정 필요

없음
