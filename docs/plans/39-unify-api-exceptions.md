status: in-progress

# 구현 계획 — #39 API 실패 응답을 오류 코드 하나로 던지도록 예외 처리 통일

브랜치: `refactor/39-unify-api-exceptions` (develop `37f3027` 기준)

## 목표 및 범위

- 목표: 서비스는 `ErrorCode`만 정해 실패를 던지고, 응답 상태 코드는 `ErrorCode`에서 한 번만 정한다. 혜택 API(#29·#30)와 계정 API(#38)의 실패 표현 방식을 하나로 맞추고, 새 API가 따를 방식을 결정 0009에 적는다.
- 포함:
  - `ErrorCode`에 상태 코드를 둔다.
  - `ApiException`의 이름을 `BusinessException`으로 바꾸고, `ErrorCode`만 받게 한다.
  - `InvalidInputException`을 `BusinessException`의 하위 클래스로 바꾼다.
  - `NotFoundException`을 없앤다.
  - 기존 실패 다섯 가지(자원 없음, 입력 오류, 이메일 중복, 로그인 실패, 토큰 주인 없음)를 새 방식으로 옮긴다.
  - 보안 필터의 401·404(`ApiAuthenticationEntryPoint`)도 `ErrorCode`의 상태 코드를 쓴다.
  - 결정 0009 갱신.
- 제외:
  - 응답 형식, 상태 코드, `message` 문구, `WWW-Authenticate` 헤더의 변경.
  - Spring MVC 예외의 상태→코드 대응(`ErrorCode.from`)과 `REQUEST_FAILED`·`INTERNAL_ERROR`의 동작 변경.
  - 성공 응답의 상태 코드. 컨트롤러가 `ResponseEntity`로 정한다(결정 [0016](../decisions/0016-controller-response.md)). 예: `AuthController`의 201.
  - 4xx 실패의 로그 정책.
  - 수집 쪽 예외(`Gov24ApiException`). 외부 API 호출 실패이고 응답으로 바뀌지 않는다.

## 확인된 사실과 제약

- 실패를 던지는 곳
  - `BenefitSearchCondition`: `new InvalidInputException("keyword", keyword, ...)`. 단어 수 제한.
  - `BenefitQueryService.getDetail`: `new NotFoundException("혜택이 없습니다. serviceId=...")`.
  - `AccountService`: `new ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHENTICATED)`(`me`), `new ApiException(HttpStatus.CONFLICT, ErrorCode.DUPLICATE_EMAIL)`(`duplicateEmail`), `new ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_CREDENTIALS)`(`invalidCredentials`).
- 상태 코드를 직접 넣는 곳
  - `GlobalExceptionHandler`: `handleInvalidInput`(400), `handleNotFound`(404), `handleApi`(`ex.status()`), `handleUnexpected`(500).
  - `ApiAuthenticationEntryPoint.commence`: 404·401을 `HttpStatus`로 넣는다.
- `GlobalExceptionHandler.handleApi`는 `UNAUTHENTICATED`일 때 `WWW-Authenticate: Bearer error="invalid_token"`을 붙인다. 토큰 주인이 없는 경우다.
- `ErrorCode.from(HttpStatusCode)`는 Spring MVC 예외의 상태 코드를 코드로 바꾼다. 400→`INVALID_INPUT`, 401→`UNAUTHENTICATED`, 404→`NOT_FOUND`, 5xx→`INTERNAL_ERROR`, 그 밖→`REQUEST_FAILED`.
  - `REQUEST_FAILED`(405·406·415 등)와 `INTERNAL_ERROR`(5xx)는 상태 코드가 하나로 정해지지 않는다.
  - 401은 `UNAUTHENTICATED`·`INVALID_CREDENTIALS` 두 코드가 쓰므로, 상태 코드에서 코드를 거꾸로 찾을 수 없다.
- 기존 API 테스트가 다섯 가지 실패의 상태 코드와 `code`를 모두 검증한다.
  - 입력 오류: `BenefitSearchApiTest.키워드는_100자_단어_10개까지다`.
  - 자원 없음: `BenefitDetailApiTest`.
  - 이메일 중복: `SignUpApiTest`(409).
  - 로그인 실패: `LoginApiTest`.
  - 토큰 주인 없음: `AccessTokenAuthTest.토큰_주인의_계정이_없으면_거절한다`(401, `WWW-Authenticate` 포함).
  - 보안 필터 404·`REQUEST_FAILED`(405)는 `AccessTokenAuthTest`·`BenefitSearchApiTest`가 검증한다.
- API 테스트가 아니면서 예외 클래스를 직접 쓰는 테스트
  - `BenefitSearchConditionTest`: `InvalidInputException`과 `error().field()`.
  - `SignUpConcurrencyTest`: `ApiException`의 `status()`·`code()`.
- `ApiDocsTest`는 명세의 `code` 허용값이 `ErrorCode.values()`와 같은지 본다. 상수를 더하거나 빼지 않으므로 영향이 없다.
- `docs/decisions/README.md`는 채택된 결정의 본문을 고치지 않는다고 정한다. 다만 #30·#33·#38은 결정을 바꾸지 않고 덧붙이는 내용(코드 표 행, 적용 위치, 영향)을 0009 본문에 더했다. 이번 변경도 기존 결정("컨트롤러에서 오류 본문을 직접 만들지 않고 예외로 던진다")을 바꾸지 않고 던지는 방법을 덧붙이는 것이며, 이슈가 0009에 적으라고 정했다.

## 설계 결정

1. **상태 코드는 `ErrorCode` 상수에 둔다.** 결정 0009에 덧붙인다.
   - 오류 코드와 상태 코드가 같은 줄에 있어 둘이 어긋날 수 없다. 코드를 추가할 때도 상수 하나만 넣으면 된다.
   - 대안: 핸들러의 `switch` 식으로 대응하기. enum은 HTTP를 모르게 되고 빠진 코드는 컴파일러가 잡는다. 하지만 `ErrorCode.from`이 남아 enum이 HTTP를 아는 것은 그대로이고, 보안 필터도 같은 대응을 써야 해서 공용 위치가 하나 더 생긴다.
2. **서비스가 던지는 예외는 `BusinessException(ErrorCode)` 하나다.** 추가 데이터가 있는 실패만 하위 클래스로 둔다. 지금은 `InvalidInputException`(항목 오류 `errors`)뿐이다.
   - 대안: 실패 종류마다 하위 클래스(`DuplicateEmailException` 등)를 두기. 서비스 코드는 읽기 좋지만, 오류 코드와 클래스가 1:1로 겹친다. 또 `new BusinessException(code)`를 직접 던지는 길이 남아 두 방식이 다시 섞일 수 있다.
   - 이름은 `ApiException`에서 `BusinessException`으로 바꾼다. 2026-10-12 사용자 결정.
     - `ErrorCode` + `BusinessException`은 Spring 프로젝트에서 흔한 조합이라 읽는 사람이 바로 알아본다. 이름에 HTTP·API가 없어 서비스가 던지는 예외라는 점이 드러난다.
     - 감수하는 점: 자원 없음, 토큰 주인 없음, 입력 오류는 엄밀히 업무 규칙 위반이 아니다. 입력 오류는 요청 DTO(`BenefitSearchCondition`)에서도 던진다. 이 예외는 "응답 오류 코드가 정해진 실패"라는 뜻으로 쓰고, 클래스 Javadoc에 적는다.
     - 패키지는 `common.api`에 그대로 둔다. 응답 형식(`ApiErrorResponse`)·`ErrorCode`와 한 묶음이다.
3. **`REQUEST_FAILED`·`INTERNAL_ERROR`는 상태 코드를 두지 않고, `BusinessException`으로 던질 수 없다.**
   - 두 코드는 Spring MVC 예외와 예상하지 못한 예외에서만 나간다. `BusinessException` 생성자가 상태 코드 없는 코드를 받으면 `IllegalArgumentException`을 던진다.
   - 완료 조건의 "같은 오류 코드가 다른 상태 코드로 나갈 수 없다"는 서비스가 던지는 실패에 적용된다고 0009에 적는다.
4. **`BusinessException.status()`는 `code.status()`를 돌려주는 메서드로 남긴다.**
   - 핸들러가 이 메서드를 쓴다. `SignUpConcurrencyTest`는 예외 이름만 바꾸면 되고 검증 내용(`status()`·`code()`)은 그대로다.
5. **디버그용 설명은 `BusinessException(ErrorCode, String detail)`로 받는다.**
   - 예외 메시지에만 남고 응답에는 나가지 않는다. `NotFoundException`이 담던 `serviceId`를 잃지 않게 한다.

## 단계별 구현 계획

1. **`ErrorCode`** (`common.api`)
   - 필드 `HttpStatus status`와 `status()`를 더한다. 상태 코드가 없는 상수는 `null`이다.
   - 상수별 상태 코드:

     | 코드 | 상태 |
     | --- | --- |
     | `INVALID_INPUT` | `BAD_REQUEST` |
     | `NOT_FOUND` | `NOT_FOUND` |
     | `UNAUTHENTICATED` | `UNAUTHORIZED` |
     | `INVALID_CREDENTIALS` | `UNAUTHORIZED` |
     | `DUPLICATE_EMAIL` | `CONFLICT` |
     | `INTERNAL_ERROR` | `null` |
     | `REQUEST_FAILED` | `null` |

   - `status()` Javadoc에 "`null`이면 `BusinessException`으로 던질 수 없고 Spring MVC 예외에서만 나간다"를 적는다.
   - `from(HttpStatusCode)`는 그대로 둔다.
2. **`BusinessException`** (`common.api`, `ApiException.java`를 `git mv`로 이름 변경)
   - 필드는 `ErrorCode code`만 둔다.
   - 생성자 `BusinessException(ErrorCode code)`와 `BusinessException(ErrorCode code, String detail)`. `code.status()`가 `null`이면 `IllegalArgumentException`을 던진다. 메시지는 `code.name()`이고, `detail`이 있으면 `code.name() + ": " + detail`이다.
   - 메서드: `code()`, `status()`(`code.status()`), `errors()`(기본 `List.of()`).
   - 클래스 Javadoc: "응답 오류 코드가 정해진 실패. 서비스와 요청 검증에서 던진다. 오류 코드만 정하고 상태 코드는 `ErrorCode`가 정한다(결정 0009)".
3. **`InvalidInputException`** (`common.api`)
   - `extends BusinessException`으로 바꾸고 `super(ErrorCode.INVALID_INPUT, field + ": " + reason)`을 호출한다.
   - `error()`는 유지하고 `errors()`를 `List.of(error)`로 재정의한다.
4. **`NotFoundException`** 삭제.
5. **`ApiErrorResponse`**: `of(ErrorCode code, List<InputError> errors)`를 더하고, `of(code)`가 이를 호출하게 한다. `invalidInput`은 그대로 둔다.
6. **`GlobalExceptionHandler`**
   - `handleInvalidInput`·`handleNotFound`를 지운다.
   - `handleApi`를 `handleBusiness(BusinessException ex)`로 바꾸고, `ResponseEntity.status(ex.status())`로 응답하고 본문은 `ApiErrorResponse.of(ex.code(), ex.errors())`다. `UNAUTHENTICATED`일 때 `WWW-Authenticate`를 붙이는 부분은 그대로 둔다.
   - `handleUnexpected`, `handleMethodArgumentNotValid`, `handleExceptionInternal`은 바꾸지 않는다.
7. **`ApiAuthenticationEntryPoint`**
   - `commence`에서 `response.setStatus(HttpStatus...)`를 지운다. `write(response, code)`가 `response.setStatus(code.status().value())`를 먼저 하게 한다.
   - `WWW-Authenticate` 헤더 설정은 그대로 둔다.
8. **실패를 던지는 곳**
   - `BenefitQueryService.getDetail`: `new BusinessException(ErrorCode.NOT_FOUND, "혜택이 없습니다. serviceId=" + serviceId)`.
   - `AccountService.me`·`duplicateEmail`·`invalidCredentials`: `new BusinessException(ErrorCode.X)`로 바꾸고 `HttpStatus` import를 지운다.
   - `BenefitSearchCondition`: 그대로 둔다(`InvalidInputException`).
9. **테스트**
   - `kr.govbenefit.common.api.BusinessExceptionTest`(새 클래스, 단위 테스트)
     - `INTERNAL_ERROR`·`REQUEST_FAILED`로 만들면 `IllegalArgumentException`이다.
     - `status()`가 `code().status()`와 같다.
     - `InvalidInputException`의 `status()`는 400이고, `errors()`는 받은 항목 하나다.
   - `SignUpConcurrencyTest`: import와 `catch`·`instanceof`의 `ApiException`을 `BusinessException`으로 바꾼다. 검증 내용은 그대로다.
   - 그 밖의 기존 테스트는 고치지 않는다.
10. **문서** `docs/decisions/0009-api-error-response.md`
    - 출처에 `#39, [플랜](../plans/39-unify-api-exceptions.md)`을 더한다.
    - 결정의 코드 표 아래에 다음을 적는다(#39에서 추가).
      - 표의 상태 코드는 `ErrorCode`에 한 번만 정한다.
      - 서비스는 `BusinessException(ErrorCode)`로 실패를 던지고 상태 코드를 지정하지 않는다.
      - 항목 오류처럼 추가 데이터가 있는 실패만 `BusinessException`의 하위 클래스로 둔다.
      - `INTERNAL_ERROR`·`REQUEST_FAILED`는 Spring MVC 예외와 예상하지 못한 예외에서만 나가며 던질 수 없다.
    - 근거에 설계 1·2의 대안과 제외 이유를 짧게 더한다.
    - 적용 위치
      - `NotFoundException` 줄을 지운다.
      - `ErrorCode` 설명을 "코드 목록과 응답 상태 코드"로 바꾼다.
      - `ApiException` 줄을 `BusinessException.java`로 바꾸고 설명을 "응답 오류 코드가 정해진 실패. 오류 코드만 받는다. #38에서 `ApiException`으로 추가, #39에서 이름을 바꾸고 상태 코드 인자 제거"로 적는다.
      - `InvalidInputException`에 "`BusinessException`의 하위 클래스"를 더한다.
    - 영향에 "새 API는 `BusinessException(ErrorCode)`로 실패를 던진다. 새 코드는 `ErrorCode`에 상태 코드와 함께 추가한다"를 더한다.

## 완료 조건 매핑

- [ ] 서비스 코드가 HTTP 상태 코드를 직접 지정하지 않는다.
  - 설계 2, 2·8단계. `BusinessException`에 상태 코드 인자가 없다.
  - 검증 명령의 `grep` 결과에 `common` 밖의 실패 응답 상태 코드가 없다. `AuthController`의 201은 성공 응답이라 제외한다.
- [ ] 오류 코드마다 응답 상태가 한 곳에서 정해져, 같은 오류 코드가 다른 상태 코드로 나갈 수 없다.
  - 설계 1·3, 1·2·6·7단계. `GlobalExceptionHandler.handleBusiness`와 `ApiAuthenticationEntryPoint`가 모두 `ErrorCode.status()`를 쓴다.
  - 상태 코드가 정해지지 않은 두 코드는 던질 수 없다(`BusinessExceptionTest`).
- [ ] 기존 API의 실패 응답(상태 코드, `code`, 본문, 401의 `WWW-Authenticate` 헤더)이 바뀌지 않는다. 기존 API 테스트를 고치지 않고 통과한다.
  - 9단계. `SignUpApiTest`, `LoginApiTest`, `BenefitSearchApiTest`, `BenefitDetailApiTest`, `AccessTokenAuthTest`, `ApiDocsTest`를 고치지 않고 통과한다.
  - `BenefitSearchConditionTest`도 고치지 않는다. `SignUpConcurrencyTest`는 API 테스트가 아니며, 예외 이름만 바꾸고 검증 내용은 그대로다.
- [ ] 결정 0009에 새 API가 실패를 표현하는 방식이 적혀 있다. → 10단계.

## 검증 명령

- `backend/`에서 `./gradlew test`
- `git diff develop --stat -- backend/src/test`: 새 파일 `BusinessExceptionTest.java`와 `SignUpConcurrencyTest.java`(이름 변경)만 나온다.
- `grep -rn "ApiException" backend/src --include='*.java' | grep -v Gov24ApiException`: 결과가 없다.
- `grep -rn "HttpStatus" backend/src/main/java/kr/govbenefit --include='*.java' | grep -v -e '/common/' -e '/collection/'`: `AuthController`의 201만 나온다.

## 결정 필요

없음. 방안(`ErrorCode`에 상태 코드, 단일 예외와 입력 오류 하위 클래스), 이름(`BusinessException`), 상태 코드 없는 두 코드의 처리는 2026-10-12 사용자가 정했다.
