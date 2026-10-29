# 0009. API 오류 응답 형식

- 상태: 채택
- 날짜: 2026-10-04
- 출처: #29, [플랜](../plans/29-benefit-search-api.md). 실패를 던지는 방식은 #39, [플랜](../plans/39-unify-api-exceptions.md)

## 배경

#29에서 첫 공개 API(혜택 검색)를 만들면서 잘못된 입력을 400으로 응답해야 했다. 오류 본문 형식이 API마다 다르면 클라이언트가 API마다 오류를 따로 해석해야 한다. #30의 상세 조회부터 이후 모든 API가 같은 형식을 써야 한다.

## 결정

성공 응답은 데이터를 그대로 보내고, 실패할 때만 HTTP 상태 코드와 다음 본문을 보낸다. 성공·실패를 함께 감싸는 envelope은 쓰지 않는다.

```json
{
  "code": "INVALID_INPUT",
  "message": "요청 값이 올바르지 않습니다.",
  "errors": [
    { "field": "size", "value": "200", "reason": "1 이상 100 이하여야 합니다." }
  ]
}
```

- `code`: 클라이언트가 분기에 쓰는 고정 문자열이다. 목록은 `ErrorCode`에 있다.

  | 코드 | 상태 | 뜻 |
  | --- | --- | --- |
  | `INVALID_INPUT` | 400 | 요청 값이 올바르지 않다. 항목 오류는 `errors`에 있다 |
  | `NOT_FOUND` | 404 | 요청한 경로나 자원이 없다 |
  | `UNAUTHENTICATED` | 401 | 액세스 토큰이 없거나, 형식이 틀렸거나, 위조·만료됐거나, 토큰 주인이 없다. 원인은 나누지 않는다([0015](0015-api-auth-boundary.md)). #38에서 추가. 재발급에서 리프레시 토큰이 없거나 쓸 수 없을 때도 이 코드다([0017](0017-refresh-token.md), #45) |
  | `INVALID_CREDENTIALS` | 401 | 로그인 실패. 비밀번호가 틀린 경우와 가입되지 않은 이메일을 구분하지 않는다([0013](0013-password-storage.md)). #38에서 추가 |
  | `DUPLICATE_EMAIL` | 409 | 이미 가입된 이메일이다. #38에서 추가 |
  | `CSRF_REJECTED` | 403 | 리프레시 토큰 쿠키로 인증하는 재발급 요청이 CSRF 방어(`X-Requested-With` 헤더, `Origin` 허용 목록)를 통과하지 못했다. 클라이언트 구현 오류라 원인을 숨기지 않는다([0017](0017-refresh-token.md)). #45에서 추가 |
  | `VERIFICATION_CODE_MISMATCH` | 400 | 가입 인증번호가 틀렸다. 같은 번호로 다시 입력할 수 있다([0019](0019-email-verification-password-reset.md)). #47에서 추가 |
  | `VERIFICATION_CODE_EXPIRED` | 400 | 가입 인증번호가 만료됐거나, 이미 썼거나, 다시 받아 무효가 됐거나, 요청한 적이 없다. 번호를 다시 받아야 한다([0019](0019-email-verification-password-reset.md)). #47에서 추가 |
  | `VERIFICATION_ATTEMPTS_EXCEEDED` | 400 | 가입 인증번호를 비교한 횟수(5번)를 다 썼다. 맞는 번호도 거절한다([0019](0019-email-verification-password-reset.md)). #47에서 추가 |
  | `EMAIL_NOT_VERIFIED` | 403 | 가입 티켓이 없거나, 만료·사용됐거나, 다른 이메일로 받은 것이다. 원인은 나누지 않는다([0019](0019-email-verification-password-reset.md)). #47에서 추가 |
  | `INVALID_RESET_TOKEN` | 400 | 비밀번호 재설정 토큰이 없거나, 형식이 틀렸거나, 만료·사용됐거나, 다시 요청해 무효가 됐다. 원인은 나누지 않는다([0019](0019-email-verification-password-reset.md)). #47에서 추가 |
  | `PASSWORD_MISMATCH` | 400 | 탈퇴할 때 다시 입력한 비밀번호가 틀렸다. 토큰 만료로 오해하지 않게 401을 쓰지 않는다(`DEC-AUTH`). #51에서 추가 |
  | `TOO_MANY_REQUESTS` | 429 | 요청 횟수 제한. `Retry-After` 헤더로 다시 시도할 수 있을 때까지의 초를 준다. 지금은 가입 인증번호 요청에서만 나간다([0019](0019-email-verification-password-reset.md)). #47에서 추가 |
  | `INTERNAL_ERROR` | 5xx | 서버에서 처리하지 못했다 |
  | `REQUEST_FAILED` | 그 밖의 상태 | 위에 해당하지 않는 실패(405 등). 상태 코드로 원인을 구분한다 |

