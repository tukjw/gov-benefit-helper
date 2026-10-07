# 0012. API 명세 문서화 방식

- 상태: 채택
- 날짜: 2026-10-06
- 출처: #33, [플랜](../plans/33-openapi-docs.md)

## 배경

검색(#29)·상세(#30) API를 공개했지만 클라이언트가 알아야 할 계약이 코드와 결정 문서(0009·0010·0011)에만 있었다. 상세 응답의 `null` 의미, 오래된 정보 판정, `totalCount`에 의존하지 말라는 안내처럼 응답 구조만으로는 알 수 없는 내용이 많다. 앱 기술은 정하지 않았지만(`DEC-APP`) 앱을 붙일 때 기준이 될 명세가 필요했다. 이후 공개 API도 같은 방식으로 문서화해야 한다.

## 결정

공개 API의 OpenAPI 명세는 springdoc-openapi로 코드에서 만들고, Swagger UI로 보여준다. 명세(`/v3/api-docs`)와 문서 화면(`/swagger-ui.html`)은 `local` 프로필에서만 연다.

- 클라이언트가 알아야 할 의미는 DTO와 컨트롤러의 어노테이션(`@Schema`, `@Parameter`, `@Operation`, `@ApiResponse`) 설명에 적는다. 구현 메모는 Javadoc에 둔다.
- 오류 응답은 API마다 `@ApiResponse`로 상태 코드와 `ApiErrorResponse` 스키마를 적는다. 500(`INTERNAL_ERROR`)은 `OpenApiConfig`가 모든 API에 붙인다.
- 서버가 검증하지 않는 값은 명세에 `enum`으로 적지 않고 설명으로만 적는다. 명세가 실제 동작과 달라지지 않게 하기 위해서다.
- 시각 필드에는 `+09:00` 예시(`ApiTimes.EXAMPLE`)를 넣는다(0010).
- `application.yaml`은 springdoc을 끄고 정적 리소스 기본 매핑(`spring.web.resources.add-mappings`)도 끈다. `application-local.yaml`에서만 springdoc을 켠다.

## 근거

- 코드에서 만들면 경로·파라미터·응답 구조·Bean Validation 제약이 코드와 어긋나지 않는다. 명세 우선 방식(`openapi.yaml` 직접 작성)은 운영 코드가 깨끗하지만 응답 검증 테스트 도구가 더 필요하다. Spring REST Docs + `restdocs-api-spec`은 테스트를 통과한 내용만 명세가 되지만, 플러그인의 Boot 4 호환이 불확실하고 빌드 산출물을 옮기는 단계가 필요하다.
- Swagger UI는 화면에서 요청을 보내 볼 수 있다(try-it-out). Redoc은 이 기능이 없다.
- 설명을 `OpenApiCustomizer`에서 필드 이름 문자열로 채우는 방식은 DTO를 그대로 두지만, 필드 이름이 바뀌면 조용히 어긋난다.
- `springdoc-openapi-starter-webmvc-ui:3.1.1`은 Spring Boot 4.1.0을 부모로 빌드됐다. springdoc은 기본값이 켜짐이므로 기본 설정에서 끈다.
- springdoc은 화면 파일을 `org.webjars:swagger-ui` jar에서 읽는다. 그런데 Spring Boot 기본 정적 매핑이 같은 jar를 `/webjars/**`로 springdoc 설정과 무관하게 공개한다. 2026-10-06에 확인한 결과, springdoc만 끄면 `/webjars/swagger-ui/index.html`이 200이었다. 이 서버는 API 전용이라 정적 파일이 없으므로 매핑을 꺼도 잃는 경로가 없다. 매핑을 꺼도 `local`의 `/swagger-ui/**`는 springdoc이 직접 제공한다.
- springdoc은 `@BindParam` 이름을 읽지 않고 레코드 컴포넌트 이름(`fields`)을 쓴다. `@Parameter(name = ...)`로 명세의 이름을 맞춘다.
- `GlobalExceptionHandler`의 핸들러는 `@ResponseStatus` 없이 `ResponseEntity`를 반환한다. 그래서 springdoc이 오류 응답을 자동으로 붙이지 않으므로 직접 적는다.

## 적용 위치

- `backend/build.gradle`: `springdoc-openapi-starter-webmvc-ui`
- `backend/src/main/resources/application.yaml`: springdoc 끄기, 정적 매핑 끄기, 성공 응답 미디어 타입(`application/json`)
- `backend/src/main/resources/application-local.yaml`: springdoc 켜기
- `backend/src/main/java/kr/govbenefit/common/api/OpenApiConfig.java`: 명세 정보와 공통 500 응답
- `backend/src/main/java/kr/govbenefit/benefit/controller/BenefitController.java`: 작업 설명과 오류 응답
- `backend/src/main/java/kr/govbenefit/benefit/dto/`, `common/api/ApiErrorResponse.java`: 스키마 설명
- `backend/src/test/java/kr/govbenefit/common/api/ApiDocsTest.java`, `ApiDocsClosedTest.java`

## 영향

- 새 공개 API는 파라미터·성공 응답·오류 응답과 응답 구조만으로 알 수 없는 의미를 같은 방식으로 명세에 적고, `ApiDocsTest`에 경로·파라미터·응답 코드 검증을 더한다.
- 문서를 `local` 밖에서 열려면(운영 공개, 인증 뒤 공개 등) 이 결정을 대체한다.
- 정적 파일을 제공해야 하면 정적 매핑 설정을 다시 정하고, `/webjars/**`가 열리지 않는지 `ApiDocsClosedTest`로 확인한다.
- 요청 객체에 `@BindParam`으로 이름을 바꾼 파라미터는 `@Parameter(name = ...)`도 함께 단다.
