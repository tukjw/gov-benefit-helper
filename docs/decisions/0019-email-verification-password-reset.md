# 0019. 가입 전 이메일 인증과 비밀번호 재설정

- 상태: 채택
- 날짜: 2026-10-16
- 출처: #47, [플랜](../plans/47-email-verification-password-reset.md)

## 배경

#38은 이메일 소유를 확인하지 않고 가입을 받아, 남의 이메일로 먼저 가입해 주인의 가입을 막을 수 있었다. 비밀번호를 잊은 사용자는 계정을 되찾을 방법도 없었다. `DEC-AUTH`는 처음에 가입 후 소유를 확인하고 그 전에는 개인 기능을 막기로 했다. 그러나 이 서비스는 가입 목적이 곧 개인 기능이라 확인을 미뤄도 마찰이 줄지 않고 미확인 계정과 이메일 선점 문제만 남아, #47에서 '확인 후 가입'으로 바꿨다. 이 결정은 가입 인증과 확인 결과를 가입에 묶는 방식, 인증번호와 재설정 토큰의 규칙, 발송 제한, 재설정 링크 형식을 정한다. 프론트의 가입 폼과 재설정 화면, 탈퇴, 로그인 속도 제한이 이 결정을 따른다.

## 결정

가입 폼에서 6자리 인증번호를 확인하면 일회용 가입 티켓을 주고, 가입 요청은 그 티켓을 본문에 담는다. 비밀번호는 메일로 보낸 일회용 링크(`<재설정 화면>#token=<토큰>`)로 재설정하고, 재설정하면 그 계정의 리프레시 토큰을 모두 폐기한다. 두 메일 모두 이메일별로 발송 간격과 최근 24시간 횟수를 제한한다.

- 흐름: `POST /api/auth/email-verification`(`email`, 204) → `POST /api/auth/email-verification/confirm`(`email`, `code`, 200 `{verificationToken, expiresAt}`) → `POST /api/auth/signup`(`email`, `password`, `verificationToken`, 201). 재설정은 `POST /api/auth/password-reset`(`email`, 항상 204) → `POST /api/auth/password-reset/confirm`(`token`, `newPassword`, 204).
- 저장: `email_verification`은 발송 한 번에 한 행이며 확인 결과(티켓 해시·만료·사용 시각)를 같은 행에 둔다. 계정이 생기기 전이라 FK가 없다. `password_reset_token`은 발송 한 번에 한 행이며 `user_id`가 `user_account`를 `ON DELETE CASCADE`로 참조한다. 두 테이블 모두 같은 이메일(계정)의 **가장 최근 행만 유효**하다. 이미 발급된 가입 티켓은 이후에 번호를 다시 받아도 유효하다.
- 값: 인증번호는 `SecureRandom`의 6자리 숫자(`000000`~`999999`)이고 DB에는 SHA-256 소문자 hex만 둔다. 가입 티켓과 재설정 토큰은 리프레시 토큰과 같은 32바이트 base64url(43자)과 SHA-256 hex다(`OpaqueTokens`, [0017](0017-refresh-token.md)).
- 유효기간: 인증번호 10분, 가입 티켓 30분, 재설정 토큰 30분. 모두 `now < expires_at`일 때만 쓸 수 있다(`auth.email-verification.*`, `auth.password-reset.token-ttl`).
- 인증번호 확인 판정 순서(`EmailVerificationService.confirm`): 가장 최근 행이 없거나·이미 맞혔거나·만료 → `VERIFICATION_CODE_EXPIRED`(시도로 세지 않음) → 시도 예약 `UPDATE … SET attempts = attempts + 1 WHERE id = ? AND attempts < 5 AND verified_at IS NULL`이 0행이면 `VERIFICATION_ATTEMPTS_EXCEEDED` → 해시가 다르면 `VERIFICATION_CODE_MISMATCH` → 맞으면 `verified_at IS NULL` 조건부 UPDATE로 티켓을 남긴다(0행이면 `VERIFICATION_CODE_EXPIRED`). 거절해도 예약한 시도는 커밋한다. 서비스는 결과 값(`ConfirmOutcome`)을 돌려주고 컨트롤러가 예외로 바꾼다.
- 가입 판정 순서(`AccountService.signUp`): 티켓 검사(형식·존재·미사용·만료 전·같은 이메일, 아니면 403 `EMAIL_NOT_VERIFIED`) → 이메일 중복(409 `DUPLICATE_EMAIL`) → `ticket_used_at IS NULL` 조건부 UPDATE(0행이면 403) → 계정 생성. 인증번호 요청 단계에서는 가입 여부를 보지 않는다.
- 발송 제한(`SendRateLimit`, `auth.mail.*`): 같은 이메일(재설정은 같은 계정)에 마지막 발송에서 60초가 지나야 하고, `created_at > now - 24h`인 발송이 10회 미만이어야 보낸다. 가입 인증과 재설정은 각자의 테이블로 따로 센다. 가입 인증은 429 `TOO_MANY_REQUESTS`와 `Retry-After`(초, 올림, 최소 1)로 거절하고, 재설정은 204를 그대로 주고 메일만 보내지 않는다.
- 재설정 판정(`PasswordResetService.reset`): 형식·존재·미사용·만료 전·그 계정의 가장 최근 행이 아니면 400 `INVALID_RESET_TOKEN`(사유를 나누지 않음) → `used_at IS NULL` 조건부 UPDATE → 비밀번호 변경 → `RefreshTokenService.revokeAllOf(userId)`. 토큰을 발급하지 않으므로 사용자는 새 비밀번호로 다시 로그인한다. 새 비밀번호는 가입과 같은 `@Password` 규칙이다.
- 재설정 링크: `auth.password-reset.url` + `#token=<토큰>`(`https://govbenefit.kr/reset-password#token=…`, local은 `http://localhost:3000/reset-password`).
- 입력 오류 응답의 `value`에서 `newPassword`, `token`, `verificationToken`을 뺀다([0009](0009-api-error-response.md)).
- 인증 기록(`email_verification`)은 가입해도, 가입하지 않아도 지우지 않는다(2026-10-16 사용자 결정).