- 실패를 던지는 방식(#39에서 추가):
  - 표의 상태 코드는 `ErrorCode` 상수에 한 번만 정한다. 같은 코드가 다른 상태 코드로 나가지 않는다.
  - 서비스와 요청 검증은 `BusinessException(ErrorCode)`로 실패를 던지고 상태 코드를 지정하지 않는다. 응답에 나가지 않는 설명은 `BusinessException(ErrorCode, detail)`로 예외 메시지에 남긴다.
  - 항목 오류(`errors`)처럼 추가 데이터가 있는 실패만 `BusinessException`의 하위 클래스로 둔다. 지금은 `InvalidInputException`과 `RateLimitedException`(`Retry-After`의 시간, #47)이다.
  - `INTERNAL_ERROR`·`REQUEST_FAILED`는 상태 코드가 하나로 정해지지 않아 던질 수 없다. Spring MVC 예외와 예상하지 못한 예외에서만 나간다.

- `message`: 사람이 읽는 한국어 문구다. 클라이언트는 이 값으로 분기하지 않는다.
- `errors`: 입력 항목별 오류이며 항상 배열이다. 항목 오류가 없으면 비어 있다. `field`는 요청 파라미터 이름, `value`는 받은 값(없었으면 `null`), `reason`은 거절한 이유다. 비밀번호 항목(`password`)은 `value`를 항상 `null`로 둔다(#38). 새 비밀번호와 일회용 토큰 항목(`newPassword`, `token`, `verificationToken`)도 같다(#47).
- 5xx 응답에는 예외 메시지나 내부 정보를 넣지 않고 로그에만 남긴다.

## 근거

- Spring `ProblemDetail`(RFC 9457)은 코드가 가장 적지만, 오류 형식을 직접 설계해 보기로 했다(2026-10-04 사용자 결정).
- 성공·실패 공통 envelope(`success`, `data`, `error`)은 클라이언트가 응답을 한 가지 방식으로 처리할 수 있지만, HTTP 상태 코드와 뜻이 겹치고 성공 응답이 한 겹 깊어진다. 상태 코드로 성공·실패를 가르고 실패만 본문 형식을 정했다.
- `timestamp`, `path` 같은 추적 필드는 클라이언트가 쓸 일이 없어 넣지 않았다. 추적이 필요하면 로그와 관측 도구(`DEC-OPS`)에서 다룬다.
- `errors`를 항상 배열로 두어 클라이언트가 `null` 검사 없이 처리하게 했다.
- 상태 코드를 `ErrorCode`에 둔 이유(#39): 서비스가 상태 코드와 오류 코드를 따로 넘기면 둘이 어긋나도(400과 `DUPLICATE_EMAIL`) 막을 수 없었다. 오류 코드와 상태 코드가 같은 줄에 있으면 어긋날 수 없다. 핸들러의 `switch`로 대응하는 방식은 보안 필터도 같은 대응을 써야 해서 공용 위치가 하나 더 생겨 고르지 않았다.
- 실패 종류마다 예외 클래스를 두지 않은 이유(#39): 오류 코드와 클래스가 1:1로 겹치고, 상위 예외를 직접 던지는 길이 남아 두 방식이 다시 섞일 수 있다.

## 적용 위치

- `backend/src/main/java/kr/govbenefit/common/api/ApiErrorResponse.java`: 본문 형식
- `backend/src/main/java/kr/govbenefit/common/api/ErrorCode.java`: 코드 목록과 응답 상태 코드, Spring MVC 예외의 상태 코드 대응
- `backend/src/main/java/kr/govbenefit/common/api/GlobalExceptionHandler.java`: Spring MVC 예외, 입력 검증 실패, `BusinessException`을 이 형식으로 바꾼다
- `backend/src/main/java/kr/govbenefit/common/api/BusinessException.java`: 응답 오류 코드가 정해진 실패. 오류 코드만 받는다. #38에서 `ApiException`으로 추가, #39에서 이름을 바꾸고 상태 코드 인자 제거
- `backend/src/main/java/kr/govbenefit/common/api/InvalidInputException.java`: Bean Validation 제약으로 표현하지 않은 입력 검증. `BusinessException`의 하위 클래스(#39). 여러 항목의 오류를 한 번에 담을 수 있다(#49)
- `backend/src/main/java/kr/govbenefit/common/api/RateLimitedException.java`: 요청 횟수 제한. `GlobalExceptionHandler`가 `Retry-After` 헤더를 붙인다. `BusinessException`의 하위 클래스. #47에서 추가([0019](0019-email-verification-password-reset.md))
- `backend/src/main/java/kr/govbenefit/common/security/ApiAuthenticationEntryPoint.java`: 보안 필터에서 거절한 요청(401·404)을 이 형식으로 쓴다. 상태 코드는 `ErrorCode`를 따른다. #38에서 추가([0015](0015-api-auth-boundary.md))
- `backend/src/main/java/kr/govbenefit/common/security/CsrfOriginInterceptor.java`: 재발급 요청의 CSRF 방어 실패를 `BusinessException(CSRF_REJECTED)`로 던진다. 인터셉터의 예외도 `GlobalExceptionHandler`가 이 형식으로 바꾼다. #45에서 추가([0017](0017-refresh-token.md))
- `backend/src/main/java/kr/govbenefit/common/api/OpenApiConfig.java`: API 명세의 공통 500 응답. `ApiErrorResponse`의 스키마 설명과 함께 #33에서 추가([0012](0012-api-spec-docs.md))

## 영향

- 새 API는 오류 본문을 이 형식으로만 보낸다. 컨트롤러에서 오류 본문을 직접 만들지 않고 예외로 던진다.
- 새 API는 `BusinessException(ErrorCode)`로 실패를 던진다. 새 코드는 `ErrorCode`에 상태 코드와 함께 추가한다(#39).
- 코드를 추가하거나 뜻을 바꾸면 `ErrorCode`와 이 문서의 표를 함께 고친다. 이미 공개한 코드의 뜻은 바꾸지 않는다.
- 입력 검증 메시지는 요청 언어에 따라 바뀌지 않도록 제약 애너테이션의 `message`에 한국어로 적는다.
- 비밀 값을 받는 요청 항목은 `GlobalExceptionHandler.SENSITIVE_FIELDS`에 이름을 두어 `value`가 응답에 나가지 않게 한다.
