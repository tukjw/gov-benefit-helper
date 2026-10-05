status: done

# 구현 계획 — #29 비회원 혜택 이름·분야·기관 유형 검색 API 제공

브랜치: `feat/29-benefit-search-api` (develop `31b399f` 기준)

## 목표 및 범위

- 목표: 로그인 없이 저장된 혜택을 이름 키워드·분야·운영 기관 유형으로 찾고, 고정된 순서로 페이지를 넘겨 볼 수 있다. 공공 API 장애와 관계없이 DB만으로 동작한다. 계약은 `DEC-SEARCH`를 따른다.
- 포함:
  - 혜택 검색 API `GET /api/benefits`와 응답 DTO.
  - 공통 오류 응답 형식과 전역 예외 처리. 결정 문서 0009.
  - API 응답 시각 형식. 결정 문서 0010.
- 제외:
  - 오래된 정보 표시(`DEC-FRESHNESS`)와 상세 조회. #30에서 한다.
  - 필터 선택지(분야·기관 유형 값 목록) API. 필요하면 별도 이슈로 만든다.
  - 운영 기관 지역·접수 상태 필터, 한 번도 수집되지 않은 상태 구분(`REQ-SEARCH-01` P1).
  - 정렬용 인덱스와 검색 성능 개선. `DEC-SEARCH`에 따라 측정한 뒤 다룬다.
  - 비회원 요청량 제한(`DEC-AUTH`, O2·O3)과 API 버전 표기(`DEC-APP`).

## 확인된 사실과 제약

