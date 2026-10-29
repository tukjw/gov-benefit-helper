# 0013. 비밀번호 저장과 길이 규칙

- 상태: 채택
- 날짜: 2026-10-10
- 출처: #38, [플랜](../plans/38-signup-login-access-token.md)

## 배경

#38에서 이메일·비밀번호 가입을 만들면서 비밀번호를 원문 없이 저장하고 로그인에서 비교해야 했다. 저장 방식은 비밀번호 재설정(B)과 탈퇴 등 이후 계정 기능도 같이 쓴다. 해시 알고리즘이 받을 수 있는 길이가 비밀번호 규칙을 제한하므로 둘을 함께 정했다.

## 결정

비밀번호는 Spring Security `DelegatingPasswordEncoder`의 BCrypt로 인코딩하고, 저장값에 `{bcrypt}` 접두사를 붙인다. 가입하는 비밀번호는 8자 이상(코드 포인트), UTF-8 72바이트 이하다(2026-10-09 사용자 결정, `DEC-AUTH`).

- 문자 종류 조합 규칙은 두지 않는다. 공백도 글자다.
- 로그인 실패는 비밀번호가 틀린 경우와 가입되지 않은 이메일을 같은 응답(401 `INVALID_CREDENTIALS`)으로 돌려준다. 가입되지 않은 이메일도 기동할 때 만든 가짜 해시와 비교해 응답 시간 차이를 줄인다.
- 72바이트를 넘는 비밀번호로 로그인하면 비교하지 않고 같은 실패로 처리한다.
- 입력 오류 응답의 `errors[].value`에 비밀번호 값을 넣지 않는다([0009](0009-api-error-response.md)).

## 근거

- BCrypt는 의존성 추가 없이 쓸 수 있는 표준 구현이다. 접두사가 있어 나중에 다른 알고리즘으로 바꿔도 기존 해시를 그대로 검증하고 로그인할 때 다시 인코딩할 수 있다.
- BCrypt는 72바이트까지만 처리한다. Spring Security 7.1.1의 `BCryptPasswordEncoder`는 넘는 입력의 `encode`에서 예외를 던지지만, `matches`는 앞 72바이트만 비교해 `true`를 돌려준다(2026-10-10 직접 실행해 확인). 그래서 가입은 길이 상한을 바이트로 두고, 로그인은 72바이트를 넘으면 비교하지 않는다. 비교하면 72바이트 비밀번호 뒤에 아무 글자를 붙여도 로그인된다. 한글만 쓰면 24자, 이모지는 18자까지다.
- Argon2id는 길이 제한이 없고 OWASP가 가장 먼저 권하지만 BouncyCastle 의존성과 메모리 파라미터 조정이 필요하다. 긴 비밀번호 요구가 생기면 접두사로 바꿀 수 있다.
- SHA-256으로 줄인 뒤 BCrypt에 넣는 방식은 다른 곳에서 유출된 SHA-256 값으로 공격할 수 있어(password shucking) 쓰지 않는다.
- 글자 수를 코드 포인트로 세는 이유는 사용자가 보는 글자 수와 맞추기 위해서다. Bean Validation `@Size`는 UTF-16 단위라 이모지를 2자로 센다.
- 응답 시간 차이: 가입되지 않은 이메일에서 해시 비교를 건너뛰면 응답이 수 ms, 비교하면 BCrypt 비용만큼 걸려 시간으로 가입 여부를 추측할 수 있다.
- 한계: 가입 API는 이미 가입된 이메일에 409 `DUPLICATE_EMAIL`을 돌려주므로 가입 여부가 드러난다. #38 완료 조건이 요구하는 동작이다. 이메일 소유 확인(후속 이슈 B)에서 "가입 요청은 항상 같은 응답, 결과는 메일로 안내" 방식을 검토한다.

## 적용 위치

- `backend/src/main/java/kr/govbenefit/common/security/SecurityConfig.java`: `PasswordEncoder` 빈
- `backend/src/main/java/kr/govbenefit/account/domain/PasswordRule.java`: 길이 규칙
- `backend/src/main/java/kr/govbenefit/account/dto/Password.java`, `PasswordValidator.java`: 가입 요청 검증
- `backend/src/main/java/kr/govbenefit/account/service/AccountService.java`: 가짜 해시 비교, 72바이트 초과 로그인 처리
- `backend/src/main/java/kr/govbenefit/common/api/GlobalExceptionHandler.java`: 비밀번호 값 제외
- `backend/src/main/resources/db/migration/V4__create_user_account.sql`: `password_hash`

## 영향

- 비밀번호를 받는 모든 API(재설정, 변경)는 같은 `PasswordEncoder`와 `PasswordRule`을 쓴다. 요청 DTO의 비밀번호 필드 이름은 `password`로 두거나 `GlobalExceptionHandler.SENSITIVE_FIELDS`에 추가한다.
- 알고리즘이나 길이 규칙을 바꾸면 이 결정을 대체한다. 길이 상한을 72바이트보다 늘리려면 알고리즘도 바꿔야 한다.
