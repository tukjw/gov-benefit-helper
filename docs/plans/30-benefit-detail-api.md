status: in-progress

# 구현 계획 — #30 비회원 혜택 상세 조회와 오래된 정보 표시

브랜치: `feat/30-benefit-detail-api` (develop `1272a75` 기준)

## 목표 및 범위

- 목표: 로그인 없이 서비스 ID로 저장된 혜택의 상세와 신청처를 확인한다. 수집이 멈춰도 DB만으로 응답하고, `DEC-FRESHNESS` 기준을 넘으면 오래된 정보임을 응답에 담는다.
- 포함:
  - 혜택 상세 API `GET /api/benefits/{serviceId}`와 응답 DTO.
  - 온라인 신청 URL을 링크로 쓸 수 있는 형태로 정리하는 규칙. 결정 문서 0011.
  - 오래된 정보 판정과 기준 시각 조회.
  - 없는 자원의 404 응답(`NOT_FOUND`) 처리.
  - 성공한 수집 실행이 없을 때의 판정을 `DEC-FRESHNESS`에 기록.
- 제외:
  - 상세 내용의 수집 시각(`source_record`의 `SERVICE_DETAIL` 원문 `collected_at`). 수집 시각은 목록 기준 `lastSeenAt`만 준다(2026-10-05 사용자 결정). 목록 수집은 성공하는데 한 혜택의 상세 요청만 계속 실패하면, 오래된 상세가 최신처럼 보인다. 필요해지면 `detail.collectedAt`을 추가한다. 필드 추가는 하위 호환이다.
  - 법령·자치법규·행정규칙, 사용자 구분, 소관기관 코드 등 완료 조건에 없는 항목. 필요하면 필드를 추가한다.
  - 상세의 수정일시(`yyyy-MM-dd`) 해석. 출처 수정 시각은 목록 `수정일시`를 해석한 `source_modified_at`만 쓴다([0008](../decisions/0008-benefit-normalization.md)).
  - 스킴 없는 338건의 실제 접속 확인(외부 요청이 필요해 하지 않음, 2026-10-05 사용자 결정).
  - 오래된 정보 기준 시각의 캐시. `DEC-FRESHNESS`의 캐시 기준이 미정이다.
  - 비회원 요청량 제한(`DEC-AUTH`, O2·O3)과 API 버전 표기(`DEC-APP`).
  - 운영 JVM 시간대 강제(#29 플랜의 후속 이슈).

## 확인된 사실과 제약

- 온라인신청사이트URL(상세) 분포(2026-09-28 스냅샷, [데이터 검증 보고서](../research/gov24-api-data.md#링크-형식)): 값 없음 8,935, `https://` 주소 하나 1,121, `http://` 주소 하나 546, 스킴 없는 주소(`www.hrd.go.kr`, `booking.kywa.or.kr` 등) 338, 대문자 도메인(`G4B.go.kr`) 1. 최대 174자. 공백만 있는 문자열은 0건이다. 상세조회URL(목록)은 모든 혜택이 `https://www.gov.kr/portal/rcvfvrSvc/dtlEx/<서비스ID>`다.
- 상세에서 받은 항목은 `benefit_detail`에, 목록과 값이 같은 항목은 `benefit`에만 있다(`BenefitDetailContent` 주석). 목록 `신청방법`은 `benefit.apply_method_type`, 상세 `신청방법`은 `benefit_detail.apply_method`다. 목록 `전화문의`와 상세 `문의처`도 따로 저장된다(`Gov24RecordMapper`).
- `benefit_detail` 행이 없으면 상세 미수집이다(`DetailStatus`). 상세 요청이 나중에 실패해도 이전 상세 행은 남는다. 두 엔티티 사이에 JPA 연관관계는 없고 `benefit_detail.benefit_id`가 `benefit.id`의 FK다.
- 실행 상태(결정 0007, `CollectionService.collectEndpoints`): 목록 1쪽을 저장하지 못하면 `FAILED`다. 따라서 `SUCCEEDED`·`PARTIAL`이면 목록을 저장한 실행이다. 목록을 저장한 뒤 DB 오류나 예상하지 못한 예외가 나도 실행은 `FAILED`가 된다. 이 경우 목록이 갱신됐어도 기준 시각으로 쓰지 않으므로 오래된 정보 표시가 일찍 켜진다. `DEC-FRESHNESS`가 `FAILED`를 갱신 없는 실행으로 정했으므로 따른다. `RUNNING` 실행은 `finished_at`이 `NULL`이다.
- `collection_run`은 하루 1행 정도 쌓인다. 인덱스 없이 조회해도 비용이 작다.
- `Clock` 빈은 `CollectionConfig`에 있다(`Asia/Seoul`). `BenefitQueryService`는 아직 주입받지 않는다.
- `ErrorCode.NOT_FOUND`의 문구는 `요청한 경로를 찾을 수 없습니다.`이다. 결정 0009 표의 뜻은 "요청한 경로나 자원이 없다"다. 이 문구를 확인하는 테스트는 없다. 클라이언트는 `message`로 분기하지 않는다(0009).
- `GlobalExceptionHandler`에는 `@PathVariable` 변환 실패 처리가 없다. `serviceId`는 문자열이라 변환 실패가 없다.
- Jackson 직렬화 설정을 바꾼 곳이 없어 `null` 필드도 응답에 나온다. #29 검색 응답도 같다.
- `BenefitSearchApiTest`가 `@SpringBootTest`·MockMvc·Testcontainers·`Gov24StubServer` 구성의 예시다. 수집 테스트들은 `benefit_detail`, `benefit`, `source_record_history`, `source_record`, `collection_quarantine`, `collection_failure`, `collection_run_step`, `collection_run` 순서로 비운다.
- `CollectionAccessTest`는 `kr.govbenefit.collection` 패키지에 HTTP 경로가 없음을 확인한다. 상세 컨트롤러는 `kr.govbenefit.benefit.controller`에 둔다.

## 설계 결정

1. **온라인 신청 URL은 조회할 때 응답 DTO로 바꾸면서 정리한다.** 결정 문서 0011.
   - 규칙(`OnlineApplyUrl.toLink(String raw)`):
     1. `null`이면 `null`. 앞뒤 공백을 지운다. 중간에 공백이 있으면(여러 주소·설명문) `null`이다.
     2. `scheme://`로 시작하지 않으면 `https://`를 붙인다(2026-10-05 사용자 결정).
     3. `java.net.URI`로 해석해 스킴이 `http`·`https`(대소문자 무시)이고, 호스트가 있고, 호스트에 `.`이 있고, 사용자 정보(`user@`)가 없을 때만 링크로 인정한다. 그 밖에는 `null`이다. `javascript:`, `ftp://` 같은 값을 링크로 내보내지 않기 위해서다.
     4. 인정한 값은 받은 글자 그대로(스킴을 붙인 것 말고는) 돌려준다. 대문자 도메인도 바꾸지 않는다.
   - 응답에는 정리한 링크(`onlineApplyUrl`)와 원문(`onlineApplyUrlText`)을 함께 둔다. 링크가 `null`이면 클라이언트는 링크 대신 접수 방법(`detail.applyMethod`, 상세 미수집이면 목록 `applyMethodType`·`receptionAgency`)을 보여준다.
   - 대안: 수집할 때 정리해 컬럼에 저장(0008 방식). 마이그레이션과 수집 변경이 필요하고, 다음 수집까지 값이 비어 있다. 0008이 조회 시 계산을 뺀 이유(DB 필터·정렬)가 URL에는 해당하지 않는다.
   - 대안: 스킴 없는 주소에 `http://`. 더 많은 사이트가 열리지만 평문 접속이 된다.
   - 공식 출처 링크(`guideUrl`, 목록 상세조회URL)도 같은 규칙을 거친다. 현재 값은 모두 `https://www.gov.kr/...`라 결과가 같다.
2. **빈 값은 JSON `null`로 보내고 문구로 채우지 않는다.** ‘정보 없음’ 표시는 클라이언트가 한다. #29 설계 7, 0008과 같다. 신청기한은 `applyPeriod` 객체(`text`, `type`, `needsReview`)로 묶고, `needsReview`가 `true`이면 ‘확인 필요’로 원문과 함께 보여준다. `type`·`needsReview`는 정규화 전에 수집된 행이면 `null`이다.
3. **응답은 목록 항목과 상세 항목을 나눈 중첩 구조로 하고, 상세 미수집은 `detail: null`로만 나타낸다.** `detailStatus` 필드는 두지 않는다(2026-10-05 사용자 결정). 미수집은 `detail`이 `null`, 정보 없음은 객체 안의 필드가 `null`이라 응답 구조로 구분된다. 같은 사실을 필드 두 개로 내보내면 둘이 어긋날 수 있다. 상태가 둘보다 많아지면 그때 상태 필드를 추가한다. 도메인 `DetailStatus`와 `BenefitView`는 수집 테스트가 쓰므로 그대로 둔다.
   ```json
   {
     "serviceId": "654000000031",
     "name": "청년 월세 지원",
     "summary": "...",
     "field": "주거·자립",
     "agencyName": "...", "agencyType": "시군구", "departmentName": "...",
     "supportType": "현금",
     "supportContent": "...", "target": "...", "selectionCriteria": null,
     "applyPeriod": { "text": "...", "type": "FIXED_DATE", "needsReview": false },
     "applyMethodType": "...", "receptionAgency": null, "phoneInquiry": "...",
     "guideUrl": "https://www.gov.kr/portal/rcvfvrSvc/dtlEx/654000000031",
     "sourceModifiedAt": "2026-09-27T14:11:21+09:00",
     "lastSeenAt": "2026-10-05T04:00:12.345+09:00",
     "detail": {
       "purpose": "...", "applyMethod": "...",
       "requiredDocuments": "...", "officialCheckDocuments": null, "selfCheckDocuments": null,
       "contact": "...", "receptionAgencyName": null,
       "onlineApplyUrl": "https://www.hrd.go.kr", "onlineApplyUrlText": "www.hrd.go.kr"
     },
     "freshness": { "stale": false, "referenceAt": "2026-10-05T04:12:30.123+09:00" }
   }
   ```
4. **수집 시각은 목록 기준 `lastSeenAt`만 준다.** 출처 수정 시각 `sourceModifiedAt`과 구분하며, 출처 수정 시각이 없으면 `null`이다(0008). 시각은 `ApiTimes.toOffset`으로 `+09:00`을 붙인다(0010). 대안인 상세 원문 수집 시각은 목표 및 범위의 제외에 적었다.
5. **오래된 정보 기준 시각은 요청마다 `collection_run`에서 조회한다.** 상태가 `SUCCEEDED`·`PARTIAL`인 실행 중 `finished_at`이 가장 늦은 실행의 `finished_at`이 기준 시각이다. 실행 상태로 판정하고, 목록 경로(`collection_run_step`)의 상태는 보지 않는다. `DEC-FRESHNESS`가 실행 상태로 정했고, 어긋나는 경우(확인된 사실 참고)에도 오래된 쪽으로만 틀린다.
   - 판정: `now`가 기준 시각 + 48시간보다 뒤면 오래된 정보다. 정확히 48시간이면 아직 아니다. `now`는 `LocalDateTime.now(clock)`이다.
   - 성공한 실행이 한 번도 없으면 `stale: true`, `referenceAt: null`이다(2026-10-05 사용자 결정). 확인한 적이 없는 정보를 최신이라고 하지 않는다.
   - 대안: 메모리 캐시. 인스턴스마다 값이 어긋날 수 있고, `DEC-FRESHNESS`의 캐시 기준이 미정이다(#29 설계 2와 같은 이유).
6. **조회는 읽기 전용 트랜잭션 하나에서 쿼리 3개로 한다.** 혜택(`findByServiceId`), 상세(`findById(benefitId)`), 기준 실행을 차례로 읽는다. REPEATABLE READ라 수집 중에도 세 쿼리가 같은 스냅샷을 본다. 엔티티 연관관계는 추가하지 않는다. 수집 코드에도 영향을 주기 때문이다.
7. **없는 서비스 ID는 `NotFoundException`을 던져 404 `NOT_FOUND`로 응답한다.** 0009에 따라 컨트롤러에서 오류 본문을 만들지 않는다. `NOT_FOUND` 문구를 `요청한 경로나 자원을 찾을 수 없습니다.`로 바꿔 경로 404와 자원 404에 함께 쓴다. 코드의 뜻은 0009 표 그대로라 바뀌지 않는다.

## 단계별 구현 계획

1. **공통 API** `kr.govbenefit.common.api`
   - `NotFoundException`(`RuntimeException`): 요청한 자원이 없다.
   - `GlobalExceptionHandler.handleNotFound(NotFoundException)`: 404와 `ApiErrorResponse.of(ErrorCode.NOT_FOUND)`.
   - `ErrorCode.NOT_FOUND` 문구를 `요청한 경로나 자원을 찾을 수 없습니다.`로 바꾼다.
2. **링크 정리** `kr.govbenefit.benefit.domain.OnlineApplyUrl`
   - `static String toLink(String raw)`: 설계 1의 규칙. 인스턴스를 만들지 않는 유틸 클래스.
3. **기준 실행 조회** `kr.govbenefit.collection.repository.CollectionRunRepository`
   - `Optional<CollectionRun> findFirstByStatusInOrderByFinishedAtDesc(Collection<CollectionStatus> statuses)`.
4. **오래된 정보 판정** `kr.govbenefit.benefit.dto.Freshness` record(`boolean stale`, `OffsetDateTime referenceAt`)
   - `static final Duration STALE_AFTER = Duration.ofHours(48)`.
   - `static Freshness judge(LocalDateTime referenceAt, LocalDateTime now)`: `referenceAt`이 `null`이면 `stale = true`. 아니면 `now.isAfter(referenceAt.plus(STALE_AFTER))`. `referenceAt`은 `ApiTimes.toOffset`으로 바꾼다.
5. **응답 DTO** `kr.govbenefit.benefit.dto.BenefitDetailResponse` record
   - 최상위 필드: 설계 3의 JSON. 중첩 record `ApplyPeriod`(`String text`, `ApplyPeriodType type`, `Boolean needsReview`), `Detail`(설계 3의 `detail` 필드).
   - `static BenefitDetailResponse of(Benefit benefit, BenefitDetail detail, Freshness freshness)`: `detail`이 `null`이면 `Detail`도 `null`. `guideUrl`과 `onlineApplyUrl`은 `OnlineApplyUrl.toLink`로 만들고, `onlineApplyUrlText`는 원문이다.
   - Javadoc에 `detail`이 `null`이면 상세 미수집, 필드가 `null`이면 원문에 값이 없다는 것과 `lastSeenAt`이 목록 기준이라는 것을 적는다.
6. **서비스** `BenefitQueryService`
   - 생성자 주입에 `CollectionRunRepository`, `Clock`을 더한다.
   - `BenefitDetailResponse getDetail(String serviceId)`: 혜택이 없으면 `NotFoundException`. 상세를 `benefitDetailRepository.findById(benefit.getId())`로 읽고, `findFirstByStatusInOrderByFinishedAtDesc(List.of(SUCCEEDED, PARTIAL))`의 `finishedAt`과 `LocalDateTime.now(clock)`으로 `Freshness.judge`를 부른다. 클래스의 `@Transactional(readOnly = true)` 안에서 실행된다.
   - 기존 `findByServiceId`(`BenefitView`)와 `search`는 바꾸지 않는다.
7. **컨트롤러** `BenefitController`
   - `@GetMapping("/{serviceId}") BenefitDetailResponse detail(@PathVariable String serviceId)`. 클래스 Javadoc에 `REQ-DETAIL-01`을 더한다.
8. **테스트**
   - `OnlineApplyUrlTest`(단위): `https://`·`http://` 유지, 스킴 없는 주소에 `https://` 추가, 대문자 도메인(`G4B.go.kr`) 유지, 앞뒤 공백 제거, `null`·빈 문자열, 중간 공백(두 주소, 설명문), `javascript:alert(1)`, `ftp://a.go.kr`, `mailto:a@b.go.kr`, 사용자 정보(`https://u@a.go.kr`), 점 없는 호스트, `HTTPS://A.GO.KR` 인정.
   - `FreshnessTest`(단위): 기준 시각 + 48시간 정확히는 `false`, 1마이크로초 뒤는 `true`, 1분 전은 `false`, 기준 시각 `null`은 `true`·`referenceAt` `null`, `referenceAt`이 `+09:00` 오프셋.
   - `BenefitDetailApiTest`(새 클래스, `@SpringBootTest`, MockMvc, Testcontainers, `Gov24StubServer`, `gov24.schedule.enabled=false`): `setUp`에서 수집 테스트와 같은 순서로 테이블을 비운다. fixture는 `BenefitRepository`·`BenefitDetailRepository`·`CollectionRunRepository`로 넣는다(`CollectionRun.start`·`finish`). 실행 시각은 실제 시각 기준 상대값(`now - 48h ± 1분`)으로 넣는다. 시나리오는 완료 조건 매핑에 적는다. 모든 시나리오 뒤 `stub.requestCount()`가 0이다.
9. **문서**
   - `docs/decisions/0011-online-apply-url-link.md`: 설계 1. 적용 위치에 `OnlineApplyUrl`, `BenefitDetailResponse`. 영향에 링크를 내보내는 이후 API(관심 목록, 알림 등)가 같은 규칙을 쓰고, 원문을 함께 준다는 것을 적는다. 0008의 해석 값과 달리 조회 시 계산하는 이유를 근거에 적는다.
   - `docs/decisions/README.md` 목록 표에 0011을 추가한다.
   - `docs/decisions/0009-api-error-response.md`: 영향 또는 적용 위치에 `NotFoundException`을 적는다. 코드 표는 바뀌지 않는다.
   - `docs/product/development.md`의 `DEC-FRESHNESS`(정보 표시 기준) 행에 "성공한 수집 실행이 없으면 오래된 정보이고 기준 시각은 비운다"를 더하고 날짜(2026-10-05)와 #30을 적는다.

## 완료 조건 매핑

`BenefitDetailApiTest` fixture는 상세까지 모든 값이 있는 혜택, 목록·상세에 `NULL` 값이 섞인 혜택, 신청기한 확인 필요 혜택, 출처 수정 시각이 `NULL`인 혜택, 상세 행이 없는 혜택, 온라인 신청 URL이 `https://`·`http://`·스킴 없음·링크 불가(`javascript:alert(1)`, 설명문)·`NULL`인 혜택을 포함한다.

- [ ] 로그인 없이 서비스 ID로 상세를 조회할 수 있다. → 7단계. 인증 정보 없이 `GET /api/benefits/{serviceId}`가 200이다.
- [ ] 지원 내용·대상·선정기준·신청 방법·기한·서류·문의처·공식 출처(gov.kr 상세 주소)를 제공한다. 빈 항목은 ‘정보 없음’, 신청기한 확인 필요 플래그가 있으면 ‘확인 필요’로 구분한다. → 설계 2·3, 5단계. 각 필드 값이 DB와 같다. `NULL`인 선정기준·문의처 등은 JSON `null`이다. 확인 필요 혜택은 `applyPeriod.needsReview`가 `true`이고 `applyPeriod.text`에 원문이 있다. `guideUrl`이 gov.kr 상세 주소다.
- [ ] 온라인 신청 URL과 안내 페이지 URL을 구분한다. 스킴 없는 온라인 신청 주소도 링크로 열 수 있고, 링크로 쓸 수 없는 값이나 온라인 신청 URL이 없으면 링크 대신 접수 방법을 보여준다. → 설계 1, 2·5단계. `onlineApplyUrl`과 `guideUrl`이 다른 필드다. 스킴 없는 주소는 `https://`가 붙는다. 링크 불가 값은 `onlineApplyUrl`이 `null`, `onlineApplyUrlText`가 원문이고 `detail.applyMethod`가 있다. `NULL`이면 둘 다 `null`이다. 규칙의 경계는 `OnlineApplyUrlTest`에서 확인한다.
- [ ] 출처 수정 시각과 수집 시각을 구분해 제공하고, 출처 수정 시각이 없으면 비워 둔다. → 설계 4. `sourceModifiedAt`과 `lastSeenAt`이 각각 DB 값이고 `+09:00`이 붙는다. 출처 수정 시각이 `NULL`인 혜택은 `sourceModifiedAt`이 `null`이고 `lastSeenAt`은 값이 있다.
- [ ] 목록을 갱신한 마지막 수집 실행(`SUCCEEDED` 또는 `PARTIAL`) 종료 후 48시간이 지나면 오래된 정보로 표시한다. 48시간 직전·직후, `FAILED` 실행만 이어진 경우를 검증한다. → 설계 5, 3·4·6단계. `SUCCEEDED`가 `now - 48h + 1분`에 끝났으면 `stale: false`, `now - 48h - 1분`이면 `true`이고, `referenceAt`이 그 실행의 종료 시각이다. `PARTIAL`도 기준이 된다. 오래된 `SUCCEEDED` 뒤에 `FAILED`만 이어지면 `referenceAt`이 `SUCCEEDED`의 종료 시각이고 48시간이 지났으면 `true`다. `RUNNING` 실행은 무시된다. 실행이 없거나 `FAILED`만 있으면 `stale: true`, `referenceAt: null`이다. 정확한 48시간 경계는 `FreshnessTest`에서 확인한다.
- [ ] 수집이 실패·중단돼도 저장된 상세를 조회할 수 있고, 상세 요청은 공공 API를 호출하지 않는다. → 설계 6. `FAILED` 실행만 있는 상태에서도 상세가 200이고 `detail`이 채워져 있다. 모든 시나리오 뒤 `stub.requestCount()`가 0이다.
- [ ] 없는 서비스 ID는 찾을 수 없음으로 응답한다. 상세 미수집 혜택은 존재하는 혜택으로 응답하되 상세 미수집임을 구분한다. → 설계 3·7, 1·6단계. 없는 서비스 ID는 404이고 본문이 `code: NOT_FOUND`, 빈 `errors`다. 상세 행이 없는 혜택은 200이고 `detail`이 `null`이며 목록 항목(지원 내용·대상·신청기한·`guideUrl`)은 채워져 있다.

## 검증 명령

- `backend/`에서 `./gradlew test`
- 로컬 확인(기본 테스트에 포함하지 않음): `SPRING_PROFILES_ACTIVE=local ./gradlew bootRun` 뒤
  ```sh
  curl -s 'http://localhost:8080/api/benefits/654000000031'
  curl -s -i 'http://localhost:8080/api/benefits/NO-SUCH-ID'
  ```
  스킴 없는 주소와 상세 미수집 혜택은 아래 SQL로 서비스 ID를 찾아 확인한다.
  ```sql
  SELECT b.service_id, d.online_apply_url FROM benefit b JOIN benefit_detail d ON d.benefit_id = b.id
  WHERE d.online_apply_url NOT LIKE 'http%' LIMIT 5;
  SELECT b.service_id FROM benefit b LEFT JOIN benefit_detail d ON d.benefit_id = b.id
  WHERE d.benefit_id IS NULL LIMIT 5;
  SELECT status, finished_at FROM collection_run ORDER BY id DESC LIMIT 5;
  ```

## 결정 필요

없음
