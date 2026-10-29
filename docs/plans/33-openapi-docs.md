status: done

# 구현 계획 — #33 공개 API의 OpenAPI 명세와 문서 화면 제공

브랜치: `chore/33-openapi-docs` (develop `bc6c61f` 기준)

## 목표 및 범위

- 목표: 검색 API(#29)와 상세 API(#30)의 계약을 OpenAPI 명세(JSON)와 브라우저 문서 화면으로 제공한다. 응답 구조만으로 알 수 없는 의미(결정 0009·0010·0011, `DEC-FRESHNESS`, `totalCount`)를 명세 설명에 적는다. 문서는 `local` 프로필에서만 연다.
- 포함:
  - springdoc-openapi 의존성과 Swagger UI.
  - `local` 전용 공개 설정과 정적 리소스 기본 매핑 끄기.
  - 검색·상세 API의 파라미터·성공 응답·오류 응답 명세와 설명.
  - 명세 내용 테스트.
  - 결정 문서 0012(API 명세 문서화 방식).
- 제외:
  - 운영 환경의 문서 공개와 문서 접근 인증.
  - `field`·`agencyType` 허용값의 서버 검증. 명세에 `enum`으로 적지 않고 설명만 적는다(설계 5). 서버 검증이 필요하면 새 이슈로 다룬다.
  - 명세 파일의 저장소 커밋, 클라이언트 코드 생성, API 버전 표기(`DEC-APP`).
  - `REQUEST_FAILED`(405 등) 응답의 API별 명세. 코드 목록은 오류 스키마 설명에만 둔다.

## 확인된 사실과 제약

2026-10-06에 의존성과 임시 테스트를 넣어 `/v3/api-docs`를 받아 확인했다. 임시 변경은 되돌렸다.

- `org.springdoc:springdoc-openapi-starter-webmvc-ui`의 최신 버전은 `3.1.1`이고, `spring-boot-starter-parent:4.1.0`을 부모로 빌드됐다(Maven Central pom). 현재 Boot `4.1.1`에서 기동하고 OpenAPI `3.1.0` 명세를 낸다.
- springdoc은 기본값이 켜짐이다(`/v3/api-docs` 200, `/swagger-ui.html` → `/swagger-ui/index.html` 302).
- 검색 파라미터
  - 어노테이션 없이 두면 `BenefitSearchRequest`가 `request`라는 필수 쿼리 파라미터 하나로 나온다. `@ParameterObject`를 붙이면 필드별 파라미터로 펼쳐진다.
  - 펼친 파라미터에는 Bean Validation 제약이 반영된다: `keyword` `maxLength: 100`(`minLength: 0`도 함께), `page` `minimum: 1`, `size` `minimum: 1`·`maximum: 100`. 기본값(`page` 1, `size` 20)은 나오지 않는다.
  - `@BindParam("field")`은 반영되지 않아 이름이 `fields`·`agencyTypes`로 나온다. 레코드 컴포넌트에 `@Parameter(name = "field")`를 붙이면 `field`로 나온다(확인함). `agencyType`도 같은 방식이다.
  - 키워드 단어 수 제한(10개)은 `InvalidInputException`이라 명세에 나오지 않는다.
- 응답
  - 두 API 모두 `200`만 나오고 `ApiErrorResponse`는 `components.schemas`에 없다. `GlobalExceptionHandler`의 핸들러가 `@ResponseStatus` 없이 `ResponseEntity`를 반환하므로 springdoc이 공통 오류 응답을 자동으로 붙이지 않는다. 오류 응답은 직접 적어야 한다.
  - `OffsetDateTime`은 `type: string`, `format: date-time`으로 나온다. 예시 값은 직접 넣어야 한다.
- 경로 공개(`springdoc.api-docs.enabled`, `springdoc.swagger-ui.enabled`)
  - 둘 다 `false`면 `/v3/api-docs`, `/swagger-ui.html`, `/swagger-ui/index.html`, `/v3/api-docs/swagger-config`가 0009 형식의 404다. 하지만 Spring Boot 기본 정적 매핑 때문에 `/webjars/swagger-ui/index.html`이 200이다(명세를 불러오지 않는 빈 Swagger UI).
  - `spring.web.resources.add-mappings: false`를 더하면 `/webjars/swagger-ui/index.html`도 404다.
  - `add-mappings: false`에서 springdoc을 켜도 `/swagger-ui.html` 302 → `/swagger-ui/index.html` 200, `/swagger-ui/swagger-initializer.js` 200, `/v3/api-docs/swagger-config` 200이다. springdoc이 자체 경로로 화면을 제공한다.
- `backend/src/main/resources`에는 설정 파일과 `db/migration`만 있고 정적 파일이 없다. 정적 매핑을 꺼도 잃는 경로가 없다.
- `TestcontainersConfiguration`의 MySQL은 `@ServiceConnection`이라 `@ActiveProfiles("local")`로 띄워도 테스트 컨테이너에 연결된다. `application-local.yaml`은 `gov24.schedule.enabled: false`다.
- `BenefitSearchApiTest`가 `@SpringBootTest`·MockMvc·Testcontainers·`Gov24StubServer` 구성의 예시다.
- Jackson 설정을 바꾸지 않아 `null` 필드도 응답에 나온다. `ApplyPeriodType`은 enum이라 명세에 허용값이 자동으로 나온다.

## 설계 결정

1. **springdoc-openapi로 코드에서 명세를 만들고 Swagger UI로 보여준다.** 결정 문서 0012.
   - 대안: `openapi.yaml`을 직접 쓰는 명세 우선 방식. 운영 코드가 깨끗하지만 코드와 명세가 어긋날 수 있어 응답 검증 테스트 도구가 더 필요하다.
   - 대안: Spring REST Docs + `restdocs-api-spec`. 테스트를 통과한 내용만 명세가 되지만, 플러그인의 Boot 4 호환이 불확실하고 빌드 산출물을 정적 리소스로 옮기는 단계가 필요하다.
   - 화면은 Swagger UI다. 완료 조건의 "화면에서 요청을 보내 응답을 본다"를 기본 기능(try-it-out)으로 충족한다. Redoc은 요청 기능이 없다.
2. **설명은 DTO와 컨트롤러에 어노테이션(`@Schema`, `@Parameter`, `@Operation`, `@ApiResponse`)으로 직접 단다.** 클라이언트가 알아야 할 의미는 어노테이션 설명에, 구현 메모는 Javadoc에 둔다. 겹치는 문장이 생기는 것은 감수한다.
   - 대안: `OpenApiCustomizer` 하나에서 필드 이름 문자열로 설명을 채움. DTO가 그대로지만 필드 이름을 바꿀 때 조용히 어긋난다.
   - 예외: 모든 API에 공통인 500 응답은 `OpenApiCustomizer`로 한 곳에서 붙인다(설계 4).
3. **`local` 프로필에서만 문서를 연다.** 2026-10-06 사용자 결정.
   - `application.yaml`에 `springdoc.api-docs.enabled: false`, `springdoc.swagger-ui.enabled: false`를 두고, `application-local.yaml`에서만 `true`로 켠다.
   - `application.yaml`에 `spring.web.resources.add-mappings: false`를 둔다. API 전용 서버라 정적 파일이 없고, 비`local`에서 `/webjars/swagger-ui/index.html`이 열리지 않게 한다.
     - 문서 화면의 원래 경로는 springdoc의 `/swagger-ui/**`다. springdoc은 화면 파일을 `org.webjars:swagger-ui` jar에서 읽는데, Spring Boot 기본 정적 매핑이 같은 jar의 `META-INF/resources/webjars/**`를 `/webjars/**`로 springdoc 설정과 무관하게 공개한다. 이 경로의 화면은 우리 명세가 아니라 Swagger 기본 예제 주소를 가리킨다.
   - 대안: `@Profile("local")` 설정 클래스. springdoc 자동 설정을 끄는 수단이 결국 속성이라 이중 관리가 된다.
4. **오류 응답은 결정 0009 스키마(`ApiErrorResponse`)로 명시한다.**
   - 검색: `400`. 상세: `404`. 컨트롤러 메서드에 `@ApiResponse`로 적는다.
   - 모든 API에 `500`(`INTERNAL_ERROR`)을 붙인다. 2026-10-06 사용자 결정. `GlobalExceptionHandler`가 실제로 0009 형식의 500을 낸다.
   - `errors`는 배열이고 항상 있다(`required`). `code`에는 `ErrorCode` 이름을 허용값으로 적는다.
5. **`field`·`agencyType`은 문자열 배열로 두고 허용값은 설명으로만 적는다.** 2026-10-06 사용자 결정. 서버가 값을 검증하지 않고 원문과 그대로 비교하므로 `enum`으로 적으면 명세가 동작과 다르고, 원문 값이 바뀌면 낡는다. 설명에는 "원문 값과 같은 것만 찾는다, 검증하지 않아 없는 값은 결과가 0건이고 빈 값은 무시한다, 파라미터를 반복하면 OR다"와 예시 하나를 적는다. 비교는 DB 정렬 규칙(0001)을 따르므로 "정확히 일치"라고 쓰지 않는다.
6. **시각 필드에는 `+09:00` 예시를 넣는다.** 결정 0010의 예(`2026-10-04T04:00:12.345+09:00`)를 쓴다. `format: date-time`은 자동으로 나온다.

## 단계별 구현 계획

1. **의존성** `backend/build.gradle`
   - `implementation 'org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.1'`
2. **설정**
   - `application.yaml`: `springdoc.api-docs.enabled: false`, `springdoc.swagger-ui.enabled: false`, `spring.web.resources.add-mappings: false`. 주석에 `local`에서만 연다는 것과 정적 매핑을 끈 이유(정적 파일 없음, webjars 노출)를 적는다.
   - `application-local.yaml`: `springdoc.api-docs.enabled: true`, `springdoc.swagger-ui.enabled: true`.
   - 구현하며 더함: `application.yaml`에 `springdoc.default-produces-media-type: application/json`. 지정하지 않으면 성공 응답의 미디어 타입이 `*/*`로 나온다.
3. **공통 명세 설정** `kr.govbenefit.common.api.OpenApiConfig`(새 클래스, `@Configuration`)
   - `OpenAPI openApi()`: `info`의 제목·설명. 설명에 공통 규칙을 적는다: 실패만 0009 형식으로 감싼다, 시각은 `+09:00` 오프셋(0010), 값이 없으면 `null`이고 기본 문구로 채우지 않는다(‘정보 없음’은 클라이언트가 표시).
   - `OpenApiCustomizer internalErrorResponse()`: 모든 operation에 `500` 응답(`ApiErrorResponse` 참조)을 더한다. `ApiErrorResponse` 스키마가 components에 없으면 등록한다.
   - 구현하며 바꿈: 처음에는 항상 `ModelConverters`로 다시 읽어 등록했더니 springdoc이 만든 스키마를 덮어써 `type: object`가 빠졌다. 없을 때만 등록한다.
4. **오류 스키마** `ApiErrorResponse`, `ApiErrorResponse.InputError`
   - `code`: `@Schema(allowableValues = {"INVALID_INPUT", "NOT_FOUND", "INTERNAL_ERROR", "REQUEST_FAILED"}, requiredMode = REQUIRED)`, 클라이언트는 이 값으로 분기한다는 설명.
   - `message`: 사람이 읽는 문구, 분기에 쓰지 않는다.
   - `errors`: `@ArraySchema(..., schema = @Schema(requiredMode = REQUIRED))` 또는 그와 같은 표현으로 항상 배열이고 항목 오류가 없으면 빈 배열이라는 설명.
   - `InputError`: `field`(요청 파라미터 이름), `value`(받은 값, 없었으면 `null`), `reason`.
   - 구현하며 바꿈: 필수 항목은 레코드에 `@Schema(requiredProperties = {"code", "message", "errors"})`로 한 번에 적었다.
5. **컨트롤러** `BenefitController`
   - 클래스: `@Tag(name = "혜택")`.
   - `search`: `@Operation(summary, description)`. 설명에 검색 규칙(DEC-SEARCH: 키워드 단어는 모두 이름에 있어야 함, 한 필터 안은 OR, 필터 사이는 AND)과 마지막 쪽을 넘으면 `items`가 빈 배열이라는 점. 파라미터에 `@ParameterObject`. `@ApiResponse(responseCode = "400", content = ApiErrorResponse)`, 400 예시(`size=200`).
   - `detail`: `@Operation(summary, description)`, `serviceId`에 `@Parameter(description, example)`. `@ApiResponse(responseCode = "404", content = ApiErrorResponse)`, 설명에 "없는 서비스 ID는 404, 상세 미수집 혜택은 `detail`이 `null`인 200".
   - 구현하며 바꿈: `@Parameter(example = "654000000031")`은 숫자 예시로 나와 `schema = @Schema(type = "string", example = ...)`로 적었다.
6. **검색 요청** `BenefitSearchRequest`
   - `keyword`: 설명(공백으로 나눈 단어 최대 10개, 모두 이름에 포함, 100자까지).
   - `fields`: `@Parameter(name = "field", description = 설계 5, example)`. `agencyTypes`: `@Parameter(name = "agencyType", ...)`.
   - `page`: 1부터, 기본 1. `size`: 1~100, 기본 20. `@Parameter(schema = @Schema(defaultValue = ...))`.
   - 구현하며 바꿈: `@Schema(defaultValue)`만 주면 타입이 `string`으로 바뀌어 `implementation = Integer.class`를 함께 적었다.
7. **검색 응답** `BenefitSearchResponse`, `BenefitSummary`
   - `totalCount`: "조건에 맞는 전체 건수. 규모가 커지면 빠지거나 다음 쪽 여부로 바뀔 수 있으니 의존하지 않는다(DEC-SEARCH)."
   - `BenefitSummary`: `applyPeriodNeedsReview`가 `true`면 ‘확인 필요’로 원문(`applyPeriodText`)과 함께 표시, `applyPeriodType`·`applyPeriodNeedsReview`는 정규화 전 행이면 `null`. `lastSeenAt` 의미와 `+09:00` 예시.
8. **상세 응답** `BenefitDetailResponse`, `ApplyPeriod`, `Detail`, `Freshness`
   - 응답 설명: 최상위는 목록, `detail`은 상세에서 받은 값. 필드가 `null`이면 원문에 값이 없다(‘정보 없음’은 클라이언트가 표시).
   - `detail`: `null`이면 상세 미수집. 필드가 `null`인 것(정보 없음)과 구분한다.
   - `applyPeriod.needsReview`: `true`면 ‘확인 필요’, 원문 `text`와 함께 보여준다. 정규화 전 행이면 `null`.
   - `detail.onlineApplyUrl`: 링크로 쓸 수 있게 정리한 주소(0011). `null`이면 링크 대신 접수 방법(`detail.applyMethod`)을 보여준다. 상세 미수집이면 `applyMethodType`·`receptionAgency`. `onlineApplyUrlText`는 원문.
   - `sourceModifiedAt`: 출처 수정 시각, 원문에 없으면 `null`이고 수집 시각으로 채우지 않는다. `lastSeenAt`: 목록에서 마지막으로 확인한 시각, 출처 수정 시각이나 상세 수집 시각이 아니다. 둘 다 `+09:00` 예시.
   - `freshness.stale`: 기준 시각에서 48시간이 지났거나 목록을 갱신한 수집 실행이 없으면 `true`. `freshness.referenceAt`: 목록을 갱신한 마지막 수집 실행(`SUCCEEDED`·`PARTIAL`)의 종료 시각, 모든 혜택에 같고, 그런 실행이 없으면 `null`이며 이때 `stale`은 `true`.
   - `guideUrl`: 공식 출처(gov.kr 상세 페이지), 링크로 쓸 수 없으면 `null`.
   - 구현하며 더함: 검색·상세가 함께 쓰는 설명(`needsReview`, `lastSeenAt`)은 패키지 내부 클래스 `BenefitDocs`에, 시각 예시는 `ApiTimes.EXAMPLE`에 둔다.
9. **테스트**
   - `kr.govbenefit.common.api.ApiDocsTest`(새 클래스, `@SpringBootTest`, `@ActiveProfiles("local")`, MockMvc, Testcontainers): `/v3/api-docs`를 받아 JSONPath로 검증한다. 시나리오는 완료 조건 매핑에 적는다. 실제 `application-local.yaml`로 켜지는 것을 함께 확인한다.
   - `kr.govbenefit.common.api.ApiDocsClosedTest`(새 클래스, 프로필 지정 없음): `/v3/api-docs`, `/v3/api-docs/swagger-config`, `/swagger-ui.html`, `/swagger-ui/index.html`, `/webjars/swagger-ui/index.html`이 404다.
   - 공공 API 호출이 없도록 기존 API 테스트처럼 `Gov24StubServer`를 붙이고 `gov24.schedule.enabled=false`로 둔다.
   - 구현하며 바꿈: `ApiDocsTest`는 stub을 붙이고 각 테스트 뒤 요청 수가 0인지 본다(`local`은 정기 수집이 꺼져 있다). `ApiDocsClosedTest`는 문서 경로만 요청하므로 `GovBenefitHelperApplicationTests`처럼 `gov24.schedule.enabled=false`만 둔다.
   - 정적 매핑을 다시 켜면 `ApiDocsClosedTest`의 webjars 경로가 실패하는 것을 확인했다.
10. **문서**
    - `docs/decisions/0012-api-spec-docs.md`: 설계 1~4. 적용 위치에 `build.gradle`, `application.yaml`, `application-local.yaml`, `OpenApiConfig`, `BenefitController`, DTO. 영향에 "새 API는 같은 방식으로 파라미터·성공 응답·오류 응답과 의미를 명세에 적는다", "문서는 `local`에서만 연다", "정적 파일이 필요해지면 정적 매핑 설정을 다시 정한다"를 적는다.
    - `docs/decisions/README.md` 목록 표에 0012를 추가한다.
    - `docs/decisions/0009-api-error-response.md`·`0010-api-time-format.md`: 적용 위치에 명세 스키마(`ApiErrorResponse` 어노테이션, 시각 예시)를 더한다.
    - `CLAUDE.md`(AGENTS.md) "로컬 실행"에 문서 화면 주소(`http://localhost:8080/swagger-ui.html`) 한 줄을 더한다.

## 완료 조건 매핑

- [ ] `local` 프로필로 실행하면 브라우저에서 문서 화면을 열 수 있고, 화면에서 검색·상세 API 요청을 보내 응답을 볼 수 있다. → 설계 1·3, 1·2단계. `ApiDocsTest`에서 `/swagger-ui.html`이 `/swagger-ui/index.html`로 302, `/swagger-ui/index.html`과 `/v3/api-docs/swagger-config`가 200이다. 화면에서 요청 보내기는 검증 명령의 수동 확인으로 본다.
- [ ] 검색 API의 요청 파라미터와 그 제약(필수 여부·허용값·범위), 성공 응답, 400 오류 응답이 명세에 나온다. `totalCount` 설명에는 나중에 빠지거나 바뀔 수 있으니 의존하지 않아야 한다는 안내가 있다. → 설계 4·5, 5~7단계. `/api/benefits` GET의 파라미터 이름이 정확히 `keyword`, `field`, `agencyType`, `page`, `size`이고 모두 `required: false`다. `keyword` `maxLength` 100, `page` `minimum` 1·`default` 1, `size` `minimum` 1·`maximum` 100·`default` 20, `field`·`agencyType`은 `type: array`이고 `enum`이 없다. 응답 `200`이 `BenefitSearchResponse`, `400`이 `ApiErrorResponse`를 참조한다. `totalCount` 설명에 "의존하지" 문구가 있다.
- [ ] 상세 API의 성공 응답과 404 오류 응답이 명세에 나온다. → 5단계. `/api/benefits/{serviceId}` GET의 `serviceId`가 필수 path 파라미터이고 `200`이 `BenefitDetailResponse`, `404`가 `ApiErrorResponse`를 참조한다.
- [ ] 상세 응답 설명에 다음 의미가 드러난다. → 8단계. 각 항목은 리뷰로 확인하고, 테스트는 해당 스키마 속성에 `description`이 있는지만 확인한다(문장 일치까지 검사하면 문구 수정마다 테스트가 깨진다).
  - `detail`이 `null`이면 상세 미수집이고, 필드가 `null`이면 원문에 값이 없다(‘정보 없음’은 클라이언트가 표시).
  - `applyPeriod.needsReview`가 `true`이면 ‘확인 필요’다.
  - 온라인 신청 링크가 `null`이면 링크 대신 접수 방법을 보여준다.
  - 출처 수정 시각과 수집 시각은 서로 다르다.
  - 오래된 정보 여부와 판정 기준 시각의 의미(기준 시각이 `null`인 경우 포함).
- [ ] 오류 응답 스키마가 결정 0009 형식과 같다. `code`·`message`가 있고 `errors`는 항상 배열이다. → 설계 4, 3·4단계. `ApiErrorResponse` 스키마의 `required`에 `code`·`message`·`errors`가 있고, `errors`가 `type: array`이며 항목이 `InputError`(`field`·`value`·`reason`)다. `code`의 허용값이 `ErrorCode.values()`의 이름과 같다. 두 API 모두 `500`이 `ApiErrorResponse`를 참조한다.
- [ ] 시각 필드는 오프셋을 포함한 날짜·시각 형식으로 나오고, 설명이나 예시에 `+09:00` 오프셋이 드러난다. → 설계 6, 7·8단계. `BenefitSummary.lastSeenAt`, `BenefitDetailResponse.sourceModifiedAt`·`lastSeenAt`, `Freshness.referenceAt`이 `format: date-time`이고 `example`이 `+09:00`으로 끝난다.
- [ ] `local`이 아닌 프로필에서는 문서 화면과 명세 경로가 열리지 않는다. → 설계 3, 2단계. `ApiDocsClosedTest`.
- [ ] 위 명세 내용(오류 응답 스키마, 시각 형식, 두 API의 경로·파라미터·응답 코드)이 테스트로 검증된다. → 9단계 `ApiDocsTest`.

## 검증 명령

- `backend/`에서 `./gradlew test`
- 로컬 확인(기본 테스트에 포함하지 않음): `SPRING_PROFILES_ACTIVE=local ./gradlew bootRun` 뒤
  - 브라우저에서 `http://localhost:8080/swagger-ui.html`을 열어 검색(`size=200`으로 400 포함)과 상세(없는 ID로 404 포함) 요청을 보내 본다.
  - `curl -s http://localhost:8080/v3/api-docs | jq '.paths | keys'`
- 비`local` 확인: 프로필 없이 실행하고 `curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/swagger-ui.html`이 404.

## 결정 필요

없음. 허용값 표현, 500 포함, 정적 매핑 끄기는 2026-10-06 사용자가 정했다.
