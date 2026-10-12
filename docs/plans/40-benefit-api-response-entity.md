status: done

# 구현 계획 — #40 혜택 검색·상세 API 응답을 ResponseEntity로 반환

브랜치: `refactor/40-benefit-api-response-entity` (develop `390217c` 기준)

## 목표 및 범위

- 목표: 모든 API 컨트롤러가 `ResponseEntity`로 응답하게 맞추고, 새 API가 따를 규칙을 결정 문서로 남긴다.
- 포함:
  - `BenefitController`의 검색·상세 메서드 반환 타입 변경.
  - 결정 문서 0016(컨트롤러 응답 방식)과 결정 목록 갱신.
- 제외:
  - 응답 상태 코드, 본문, API 명세 변경.
  - 201 응답의 `Location` 헤더. 지금 가입 API에 없고 이번 규칙에도 넣지 않는다(2026-10-12 사용자 결정).
  - `GlobalExceptionHandler`. 이미 `ResponseEntity`를 반환한다.

## 확인된 사실과 제약

- API 컨트롤러는 `AuthController`, `MeController`, `BenefitController` 셋이다. 계정 컨트롤러 둘은 `ResponseEntity<응답 DTO>`를 반환하고(#38), `BenefitController`만 DTO를 바로 반환한다.
- `backend/src/main`에 `@ResponseStatus`를 쓰는 곳이 없다.
- springdoc은 `ResponseEntity<T>`의 `T`로 응답 스키마를 만든다.
- 기존 테스트가 완료 조건을 확인한다.
  - `BenefitSearchApiTest`, `BenefitDetailApiTest`: 상태 코드와 본문.
  - `ApiDocsTest`: 검색(`200`·`400`·`500`)·상세(`200`·`404`·`500`) 응답 코드와 `200` 응답 스키마(`BenefitSearchResponse`, `BenefitDetailResponse`).

## 설계 결정

1. **규칙은 결정 문서 0016으로 남긴다.** 2026-10-12 사용자 결정. 다른 이슈의 컨트롤러 코드가 따라야 하는 결정이라 결정 문서 기준에 맞는다.
   - 대안: `AGENTS.md`. 작업·검증 규칙을 두는 곳이고 코드 작성 규칙은 없다.
   - 대안: 0012(API 명세 문서화 방식)에 추가. 채택된 문서는 본문을 고칠 수 없어 대체 문서가 필요하다.
2. **규칙 범위.** 2026-10-12 사용자 결정.
   - 컨트롤러는 `ResponseEntity<응답 DTO>`를 반환하고 성공 상태 코드를 컨트롤러에서 정한다(#38, 2026-10-11 결정).
   - 컨트롤러에 `@ResponseStatus`를 쓰지 않는다.
   - 본문 없는 성공 응답은 `ResponseEntity<Void>`와 `ResponseEntity.noContent().build()`(204)다.

## 단계별 구현 계획

1. **컨트롤러** `backend/src/main/java/kr/govbenefit/benefit/controller/BenefitController.java`
   - `search`: `ResponseEntity<BenefitSearchResponse>`를 반환하고 `ResponseEntity.ok(...)`로 감싼다.
   - `detail`: `ResponseEntity<BenefitDetailResponse>`를 반환하고 `ResponseEntity.ok(...)`로 감싼다.
   - 어노테이션(`@Operation`, `@ApiResponse`, `@Parameter`)은 그대로 둔다.
2. **결정 문서** `docs/decisions/0016-controller-response.md`
   - 설계 결정 1·2를 적는다. 적용 위치는 세 컨트롤러다.
   - `docs/decisions/README.md`의 결정 목록에 0016을 더한다.

## 완료 조건 매핑

- [ ] 혜택 검색·상세 API의 응답 상태 코드와 본문이 바뀌지 않는다. 기존 API 테스트를 고치지 않고 통과한다. → 단계 1, `BenefitSearchApiTest`·`BenefitDetailApiTest`
- [ ] API 명세의 응답 코드와 스키마가 바뀌지 않는다. → 단계 1, `ApiDocsTest`
- [ ] 모든 API 컨트롤러가 같은 방식으로 응답하고, 그 규칙이 문서에 적혀 있다. → 단계 1·2

## 검증 명령

```
cd backend && ./gradlew test
git diff develop -- backend/src/test
```

두 번째 명령의 출력이 비어 있어야 한다.

## 결정 필요

없음