- 2026-10-04 로컬 DB 기준 `benefit` 10,950건. `name`·`service_field`·`agency_type`에 `NULL`이 없다. 기관 유형은 7종(`시군구`, `광역시도`, `중앙행정기관`, `공공기관`, `지방출자_출연기관`, `지방공기업`, `교육청`), 분야는 10종(`생활안정`, `보육·교육`, `보건·의료` 등)이다. 값에 가운뎃점(`·`)과 밑줄(`_`)이 들어 있다.
- 같은 이름의 혜택이 최대 42건이다(`DEC-SEARCH`). `service_id`는 `UNIQUE`라서 `name, service_id` 정렬로 순서가 하나로 정해진다.
- 정렬 규칙은 `utf8mb4_0900_ai_ci`다([0001](../decisions/0001-db-collation.md)). LIKE·`IN`·정렬 모두 대소문자와 악센트를 구분하지 않는다.
- 시각 컬럼은 `Asia/Seoul` 기준 `LocalDateTime`이다([0002](../decisions/0002-db-timezone.md)). Hibernate(`hibernate.jdbc.time_zone`)는 DB 시각을 JVM 기본 시간대로 바꿔 읽는다. 0002는 JVM 시간대를 정하지 않아, CI 러너(UTC)에서 JDBC로 넣은 `last_seen_at`이 9시간 어긋나 읽혔다(PR #31 CI, 2026-10-05). 테스트 JVM을 `Asia/Seoul`로 고정하고(`build.gradle`의 `user.timezone`), 운영 JVM 시간대를 강제하는 일은 후속 이슈로 둔다. 출처 시각과 수집 시각은 구분해 표시하고, 출처 시각이 `NULL`이면 수집 시각으로 대신하지 않는다([0008](../decisions/0008-benefit-normalization.md)).
- 현재 웹 계층(컨트롤러, 예외 처리)이 없고 Spring Security도 없다. `BenefitQueryService.findByServiceId`는 수집 테스트가 쓰는 조회이며 이번에 바꾸지 않는다.
- `CollectionAccessTest`는 `kr.govbenefit.collection` 패키지에 HTTP 경로가 없음을 확인한다. 검색 컨트롤러는 `kr.govbenefit.benefit.controller`에 둔다.
- `Gov24StubServer.requestCount()`로 stub에 들어온 요청 수를 확인할 수 있다.
- InnoDB 기본 격리 수준(REPEATABLE READ)에서 읽기 전용 트랜잭션의 쿼리는 같은 스냅샷을 본다. 목록 쿼리와 건수 쿼리를 한 트랜잭션에서 실행하면 수집 중에도 둘이 어긋나지 않는다.
- Spring의 `Pageable` 자동 바인딩은 음수 페이지를 0으로, 최대 크기 초과를 최대값으로 조용히 보정한다. 범위 밖 입력을 400으로 응답해야 하므로 쓰지 않는다.
- Spring Data JPA는 쪽 시작 위치를 `int`로 넘긴다(`Math.toIntExact`). `(page - 1) * size`가 `int` 범위를 넘으면 예외가 나므로, 그런 쪽은 조회하지 않고 빈 목록으로 응답해야 한다.
- 쿼리 파라미터를 `List<String>`으로 받을 때 값이 하나만 오면 Spring 기본 변환이 쉼표로 나눈다(`field=보육·교육,보건·의료`가 두 값이 됨). `@RequestParam`(2026-10-04)과 요청 DTO 바인딩(2026-10-05) 모두 테스트로 확인했다. 현재 분야·기관 유형 값에는 쉼표가 없다.

## 설계 결정

1. **동적 조건은 Spring Data JPA `Specification`으로 조립한다.**
   - 대안: QueryDSL(의존성과 annotation processing이 늘고 Boot 4·Hibernate 7 호환을 따로 검증해야 함), `JdbcClient` 네이티브 SQL(필요한 컬럼만 읽지만 WHERE 조립과 이스케이프를 직접 함).
   - 엔티티 전체를 읽으므로 TEXT 컬럼도 함께 읽지만, 쪽당 최대 100행이라 비용이 작다. 측정에서 문제가 나오면 리포지토리 구현만 바꾸고 서비스·응답 계약은 유지한다.
2. **키워드는 공백으로 나눈 단어마다 `name LIKE '%단어%'`를 AND로 묶는다.**
   - 공백은 유니코드 공백 전체(전각 공백 U+3000 포함)로 나눈다. 중복 단어는 한 번만 쓴다. 빈 키워드·공백만 있는 키워드·키워드 생략은 조건 없음(전체 목록)이다.
   - LIKE 특수 문자 `\`, `%`, `_`는 `\`로 이스케이프한다. 기관 유형 값처럼 밑줄이 들어간 이름도 글자 그대로 찾는다.
   - 키워드는 최대 100자, 단어는 최대 10개다. 넘으면 잘못된 입력이다. 이름 컬럼이 255자이고 실제 검색어로는 충분하며, 조건 수가 끝없이 늘지 않게 막는다.
   - 대안: MySQL FULLTEXT ngram(토큰 크기보다 짧은 단어를 찾지 못해 "포함" 계약과 다름), 메모리 필터링(사실상 캐시 도입이라 `DEC-FRESHNESS` 미정 항목과 충돌).
3. **필터 값은 원문 문자열 그대로 받아 `IN`으로 비교한다.** 파라미터를 반복해 여러 값을 받는다(`field=보육·교육&field=보건·의료`). API 계약으로는 쉼표 구분을 두지 않는다. 다만 값이 하나일 때 Spring 기본 변환이 쉼표로 나누는 동작은 막지 않는다(2026-10-04 사용자 결정). 현재 값에 쉼표가 없어 결과가 틀리지 않는다. 쉼표가 든 값이 생기면 그 값을 혼자 보낼 때만 나뉘어 빈 목록이 되므로, 그때 필터 파라미터를 원본 값으로 받도록 바꾼다. 빈 문자열 값은 무시한다. 모르는 값은 오류가 아니라 일치하는 혜택이 없는 것이다.
   - 대안: 영문 코드 enum 매핑. 모르는 값을 400으로 거절할 수 있지만, 출처에 새 값이 생기면 코드를 고치기 전까지 필터할 수 없고 원문 그대로 두는 원칙([0008](../decisions/0008-benefit-normalization.md))과 어긋난다.
4. **페이지 번호는 1부터 센다.** `page`는 1 이상, `size`는 1 이상 100 이하이며 기본값은 1과 20이다. 범위 밖이거나 정수가 아니면 잘못된 입력이다. 마지막 쪽을 넘은 번호는 빈 목록이며, 이때도 결과 건수는 조건에 맞는 전체 건수다. `page` 상한은 두지 않고, 시작 위치가 `int` 범위를 넘는 쪽은 건수만 세고 빈 목록으로 응답한다. 쿼리 파라미터는 요청 DTO(`BenefitSearchRequest`, `@Valid`)로 받아 Bean Validation 제약을 달고, 서비스에서 `PageRequest.of(page - 1, size, sort)`를 만든다.
   - 처음에는 `@RequestParam` 5개로 받았으나 2026-10-05에 요청 DTO로 바꿨다(사용자 결정). 시그니처가 단순해지고, P1의 운영 기관 지역·접수 상태 필터를 필드 추가로 받을 수 있다. 요청 DTO는 받은 값과 범위 검증, `BenefitSearchCondition`은 검색에 쓸 정리된 조건으로 역할을 나눈다.
5. **정렬은 `name ASC, service_id ASC`로 고정한다.** 정렬 파라미터는 받지 않는다. 이름이 `NULL`인 혜택은 현재 0건이며, 생기면 MySQL 기본대로 맨 앞에 온다.
6. **결과 건수는 `Page`의 건수 쿼리로 구하고, 응답은 자체 레코드로 내보낸다.** `PageImpl`을 그대로 직렬화하지 않는다(Spring Data가 JSON 구조를 보장하지 않음). 응답 최상위는 `totalCount`, `page`, `size`, `items`다. `DEC-SEARCH`에 따라 `totalCount`는 나중에 빼거나 다음 쪽 여부로 바꿀 수 있는 항목으로 두며, 클라이언트가 `items`를 해석할 때 `totalCount`에 의존하지 않게 문서화한다.
7. **목록 항목에는 원문 값을 그대로 담고, 없는 값은 `null`로 보낸다.** 항목 필드: `serviceId`, `name`, `summary`(`purpose_summary`), `agencyName`, `agencyType`, `field`(`service_field`), `applyPeriodText`, `applyPeriodType`, `applyPeriodNeedsReview`, `lastSeenAt`. 기본 문구로 채우지 않는다. 목록에서 사라진 혜택도 숨기지 않으며 `lastSeenAt`으로 구분한다.
8. **오류 응답은 자체 형식으로 한다.** 결정 문서 0009.
   - 성공 응답은 데이터를 그대로 보내고, 실패할 때만 HTTP 상태 코드와 아래 본문을 보낸다. 성공·실패 공통 envelope은 쓰지 않는다.
     ```json
     {
       "code": "INVALID_INPUT",
       "message": "요청 값이 올바르지 않습니다.",
       "errors": [
         { "field": "size", "value": "200", "reason": "1 이상 100 이하여야 합니다." }
       ]
     }
     ```
   - `code`는 클라이언트가 분기에 쓰는 고정 문자열이고 `message`는 사람이 읽는 문구다. `errors`는 항상 배열이며 입력 항목 오류가 없으면 비어 있다.
   - 이번에 쓰는 코드: `INVALID_INPUT`(400), `NOT_FOUND`(404), `INTERNAL_ERROR`(500). 그 밖의 Spring MVC 예외(405 등)는 `REQUEST_FAILED`와 해당 상태 코드로 보낸다. 500은 내부 메시지를 본문에 넣지 않고 로그에만 남긴다.
   - 대안: Spring `ProblemDetail`(RFC 9457). 코드가 적지만, 사용자가 직접 설계해 보기로 했다(2026-10-04).
   - `ResponseEntityExceptionHandler`를 상속해 Spring MVC 예외 전체를 같은 형식으로 바꾼다.
9. **API 응답 시각은 `+09:00` 오프셋을 붙인 ISO-8601 문자열로 보낸다.** 예: `2026-10-04T04:00:12.123456+09:00`. 결정 문서 0010. 엔티티는 `LocalDateTime` 그대로 두고 응답 DTO로 바꿀 때 `Asia/Seoul` 기준 `OffsetDateTime`으로 변환한다. 대안인 오프셋 없는 문자열은 변환이 없지만 클라이언트가 한국 시각이라는 것을 따로 알아야 한다.

## 단계별 구현 계획

1. **공통 API 지원** `kr.govbenefit.common.api`
   - `ApiTimes.toOffset(LocalDateTime)`: `null`이면 `null`, 아니면 `Asia/Seoul` 기준 `OffsetDateTime`.
   - `ErrorCode` enum: `INVALID_INPUT`, `NOT_FOUND`, `INTERNAL_ERROR`, `REQUEST_FAILED`. 필드 `message`. 상태 코드에서 코드를 고르는 `from(HttpStatusCode)`(400 → `INVALID_INPUT`, 404 → `NOT_FOUND`, 5xx → `INTERNAL_ERROR`, 그 밖 → `REQUEST_FAILED`).
   - `ApiErrorResponse` record(`String code`, `String message`, `List<InputError> errors`)와 중첩 record `InputError`(`String field`, `String value`, `String reason`). Spring의 `org.springframework.web.ErrorResponse`, `FieldError`와 이름이 겹치지 않게 했다.
   - `InvalidInputException`(`field`, `value`, `reason`): Bean Validation 제약으로 표현하지 않은 입력 검증의 실패. 핸들러가 `INVALID_INPUT`으로 바꾼다.
   - `GlobalExceptionHandler`(`@RestControllerAdvice`, `ResponseEntityExceptionHandler` 상속):
     - `handleMethodArgumentNotValid`: 요청 DTO의 바인딩·검증 실패를 `INVALID_INPUT`과 항목별 `errors`로 바꾼다. `page=abc` 같은 변환 실패(`FieldError.isBindingFailure()`)는 Spring 기본 문구(영어) 대신 `형식이 올바르지 않습니다.`로 바꾼다.
     - `@RequestParam`·`@PathVariable`을 쓰는 곳이 없어 `handleHandlerMethodValidationException`·`handleTypeMismatch`는 두지 않는다. 해당 예외는 `handleExceptionInternal`이 `errors` 없는 `INVALID_INPUT`으로 바꾼다. 항목 오류가 필요한 API가 생기면 그때 추가한다.
     - `handleExceptionInternal`: 위에서 다루지 않은 Spring MVC 예외(404 `NoResourceFoundException`, 405 등)를 `ErrorCode.from(status)`로 `ApiErrorResponse`로 바꾼다.
     - `@ExceptionHandler(Exception.class)`: `INTERNAL_ERROR`. 예외는 로그에 남긴다.
2. **검색 조건** `kr.govbenefit.benefit.dto.BenefitSearchCondition` record(`List<String> keywords`, `List<String> fields`, `List<String> agencyTypes`)
   - `static of(String keyword, List<String> fields, List<String> agencyTypes)`: 키워드를 유니코드 공백으로 나누고 중복을 뺀다. 필터에서 `null`·빈 문자열을 뺀다.
   - 단어 수 상한(10개)을 넘으면 `of`가 `InvalidInputException("keyword", ...)`을 던진다. 키워드 길이(100자)는 요청 DTO의 `@Size`로 검사한다.
3. **리포지토리**
   - `BenefitRepository`에 `JpaSpecificationExecutor<Benefit>`를 더한다.
   - `kr.govbenefit.benefit.repository.BenefitSpecifications`: `nameContainsAll(List<String> words)`, `serviceFieldIn(List<String>)`, `agencyTypeIn(List<String>)`. 빈 목록이면 조건 없음. `escapeLike(String)`로 `\`, `%`, `_`를 이스케이프하고 `cb.like(..., '\\')`로 비교한다.
4. **서비스** `BenefitQueryService.search(BenefitSearchCondition condition, int page, int size)`
   - 세 조건을 AND로 묶고 `PageRequest.of(page - 1, size, Sort.by(asc("name"), asc("serviceId")))`로 `findAll`한다. 시작 위치가 `int` 범위를 넘으면 `count(spec)`만 하고 빈 목록을 돌려준다. 클래스의 `@Transactional(readOnly = true)` 안에서 목록·건수 쿼리가 함께 실행된다.
   - `BenefitSearchResponse`로 바꿔 돌려준다.
5. **응답 DTO** `kr.govbenefit.benefit.dto`
   - `BenefitSearchResponse` record(`long totalCount`, `int page`, `int size`, `List<BenefitSummary> items`).
   - `BenefitSummary` record: 설계 7의 필드. `static from(Benefit)`에서 `lastSeenAt`을 `ApiTimes.toOffset`으로 바꾼다.
6. **요청 DTO와 컨트롤러**
   - `kr.govbenefit.benefit.dto.BenefitSearchRequest` record: `@Size(max = 100) String keyword`, `@BindParam("field") List<String> fields`, `@BindParam("agencyType") List<String> agencyTypes`, `@Min(1) Integer page`, `@Min(1) @Max(100) Integer size`. compact constructor에서 `page`·`size`가 null이면 1·20으로 채운다. `toCondition()`이 `BenefitSearchCondition.of`를 부른다. 제약 문구는 요청 언어에 따라 바뀌지 않게 `message`에 한국어로 적는다(결정 0009).
   - `kr.govbenefit.benefit.controller.BenefitController`: `GET /api/benefits`에서 `@Valid BenefitSearchRequest`를 받아 `BenefitQueryService.search(request.toCondition(), request.page(), request.size())`를 부른다.
7. **테스트**
   - `BenefitSearchConditionTest`(단위): 공백·탭·전각 공백 분리, 앞뒤 공백, 중복 단어, 빈 키워드·`null`, 필터의 빈 값 제거.
   - `BenefitSpecificationsTest`(단위): `escapeLike`가 `\`, `%`, `_`를 이스케이프한다.
   - `BenefitSearchApiTest`(새 클래스, `@SpringBootTest`, MockMvc, Testcontainers, `Gov24StubServer`, `gov24.schedule.enabled=false`): `setUp`에서 `benefit_detail`, `benefit`을 비우고 `BenefitRepository.saveAll`로 fixture를 넣는다. 사라진 혜택은 JDBC로 `last_seen_at`을 과거로 바꾼다. 시나리오는 완료 조건 매핑에 적는다.
   - `GlobalExceptionHandlerTest`는 따로 두지 않고 `BenefitSearchApiTest`의 오류 시나리오와 404 시나리오로 확인한다.
8. **결정 문서**
   - `docs/decisions/0009-api-error-response.md`: 설계 8. 영향에 이후 모든 API(#30부터)가 같은 형식과 코드 목록을 쓰고, 코드를 추가할 때 `ErrorCode`와 이 문서를 함께 고친다는 것을 적는다.
   - `docs/decisions/0010-api-time-format.md`: 설계 9. 영향에 #30의 출처 수정 시각·수집 시각·기준 시각이 같은 형식을 쓴다는 것을 적는다.
   - `docs/decisions/README.md` 목록 표에 두 문서를 추가한다.

## 완료 조건 매핑

`BenefitSearchApiTest` fixture는 같은 이름 혜택 5건(`같은 이름 지원`, 서비스 ID가 이름 순서와 다르게 섞임), 분야·기관 유형이 겹치고 엇갈리는 혜택, 이름에 `%`·`_`가 들어간 혜택, 요약·기관명·신청기한이 `NULL`인 혜택, `last_seen_at`이 과거인 혜택을 포함한다.

- [ ] 로그인 없이 검색할 수 있다. → 6단계. 인증 정보 없이 `GET /api/benefits`가 200이다.
- [ ] 키워드를 공백으로 나눈 단어를 이름에 모두 포함한 혜택만 나온다. 빈 키워드·공백만 있는 키워드는 전체 목록이다. → 설계 2, 2·3단계. `청년 월세`와 `월세 청년`이 같은 결과이고, 한 단어만 포함한 혜택은 빠진다. 전각 공백으로 나눈 키워드도 같다. `keyword=`, `keyword=%20%20`, 생략이 모두 전체 건수다. `%`·`_` 키워드는 그 글자가 들어간 혜택만 찾는다. 101자 키워드와 11개 단어는 400 `INVALID_INPUT`이다.
- [ ] 분야·기관 유형 필터는 한 필터 안의 여러 값을 OR, 필터 사이와 키워드를 AND로 적용한다. → 설계 3, 3단계. `field` 두 값이면 둘 중 하나인 혜택, `field`와 `agencyType`을 함께 주면 둘 다 맞는 혜택, 여기에 키워드를 더하면 세 조건을 모두 만족하는 혜택만 나온다. 모르는 필터 값은 빈 목록이다.
- [ ] 이름순, 동률은 서비스 ID 순이다. 같은 이름 혜택이 페이지 경계에 걸쳐도 데이터가 그대로면 페이지 이동 시 중복·누락이 없다. → 설계 5, 4단계. 전체 목록이 `name, service_id` 순이다. `size=2`로 모든 쪽을 넘겨 모은 서비스 ID가 한 번에 받은 목록과 같고 중복이 없다. 같은 쪽을 두 번 요청하면 결과가 같다.
- [ ] 기본 20건, 최대 100건이다. 범위를 벗어난 크기·잘못된 페이지 번호는 잘못된 입력, 결과 없음과 마지막 쪽을 넘은 번호는 빈 목록으로 응답한다. → 설계 4·8, 1·6단계. 파라미터 생략 시 `page=1`, `size=20`이고 항목이 20건 이하다. `size=100`은 200, `size=0`·`size=101`·`size=abc`·`page=0`·`page=-1`·`page=abc`·`page=99999999999`(int 초과)는 400이고 본문이 `code: INVALID_INPUT`과 해당 항목의 `errors`다. `page=abc`의 오류는 받은 값 `abc`와 `형식이 올바르지 않습니다.`를 담는다. `page=0&size=101`에 101자 키워드를 더하면 `errors`에 세 항목이 모두 담긴다. 일치하는 혜택이 없는 키워드와 마지막 쪽을 넘은 `page`(`page=2147483647`, `size=100` 포함)는 200과 빈 `items`다.
- [ ] 응답에 조건에 맞는 결과 건수가 있다. → 설계 6, 4·5단계. 조건마다 `totalCount`가 fixture에서 직접 센 건수와 같다. 마지막 쪽을 넘은 번호에서도 같다.
- [ ] 항목에 서비스 ID·이름·요약·기관명·기관 유형·분야·신청기한 원문과 분류 상태·목록 마지막 확인 시각이 있고, 원문에 없는 값은 채우지 않는다. → 설계 7·9, 5단계. 항목 필드가 설계 7과 같고 값이 DB와 같다. `lastSeenAt`이 `+09:00`이 붙은 문자열이다. `NULL`인 요약·기관명·신청기한은 JSON `null`이다.
- [ ] 목록에서 사라진 혜택도 결과에 나오며 마지막 확인 시각으로 구분할 수 있다. → 설계 7. `last_seen_at`을 과거로 바꾼 혜택이 결과에 나오고 `lastSeenAt`이 그 과거 시각이다.
- [ ] 검색 요청은 공공 API를 호출하지 않는다. → 4단계. 위 시나리오의 요청 전후로 `stub.requestCount()`가 0이다.

## 검증 명령

- `backend/`에서 `./gradlew test`
- 로컬 확인(기본 테스트에 포함하지 않음): `SPRING_PROFILES_ACTIVE=local ./gradlew bootRun` 뒤
  ```sh
  curl -s 'http://localhost:8080/api/benefits?keyword=%EC%B2%AD%EB%85%84%20%EC%9B%94%EC%84%B8&size=5'
  curl -s 'http://localhost:8080/api/benefits?field=%EB%B3%B4%EC%9C%A1%C2%B7%EA%B5%90%EC%9C%A1&agencyType=%EC%8B%9C%EA%B5%B0%EA%B5%AC'
  curl -s -i 'http://localhost:8080/api/benefits?size=101'
  ```
  응답 건수를 아래 SQL과 비교한다.
  ```sql
  SELECT COUNT(*) FROM benefit WHERE name LIKE '%청년%' AND name LIKE '%월세%';
  SELECT COUNT(*) FROM benefit WHERE service_field = '보육·교육' AND agency_type = '시군구';
  ```

## 결정 필요

없음