## 근거

- **본문으로 주고받는 가입 티켓**: "이 이메일은 확인됨"만 기록하고 가입이 이메일만 보면, 주인이 확인한 직후 유효기간 안에 다른 사람이 같은 이메일과 자기 비밀번호로 먼저 가입할 수 있어 선점 문제가 남는다. 가입에 인증번호를 다시 넣는 방식은 번호 검사가 두 곳에서 일어나 시도 횟수를 어디서 세는지 모호하다. 서명한 stateless 티켓(JWT)은 한 번만 쓰게 하려면 결국 식별자를 저장해야 한다. 쿠키로 주면 CSRF 방어가 필요하고 이후 네이티브 앱에서 같은 API를 쓰기 어렵다.
- **두 테이블**: 가입 인증은 계정 전이라 `email`만, 재설정은 `user_id`와 FK가 필요하다. 한 테이블에 `purpose`를 두면 nullable 컬럼과 조건 분기가 생긴다. Redis는 스택에 없다(`DEC-OPS`).
- **가장 최근 행만 유효**: 다시 받을 때 이전 행을 따로 무효 표시하지 않아도 된다. 발송 제한도 이 행들의 `created_at`으로 센다.
- **인증번호의 SHA-256(2026-10-16 사용자 결정)**: 다른 해시 저장과 표기를 맞춘다. 한계: 경우의 수가 10^6이라 DB가 유출되면 해시로 번호를 역산할 수 있다. 번호가 10분 뒤 만료되고 시도 횟수를 제한한다는 점에 기댄다. 서버 키 HMAC은 실제로 보호되지만 운영 비밀 값이 하나 늘어난다.
- **비교 전에 시도 예약**: 틀린 뒤에 세면 동시 요청 수십 개가 모두 같은 횟수를 읽고 비교해 제한보다 많이 추측할 수 있다. 조건부 UPDATE는 행을 잠그고 최신 값을 보므로 동시 요청이 몇 개든 비교는 최대 5번이다. 맞히면 번호가 끝나므로 경계는 "4번 틀린 뒤 맞히면 성공, 5번 틀린 뒤에는 맞아도 거절"로 틀린 횟수 기준과 같다. 시도 예약이 0행일 때 동시에 다른 요청이 맞힌 경우도 섞이지만, 같은 트랜잭션에서 다시 읽어도 `REPEATABLE READ` 스냅샷이라 구분할 수 없어 `VERIFICATION_ATTEMPTS_EXCEEDED`로 둔다.
- **티켓 검사를 중복 검사보다 먼저**: 반대 순서면 티켓 없이도 409로 가입 여부를 조회할 수 있다. 티켓을 먼저 보면 409는 그 메일함의 주인만 본다. 409는 트랜잭션을 롤백해 티켓 사용도 되돌린다.
- **인증번호 실패 코드를 나눔**: 클라이언트의 다음 행동이 다르다(다시 입력 / 번호를 다시 받음). 인증번호는 가입 여부와 무관해 나눠도 드러나는 정보가 없다. 가입 티켓과 재설정 토큰은 어느 실패든 처음부터 다시 해야 해서 하나로 합친다.
- **발송 제한(2026-10-16 사용자 결정)**: 메일 폭탄과, 다시 받기를 반복해 추측 기회를 늘리는 것을 막는다. 최근 24시간은 한국 날짜 기준과 달리 자정 직전·직후에 몰아 상한의 두 배를 보낼 수 없다. 재설정에서만 거절을 숨기는 이유는 가입된 이메일에서만 429가 나면 가입 여부가 드러나기 때문이다. 한계: 같은 이메일의 동시 요청은 판정을 함께 통과해 몇 통 더 나갈 수 있다. 첫 발송에는 잠글 행이 없다.
- **재설정 링크 토큰(2026-10-16 사용자 결정)**: 엔트로피가 256비트라 시도 횟수 제한이 필요 없다. 6자리 번호로 재설정하면 다시 요청을 반복해 계정 탈취 시도 기회를 늘릴 수 있다. fragment는 브라우저가 서버로 보내지 않아 프론트 호스팅 접근 로그와 Referer에 남지 않고, 개인 화면은 브라우저에서 렌더링하므로(`DEC-APP`) 스크립트가 읽으면 된다.
- **재설정 뒤 자동 로그인 없음(2026-10-16 사용자 결정)**: 로그인 경로를 하나로 두고 재설정 응답에 쿠키를 붙이지 않는다. 한계: 재설정 뒤에도 이미 발급된 액세스 토큰은 만료(최대 15분)까지 유효하다(`DEC-AUTH`의 한계).
- **인증 기록을 지우지 않음(2026-10-16 사용자 결정)**: 가장 단순하다. 한계: 가입하지 않은 사람의 이메일이 계속 쌓이고, 탈퇴한 사람의 이메일도 인증 기록에 남는다.

