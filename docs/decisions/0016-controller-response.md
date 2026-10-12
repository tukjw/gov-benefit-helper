# 0016. 컨트롤러 응답 방식

- 상태: 채택
- 날짜: 2026-10-12
- 출처: #40, [플랜](../plans/40-benefit-api-response-entity.md)

## 배경

#38의 계정 API는 컨트롤러가 `ResponseEntity`로 응답하고 상태 코드도 컨트롤러에서 정했다. 그 전에 만든 혜택 검색·상세 API(#29·#30)는 응답 DTO를 바로 반환했다. 컨트롤러의 응답 방식이 두 가지로 섞여 있어 새 API가 어느 쪽을 따를지 정해야 했다.

## 결정

API 컨트롤러는 `ResponseEntity<응답 DTO>`를 반환하고 성공 상태 코드를 메서드 안에서 정한다. 예: `ResponseEntity.ok(...)`, `ResponseEntity.status(HttpStatus.CREATED).body(...)`.

- 컨트롤러에 `@ResponseStatus`를 쓰지 않는다.
- 본문 없는 성공 응답은 `ResponseEntity<Void>`를 반환하고 `ResponseEntity.noContent().build()`(204)로 만든다.

## 근거

- 200이 아닌 성공 응답(가입 201)이 있다. 상태 코드가 반환문에 있으면 메서드만 읽어도 응답을 알 수 있고, DTO 반환과 `@ResponseStatus`가 섞이지 않는다.
- 헤더나 상태를 나중에 더할 때 반환 타입을 바꾸지 않아도 된다.
- `GlobalExceptionHandler`도 오류 응답을 `ResponseEntity`로 만든다. 성공과 오류의 응답 방식이 같아진다.
- springdoc은 `ResponseEntity<T>`의 `T`로 응답 스키마를 만든다. 혜택 API를 바꾼 뒤에도 `ApiDocsTest`의 응답 코드·스키마 검증이 그대로 통과했다.
- 대안: DTO를 바로 반환하고 200이 아닐 때만 `@ResponseStatus`를 단다. 코드는 짧지만 상태 코드가 어노테이션과 기본값으로 나뉜다.

## 적용 위치

- `backend/src/main/java/kr/govbenefit/account/controller/AuthController.java`
- `backend/src/main/java/kr/govbenefit/account/controller/MeController.java`
- `backend/src/main/java/kr/govbenefit/benefit/controller/BenefitController.java`

## 영향

- 새 API 컨트롤러는 이 방식으로 응답한다.
- 성공 응답의 상태 코드는 명세의 `@ApiResponse`와 맞춘다([0012](0012-api-spec-docs.md)).
- 성공 응답의 본문은 응답 DTO 그대로이며 envelope으로 감싸지 않는다([0009](0009-api-error-response.md)).