## 적용 위치

- `backend/src/main/resources/db/migration/V6__create_email_verification.sql`, `V7__create_password_reset_token.sql`
- `backend/src/main/resources/application.yaml`: `auth.email-verification`, `auth.password-reset`, `auth.mail`(`cooldown`, `window`, `max-per-window`)
- `backend/src/main/resources/application-local.yaml`: `auth.password-reset.url`(`http://localhost:3000/reset-password`)
- `backend/src/main/java/kr/govbenefit/account/domain/EmailVerification.java`, `PasswordResetToken.java`, `VerificationCodes.java`, `SendRateLimit.java`, `OpaqueTokens.java`
- `backend/src/main/java/kr/govbenefit/account/repository/EmailVerificationRepository.java`, `PasswordResetTokenRepository.java`: 조건부 UPDATE. `RefreshTokenRepository.revokeAllByUserId`
- `backend/src/main/java/kr/govbenefit/account/service/EmailVerificationService.java`, `ConfirmOutcome.java`, `PasswordResetService.java`, `AccountService.signUp`, `RefreshTokenService.revokeAllOf`
- `backend/src/main/java/kr/govbenefit/account/controller/EmailVerificationController.java`, `PasswordResetController.java`, `AuthController.java`(가입)
- `backend/src/main/java/kr/govbenefit/account/config/EmailVerificationProperties.java`, `PasswordResetProperties.java`, `AccountMailProperties.java`
- `backend/src/main/java/kr/govbenefit/common/api/RateLimitedException.java`, `GlobalExceptionHandler.java`(`Retry-After`, `SENSITIVE_FIELDS`)
- `backend/src/test/java/kr/govbenefit/account/controller/EmailVerificationApiTest.java`, `SignUpApiTest.java`, `PasswordResetApiTest.java`, `account/service/EmailVerificationConcurrencyTest.java`, `SignUpConcurrencyTest.java`

## 영향

- 프론트의 가입 폼은 인증번호 요청 → 확인 → 가입 순서로 부르고, 확인 실패 코드에 따라 다시 입력하거나 번호를 다시 받게 한다. 429는 `Retry-After`의 초만큼 다시 보내기를 막는다.
- 프론트의 재설정 화면은 `/reset-password`에 두고 `location.hash`의 `token`을 읽는다. 화면 경로를 바꾸면 `auth.password-reset.url`을 함께 바꾼다.
- CORS를 붙이는 이슈는 브라우저 스크립트가 `Retry-After`를 읽을 수 있게 노출 헤더(`Access-Control-Expose-Headers`)에 넣는다.
- 탈퇴 이슈는 `email_verification`에 남는 이메일을 이메일 기준으로 지울지 판단한다. `DEC-AUTH`는 탈퇴하면 데이터를 즉시 삭제한다고 한다. 재설정 토큰은 계정과 함께 지워진다.
- 로그인 속도 제한은 같은 `TOO_MANY_REQUESTS`와 `RateLimitedException`을 쓴다.
- 인증번호를 서버 키 HMAC으로 바꾸거나 인증 기록 정리 작업을 두려면 이 결정을 대체한다.
