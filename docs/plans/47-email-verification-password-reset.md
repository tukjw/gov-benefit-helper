status: in-progress

# 구현 계획 — #47 가입 전 이메일 인증과 비밀번호 재설정 제공

브랜치: `feat/47-email-verification-password-reset` (develop `d41c152` 기준)

## 목표 및 범위

- 목표: 가입 폼 안에서 인증번호로 이메일 소유를 확인한 뒤에만 계정을 만든다(확인 후 가입). 비밀번호를 잊은 사용자는 메일로 받은 일회용 링크로 비밀번호를 재설정하고, 재설정하면 그 계정의 리프레시 토큰을 모두 폐기한다. 메일 발송은 교체할 수 있는 구현 뒤에 두고 테스트는 가짜 발송으로 검증한다.
- 포함:
  - 인증번호 요청·확인 API, 가입 API의 가입 티켓 요구.
  - 비밀번호 재설정 요청·재설정 API.
  - 테이블 `email_verification`, `password_reset_token`과 Flyway 마이그레이션.
  - 인증번호의 유효기간과 시도 횟수 제한, 가입 티켓의 유효기간과 1회 사용, 재설정 토큰의 유효기간과 1회 사용.
  - 인증·재설정 메일의 발송 제한(이메일별 60초 간격, 최근 24시간 10회).
  - 메일 발송 인터페이스와 local 로그 구현, 테스트용 가짜 구현.
  - 새 오류 코드와 `Retry-After` 헤더.
  - 결정 문서 0019(가입 전 이메일 인증과 비밀번호 재설정)·0020(메일 발송 구현 경계). 0009 갱신. `DEC-AUTH`와 `REQ-ACCOUNT-01`을 '확인 후 가입'으로 바꿈.
  - API 명세: 새 API, 가입 요청의 `verificationToken`, `ApiDocsTest` 검증.
- 제외:
  - 실제 메일 발송 구현과 발송 서비스 선택(`DEC-OPS`). 후속 이슈다. 비동기 발송으로 바꾸는 일도 그 이슈에서 한다(설계 8).
  - 인증 기록의 삭제·정리 작업(2026-10-16 사용자 결정, 설계 10).
  - IP별 발송 제한, 로그인 실패 속도 제한: 로그인 속도 제한 이슈.
  - 재설정 뒤 이미 발급된 액세스 토큰의 즉시 무효화: 로그아웃과 같은 `DEC-AUTH`의 한계로 둔다.
  - 재설정 화면과 가입 폼(프론트): 프론트 이슈. 링크 형식은 0019에 적는다.
  - 비밀번호 해시 계산 중 DB 커넥션 점유: #41. 재설정도 가입과 같은 방식(트랜잭션 안에서 계산)으로 두고 #41에서 함께 고친다.

## 확인된 사실과 제약

- `AccountService.signUp`은 이메일 중복을 먼저 조회하고, 동시 가입은 unique 제약 위반(`uk_user_account_email`)을 409 `DUPLICATE_EMAIL`로 바꾼다.
- `SecurityConfig`는 `/api/auth/**`를 공개 경로로 둔다. 새 API도 그 아래에 두므로 보안 설정을 바꾸지 않는다. 새 API는 쿠키를 쓰지 않아 CSRF 방어(0017)가 필요 없다.
- `RefreshTokenValues`가 32바이트 난수의 base64url(43자) 생성, 형식 검사, SHA-256 소문자 hex 해시를 제공한다. 가입 티켓과 재설정 토큰도 같은 값이 필요하다.
- `BusinessException`은 오류 코드만 갖고, `GlobalExceptionHandler.handleBusiness`는 `UNAUTHENTICATED`에만 헤더(`WWW-Authenticate`)를 붙인다. `Retry-After`를 붙일 경로가 없다.
- `GlobalExceptionHandler.SENSITIVE_FIELDS`는 `password`뿐이다. 입력 오류 응답에 `newPassword`·`token`·`verificationToken` 값이 그대로 나간다.
- `Clock` 빈은 `CollectionConfig`에 있다(`Asia/Seoul`). 테스트는 `kr.govbenefit.MutableClock`을 `@Primary` 빈으로 넣어 시각을 옮긴다(`RefreshTokenApiTest`).
- `@SpringBootTest` 16개가 모두 `TestcontainersConfiguration`을 import한다. 프로필은 쓰지 않고 `src/test/resources/config/application.yaml`로 설정을 덮는다.
- 컴포넌트 스캔은 테스트 클래스패스의 `kr.govbenefit` 패키지도 훑는다. `@TestConfiguration`만 제외된다.
- `SignUpApiTest`·`LoginApiTest`·`RefreshTokenApiTest`·`ApiDocsTest`는 `/api/auth/signup`을 호출하고, `SignUpConcurrencyTest`·`RefreshTokenConcurrencyTest`는 `AccountService.signUp`을 직접 부른다. 가입에 티켓이 필요해지면 준비 단계를 바꿔야 한다.
- 테스트 `setUp`은 `DELETE FROM user_account`로 정리한다. `user_id` FK에 `ON DELETE CASCADE`가 있는 테이블은 함께 지워지고, `email_verification`은 FK가 없어 따로 지워야 한다.
- MySQL InnoDB 기본 격리 수준은 `REPEATABLE READ`다. `UPDATE`는 최신 커밋된 행을 읽고 잠그지만(current read), 같은 트랜잭션의 일반 `SELECT`는 첫 읽기 시점의 스냅샷을 본다(#45 플랜).
- 외부 연동 구현은 `collection.client`처럼 도메인 패키지 아래 별도 패키지에 둔다. 메일 발송은 `account.mail`에 둔다.
- 공개 전이라 실제 사용자가 없다. #38·#45 기간에 로컬에서 만든 계정은 그대로 쓴다. 계정 테이블에 확인 여부 컬럼을 두지 않는다(만들어진 계정은 모두 확인을 마친 계정이다).
- `local` 없이 실행하는 프로필(예: `collect` 단독)은 메일 발송 구현이 없어 기동에 실패하게 된다(설계 8). 저장소 지침의 실행 방법은 `local` 프로필이다.
- 채택된 결정 문서 본문은 고치지 않는다. 0017 `적용 위치`의 `RefreshTokenValues.java`는 이름 변경(설계 3) 때문에 깨진 경로가 되므로, 깨진 링크 수정 규칙에 따라 고친다.

## 설계 결정

1. **확인하면 일회용 가입 티켓을 발급하고, 가입 요청은 그 티켓을 본문에 담는다.** 결정 문서 0019.
   - `confirm`이 인증번호를 맞히면 티켓(난수 43자)을 돌려준다. `signup`은 `email`, `password`, `verificationToken`을 받는다.
   - 티켓은 확인한 이메일과 같은 이메일로만 쓸 수 있고, 30분 동안 유효하며, 한 번 쓰면 소비된다.
   - 대안: 서버에 "이 이메일은 확인됨"만 기록하고 가입은 이메일만 보는 방식. 주인이 확인한 직후 유효기간 안에 다른 사람이 같은 이메일과 자기 비밀번호로 먼저 가입할 수 있어 선점 문제가 남는다.
   - 대안: 가입 요청에 인증번호를 다시 넣는 방식. 번호 검사가 두 곳에서 일어나 시도 횟수를 어디서 세는지 모호하고, "확인 결과의 유효기간"이 번호의 유효기간과 같아진다.
   - 대안: 서명한 stateless 티켓(JWT). 한 번만 쓰게 하려면 결국 식별자를 저장해야 한다.
   - 티켓은 쿠키가 아니라 본문으로 주고받는다. 쿠키면 CSRF 방어가 필요하고, 이후 네이티브 앱에서 같은 API를 쓰기 어렵다.
2. **저장 모델.** 결정 문서 0019.
   - `email_verification`: 인증번호 발송 한 번에 한 행. 같은 행에 확인 결과(티켓 해시·만료·사용 시각)를 둔다. 계정이 생기기 전이라 `email`만 있고 FK가 없다.
   - `password_reset_token`: 재설정 메일 한 번에 한 행. `user_id`가 `user_account`를 `ON DELETE CASCADE`로 참조한다.
   - 두 테이블 모두 **같은 이메일(계정)의 가장 최근 행만 유효**하다. 다시 받으면 이전 번호와 이전 링크가 따로 표시하지 않아도 무효가 된다. 이미 발급된 가입 티켓은 이후에 번호를 다시 받아도 유효하다(티켓은 확인한 순간의 결과다).
   - 발송 제한(설계 6)은 이 행들의 `created_at`으로 센다. 별도 카운터 테이블을 두지 않는다.
   - 대안: 한 테이블에 `purpose` 컬럼. 가입 인증은 `email`만, 재설정은 `user_id`와 FK가 필요해 nullable 컬럼과 조건 분기가 생긴다.
   - 대안: Redis TTL. 스택에 없다(`DEC-OPS`: 근거가 생기면 선택).
3. **값과 해시.**
   - 인증번호: `SecureRandom.nextInt(1_000_000)`을 6자리로 채운 숫자(`000000`~`999999`). DB에는 SHA-256 소문자 hex만 저장한다(2026-10-16 사용자 결정).
     - 한계: 경우의 수가 10^6이라 DB가 유출되면 해시로 번호를 바로 역산할 수 있다. 번호는 10분 뒤 만료되고 시도 횟수 제한이 따로 막는다는 점에 기댄다. 0019 근거에 적는다.
     - 해시 비교는 `MessageDigest.isEqual`로 한다.
   - 가입 티켓·재설정 토큰: 리프레시 토큰과 같은 32바이트 난수의 base64url(43자)과 SHA-256 hex. `RefreshTokenValues`를 `OpaqueTokens`로 이름을 바꿔 세 곳이 함께 쓴다. 이름만 바꾸고 동작은 바꾸지 않는다.
4. **인증번호 확인의 판정 순서와 시도 횟수.** `EmailVerificationService.confirm`이 한 트랜잭션에서 처리한다.
   1. 그 이메일의 가장 최근 행을 찾는다. 없으면 `VERIFICATION_CODE_EXPIRED`.
   2. `verified_at`이 있으면(이미 쓴 번호) `VERIFICATION_CODE_EXPIRED`.
   3. `now >= expires_at`이면 `VERIFICATION_CODE_EXPIRED`. 만료된 번호는 시도로 세지 않는다.
   4. **시도를 먼저 예약한다**: `UPDATE email_verification SET attempts = attempts + 1 WHERE id = ? AND attempts < :max AND verified_at IS NULL`. 0행이면 `VERIFICATION_ATTEMPTS_EXCEEDED`.
   5. 해시가 다르면 `VERIFICATION_CODE_MISMATCH`.
   6. 맞으면 `UPDATE … SET verified_at = ?, ticket_hash = ?, ticket_expires_at = ? WHERE id = ? AND verified_at IS NULL`. 1행이면 티켓을 돌려주고, 0행이면(동시에 다른 요청이 확인함) `VERIFICATION_CODE_EXPIRED`.
   - 시도 횟수는 틀린 입력이 아니라 **비교한 횟수**로 센다. 맞히면 그 번호는 끝나므로 경계는 완료 조건과 같다. 4번 틀린 뒤 5번째에 맞히면 성공하고, 5번 틀린 뒤에는 맞는 번호도 거절한다.
   - 비교 전에 예약하는 이유: 틀린 뒤에 세면 동시 요청 수십 개가 모두 `attempts = 0`을 읽고 비교하므로 제한보다 많이 추측할 수 있다. 조건부 UPDATE는 행을 잠그고 최신 값을 보므로 동시 요청이 몇 개든 비교는 최대 5번이다.
   - 거절해도 예약한 시도는 커밋되어야 한다. #45 설계 6처럼 서비스는 예외를 던지지 않고 결과(`ConfirmOutcome`: `Confirmed(티켓, 만료 시각)` 또는 `Rejected(ErrorCode)`)를 돌려주고, 컨트롤러가 `BusinessException`을 던진다.
   - 4단계 0행에는 동시에 다른 요청이 확인을 마친 경우도 섞인다. 같은 트랜잭션에서 다시 읽어도 스냅샷이라 구분할 수 없으므로 `VERIFICATION_ATTEMPTS_EXCEEDED`로 둔다. 정상 사용자에게는 거의 일어나지 않는다.
   - 유효 판정은 `now < expires_at`이다. 발급 후 정확히 10분이 된 순간부터 거절한다. 티켓과 재설정 토큰도 같다.
5. **가입은 티켓을 먼저 검사하고 그다음 이메일 중복을 본다.** `AccountService.signUp`.
   1. 티켓 형식이 틀렸거나, 해시로 찾은 행이 없거나, `ticket_used_at`이 있거나, `now >= ticket_expires_at`이거나, 행의 `email`이 요청 이메일(정규화 후)과 다르면 403 `EMAIL_NOT_VERIFIED`.
   2. 이미 가입된 이메일이면 409 `DUPLICATE_EMAIL`(지금과 같음).
   3. `UPDATE … SET ticket_used_at = ? WHERE id = ? AND ticket_used_at IS NULL`이 0행이면 `EMAIL_NOT_VERIFIED`. 같은 티켓의 동시 가입은 여기서 하나만 통과한다.
   4. 계정을 만든다. unique 제약 위반은 지금처럼 409로 바꾼다.
   - 순서의 이유: 중복 검사를 먼저 하면 티켓 없이도 409로 가입 여부를 조회할 수 있다. 티켓을 먼저 보면 409는 그 메일함의 주인만 본다.
   - 2·4단계의 409는 트랜잭션을 롤백하므로 티켓 소비도 되돌려진다. 다시 시도해도 409라 문제가 없다.
   - 이미 가입된 이메일로 인증번호를 요청해도 구분하지 않고 번호를 보낸다(요청 단계에서 가입 여부를 드러내지 않음). 그 사람은 확인한 뒤 가입할 때 409를 본다.
6. **발송 제한.** 결정 문서 0019.
   - 같은 이메일(재설정은 같은 계정)의 마지막 발송에서 `auth.mail.cooldown`(60초)이 지나지 않았거나, 최근 `auth.mail.window`(24시간) 안의 발송이 `auth.mail.max-per-window`(10회) 이상이면 보내지 않는다. 가입 인증과 재설정은 각자의 테이블로 따로 센다.
   - 최근 24시간은 `created_at > now - 24h`다. 한국 날짜 기준이 아니라서 자정 직전·직후에 몰아 상한의 두 배를 보낼 수 없다(2026-10-16 사용자 결정).
   - 판정은 순수 함수 `SendRateLimit.retryAfter(now, 마지막 발송, 창 안 발송 수, 창 안 가장 오래된 발송)`로 두고 단위 테스트한다. 다시 보낼 수 있는 시각은 간격이면 `마지막 + 60초`, 상한이면 `가장 오래된 발송 + 24시간`이다.
   - 가입 인증: 429 `TOO_MANY_REQUESTS`와 `Retry-After`(초, 올림, 최소 1). 이메일 소유와 무관한 응답이라 드러나는 정보가 없다.
   - 재설정: 204를 그대로 주고 메일만 보내지 않는다. 가입된 이메일에서만 429가 나면 가입 여부가 드러난다.
   - 한계: 같은 이메일의 동시 요청은 판정을 함께 통과해 몇 통 더 나갈 수 있다. 첫 발송에는 잠글 행이 없어 막으려면 별도 잠금이 필요하다. 간격 제한의 목적(메일 폭탄, 재발송으로 추측 기회 늘리기)에는 영향이 작다.
   - 429 코드는 로그인 속도 제한에서도 쓸 수 있게 범용 이름으로 둔다.
7. **비밀번호 재설정.** 결정 문서 0019.
   - 요청 `POST /api/auth/password-reset`: 가입된 이메일이고 발송 제한에 걸리지 않으면 토큰 행을 만들고 메일을 보낸다. 가입 여부·제한과 관계없이 204, 본문 없음이다.
   - 링크는 `auth.password-reset.url` + `#token=<토큰>`이다(`https://govbenefit.kr/reset-password#token=…`). fragment는 브라우저가 서버로 보내지 않아 프론트 호스팅 접근 로그와 Referer에 남지 않는다. 개인 화면은 브라우저에서 렌더링하므로(`DEC-APP`) 스크립트가 읽으면 된다(2026-10-16 사용자 결정).
   - 재설정 `POST /api/auth/password-reset/confirm`(`token`, `newPassword`):
     1. 형식이 틀렸거나, 해시로 찾은 행이 없거나, `used_at`이 있거나, `now >= expires_at`이거나, 그 계정의 가장 최근 행이 아니면 400 `INVALID_RESET_TOKEN`. 사유는 나누지 않는다.
     2. `UPDATE … SET used_at = ? WHERE id = ? AND used_at IS NULL`이 0행이면 `INVALID_RESET_TOKEN`.
     3. 비밀번호 해시를 바꾼다(`UserAccount.changePassword`).
     4. `RefreshTokenRepository.revokeAllByUserId`로 그 계정의 폐기되지 않은 리프레시 토큰을 모두 폐기한다(0017 영향).
     5. 204. 토큰을 발급하지 않으므로 사용자는 새 비밀번호로 다시 로그인한다(2026-10-16 사용자 결정).
   - 재설정의 거절은 쓰기 전에 일어나므로 예외를 바로 던진다. 2단계 이후의 실패는 모두 롤백된다.
   - 토큰 엔트로피가 256비트라 시도 횟수 제한을 두지 않는다.
   - 한계: 재설정 뒤에도 이미 발급된 액세스 토큰은 만료(최대 15분)까지 유효하다. 로그아웃과 같은 `DEC-AUTH`의 한계다.
8. **메일 발송 구현 경계.** 결정 문서 0020.
   - 인터페이스 `account.mail.AccountMailSender`: `sendSignupCode(String email, String code, Duration validFor)`, `sendPasswordReset(String email, String resetUrl, Duration validFor)`. 메일 종류별 메서드라 가짜 구현이 번호와 링크를 구조화해 기록하고, 실제 발송이 SMTP든 HTTP API든 상관없다.
   - 서비스는 이벤트(`SignupCodeIssued`, `PasswordResetIssued`)만 발행하고, `AccountMailListener`가 `@TransactionalEventListener(phase = AFTER_COMMIT)`에서 **동기로** 발송한다. 롤백된 요청의 메일이 나가지 않는다.
   - 리스너는 발송 예외를 잡아 오류 로그(행 ID만, 이메일·번호·링크 없음)를 남기고 삼킨다. 커밋 뒤라 응답을 실패로 바꿔도 행은 남고, 재설정에서 가입된 이메일만 500이 나면 가입 여부가 드러난다.
   - 구현 선택: `auth.mail.sender=logging`이면 `LoggingAccountMailSender`(번호와 링크를 INFO 로그로 남김)를 등록하고, `application-local.yaml`에서만 켠다. 그 밖의 프로필은 구현이 없어 기동에 실패한다. 실제 발송 구현이 들어올 때까지 운영 설정에서 번호가 로그로 새는 일을 막는다(2026-10-16 사용자 결정).
   - 테스트: 테스트 소스의 `@Component` `RecordingAccountMailSender`가 컴포넌트 스캔으로 모든 `@SpringBootTest`에 들어간다. 16개 테스트에 import를 더하지 않기 위해서다. 실제 메일을 보내지 않는다.
   - 한계: 실제 발송이 느리면 재설정 요청의 응답 시간으로 가입 여부가 드러날 수 있다. 실제 발송 이슈에서 비동기로 바꾼다. 0020 영향에 적는다.
   - 대안: 범용 `send(to, subject, body)`. 테스트가 본문을 파싱해 번호를 뽑아야 한다.
   - 대안: `JavaMailSender` + GreenMail. 발송 서비스가 HTTP API 방식이면 쓸모가 없어진다.
9. **응답과 오류 코드.**

   | API | 성공 | 실패 |
   | --- | --- | --- |
   | `POST /api/auth/email-verification` (`email`) | 204 | 400 `INVALID_INPUT`, 429 `TOO_MANY_REQUESTS` |
   | `POST /api/auth/email-verification/confirm` (`email`, `code`) | 200 `{verificationToken, expiresAt}` | 400 `INVALID_INPUT`·`VERIFICATION_CODE_MISMATCH`·`VERIFICATION_CODE_EXPIRED`·`VERIFICATION_ATTEMPTS_EXCEEDED` |
   | `POST /api/auth/signup` (`email`, `password`, `verificationToken`) | 201 (지금과 같음) | 400 `INVALID_INPUT`, 403 `EMAIL_NOT_VERIFIED`, 409 `DUPLICATE_EMAIL` |
   | `POST /api/auth/password-reset` (`email`) | 204 | 400 `INVALID_INPUT` |
   | `POST /api/auth/password-reset/confirm` (`token`, `newPassword`) | 204 | 400 `INVALID_INPUT`·`INVALID_RESET_TOKEN` |

   - 인증번호 실패는 클라이언트의 다음 행동이 달라 나눈다. `MISMATCH`면 다시 입력하고, `EXPIRED`(만료·이미 씀·다시 받아 무효·요청 내역 없음)와 `ATTEMPTS_EXCEEDED`면 번호를 다시 받는다. 인증번호는 가입 여부와 무관해 나눠도 드러나는 정보가 없다.
   - 가입 티켓과 재설정 토큰의 실패는 하나로 합친다. 어느 경우든 처음부터 다시 해야 한다.
   - `code`는 `@Pattern("\\d{6}")`. 형식이 틀린 번호는 400 `INVALID_INPUT`이고 시도로 세지 않는다.
   - `newPassword`는 `@Password`(가입과 같은 8자 이상, UTF-8 72바이트 이하).
   - `expiresAt`은 결정 0010의 오프셋 표기(`ApiTimes`)다.
   - `Retry-After`: `RateLimitedException extends BusinessException`이 `Duration retryAfter`를 갖고, `GlobalExceptionHandler.handleBusiness`가 이 예외에 헤더를 붙인다.
   - 입력 오류 응답에서 값을 빼는 항목(`SENSITIVE_FIELDS`)에 `newPassword`, `token`, `verificationToken`을 더한다. 요청 DTO의 `toString`도 이 값을 가린다.
10. **인증 기록은 지우지 않는다(2026-10-16 사용자 결정).**
    - 가입에 성공해도, 가입하지 않았어도 `email_verification` 행을 남긴다. 재설정 토큰은 탈퇴하면 FK로 함께 지워진다.
    - 한계: 가입하지 않은 사람의 이메일이 계속 쌓이고, 탈퇴한 사람의 이메일도 인증 기록에 남는다. `DEC-AUTH`는 탈퇴하면 데이터를 즉시 삭제한다고 하므로 탈퇴 이슈에서 이메일 기준 삭제를 다시 판단한다. 0019 영향과 `DEC-AUTH` 한계에 적는다.

## 단계별 구현 계획

1. **마이그레이션**
   - `V6__create_email_verification.sql`
     ```sql
     -- 가입 전 이메일 인증(#47, 결정 0019). 발송 한 번에 한 행이며 같은 이메일의 가장 최근 행만 유효하다.
     -- 인증번호와 가입 티켓은 SHA-256(소문자 hex)만 둔다. 계정이 생기기 전이라 FK가 없고, 행을 지우지 않는다.
     CREATE TABLE email_verification (
         id                BIGINT       NOT NULL AUTO_INCREMENT,
         email             VARCHAR(254) NOT NULL,
         code_hash         CHAR(64)     NOT NULL,
         attempts          INT          NOT NULL DEFAULT 0,
         created_at        DATETIME(6)  NOT NULL,
         expires_at        DATETIME(6)  NOT NULL,
         verified_at       DATETIME(6)  NULL,
         ticket_hash       CHAR(64)     NULL,
         ticket_expires_at DATETIME(6)  NULL,
         ticket_used_at    DATETIME(6)  NULL,
         PRIMARY KEY (id),
         CONSTRAINT uk_email_verification_ticket_hash UNIQUE (ticket_hash),
         INDEX idx_email_verification_email_created (email, created_at)
     );
     ```
   - `V7__create_password_reset_token.sql`
     ```sql
     -- 비밀번호 재설정 토큰(#47, 결정 0019). 발송 한 번에 한 행이며 같은 계정의 가장 최근 행만 유효하다.
     CREATE TABLE password_reset_token (
         id         BIGINT      NOT NULL AUTO_INCREMENT,
         user_id    BIGINT      NOT NULL,
         token_hash CHAR(64)    NOT NULL,
         created_at DATETIME(6) NOT NULL,
         expires_at DATETIME(6) NOT NULL,
         used_at    DATETIME(6) NULL,
         PRIMARY KEY (id),
         CONSTRAINT uk_password_reset_token_hash UNIQUE (token_hash),
         CONSTRAINT fk_password_reset_token_user FOREIGN KEY (user_id) REFERENCES user_account (id) ON DELETE CASCADE,
         INDEX idx_password_reset_token_user_created (user_id, created_at)
     );
     ```
2. **설정**
   - `application.yaml`
     ```yaml
     auth:
       email-verification:
         code-ttl: 10m
         max-attempts: 5
         ticket-ttl: 30m
       password-reset:
         token-ttl: 30m
         # 프론트의 재설정 화면(DEC-APP). 토큰은 #token=으로 붙인다(결정 0019).
         url: https://govbenefit.kr/reset-password
       mail:
         cooldown: 60s
         window: 24h
         max-per-window: 10
         # 발송 구현(결정 0020). 지정하지 않으면 구현이 없어 기동에 실패한다. local만 logging을 쓴다.
         # sender:
     ```
   - `application-local.yaml`: `auth.password-reset.url: http://localhost:3000/reset-password`, `auth.mail.sender: logging`.
3. **공통 API** `kr.govbenefit.common.api`
   - `ErrorCode`: `VERIFICATION_CODE_MISMATCH`·`VERIFICATION_CODE_EXPIRED`·`VERIFICATION_ATTEMPTS_EXCEEDED`·`INVALID_RESET_TOKEN`(400), `EMAIL_NOT_VERIFIED`(403), `TOO_MANY_REQUESTS`(429). 각 값에 설계 9의 의미를 주석으로 단다.
   - `ApiErrorResponse.code`의 `@Schema` 설명과 `allowableValues`에 추가.
   - `RateLimitedException`(`BusinessException` 상속, `TOO_MANY_REQUESTS` 고정, `Duration retryAfter()`).
   - `GlobalExceptionHandler`: `handleBusiness`에서 `RateLimitedException`이면 `Retry-After`(초, 올림, 최소 1). `SENSITIVE_FIELDS`에 `newPassword`, `token`, `verificationToken`.
4. **계정 도메인** `kr.govbenefit.account.domain`
   - `RefreshTokenValues` → `OpaqueTokens`(이름만 변경, 클래스 주석을 리프레시 토큰·가입 티켓·재설정 토큰 공용으로). `RefreshTokenService`의 참조를 고친다.
   - `VerificationCodes`: `String generate()`, `String hash(String)`(`OpaqueTokens.hash` 사용), `boolean matches(String code, String hash)`(`MessageDigest.isEqual`).
   - `SendRateLimit`(record: `cooldown`, `window`, `maxPerWindow`): `Optional<Duration> retryAfter(LocalDateTime now, Optional<LocalDateTime> lastSentAt, long sentInWindow, Optional<LocalDateTime> oldestInWindow)`.
   - `EmailVerification`(엔티티): 컬럼은 V6과 같다. `static issue(String email, String codeHash, LocalDateTime now, Duration ttl)`, `isVerified()`, `isExpiredAt(LocalDateTime)`, `isTicketUsableAt(LocalDateTime, String email)`.
   - `PasswordResetToken`(엔티티): `static issue(Long userId, String tokenHash, LocalDateTime now, Duration ttl)`, `isUsed()`, `isExpiredAt(LocalDateTime)`.
   - `UserAccount.changePassword(String passwordHash)`.
5. **저장소** `kr.govbenefit.account.repository`
   - `EmailVerificationRepository`
     - `Optional<EmailVerification> findFirstByEmailOrderByCreatedAtDescIdDesc(String email)`
     - `Optional<EmailVerification> findByTicketHash(String ticketHash)`
     - `long countByEmailAndCreatedAtAfter(String email, LocalDateTime after)`
     - `Optional<EmailVerification> findFirstByEmailAndCreatedAtAfterOrderByCreatedAtAscIdAsc(String email, LocalDateTime after)`
     - `@Modifying int reserveAttempt(Long id, int maxAttempts)`: 설계 4의 4단계.
     - `@Modifying int markVerified(Long id, String ticketHash, LocalDateTime ticketExpiresAt, LocalDateTime now)`: 설계 4의 6단계.
     - `@Modifying int useTicket(Long id, LocalDateTime now)`: 설계 5의 3단계.
   - `PasswordResetTokenRepository`
     - `Optional<PasswordResetToken> findByTokenHash(String)`
     - `boolean existsByUserIdAndIdGreaterThan(Long userId, Long id)`: 가장 최근 행 판정.
     - `Optional<PasswordResetToken> findFirstByUserIdOrderByCreatedAtDescIdDesc(Long userId)`, `long countByUserIdAndCreatedAtAfter(...)`, `Optional<PasswordResetToken> findFirstByUserIdAndCreatedAtAfterOrderByCreatedAtAscIdAsc(...)`
     - `@Modifying int markUsed(Long id, LocalDateTime now)`
   - `RefreshTokenRepository.revokeAllByUserId(Long userId, LocalDateTime now)`: `revoked_at IS NULL`인 행만.
   - 조건부 UPDATE는 기존처럼 `@Modifying(flushAutomatically = true, clearAutomatically = true)`.
6. **설정 클래스** `kr.govbenefit.account.config`
   - `EmailVerificationProperties`(`auth.email-verification`: `codeTtl`, `maxAttempts`, `ticketTtl`), `PasswordResetProperties`(`auth.password-reset`: `tokenTtl`, `url`), `AccountMailProperties`(`auth.mail`: `cooldown`, `window`, `maxPerWindow`, `toSendRateLimit()`). `AccountConfig`가 활성화한다.
   - `AccountMailConfig`: `@ConditionalOnProperty(name = "auth.mail.sender", havingValue = "logging")`로 `LoggingAccountMailSender` 빈.
7. **메일** `kr.govbenefit.account.mail`
   - `AccountMailSender`(인터페이스), `LoggingAccountMailSender`, 이벤트 record `SignupCodeIssued(Long verificationId, String email, String code, Duration validFor)`·`PasswordResetIssued(Long tokenId, String email, String resetUrl, Duration validFor)`, `AccountMailListener`(설계 8). 이벤트 record의 `toString`은 번호와 URL을 가린다.
8. **서비스** `kr.govbenefit.account.service`
   - `EmailVerificationService`
     - `void request(String email)`: 정규화 → 발송 제한(설계 6, 걸리면 `RateLimitedException`) → 행 저장 → `SignupCodeIssued` 발행.
     - `ConfirmOutcome confirm(String email, String code)`: 설계 4. `ConfirmOutcome`은 sealed interface(`Confirmed(String verificationToken, LocalDateTime expiresAt)`, `Rejected(ErrorCode code)`).
     - `EmailVerification checkTicket(String email, String verificationToken)`: 설계 5의 1단계. 쓸 수 없으면 `BusinessException(EMAIL_NOT_VERIFIED)`.
     - `void useTicket(EmailVerification verification)`: 설계 5의 3단계. 0행이면 `BusinessException(EMAIL_NOT_VERIFIED)`.
     - `AccountService.signUp`이 두 메서드 사이에 중복 검사(설계 5의 2단계)를 둔다. 둘 다 쓰기 전에 거절하므로 예외를 바로 던진다.
   - `PasswordResetService`
     - `void request(String email)`: 설계 7. 계정이 없거나 제한에 걸리면 아무것도 하지 않는다.
     - `void reset(String token, String newPassword)`: 설계 7의 1~4단계.
   - `AccountService.signUp`: 설계 5의 순서로 바꾼다. 클래스 주석에 이메일 인증과 재설정을 더한다.
   - 시각은 모두 `Clock` 빈으로 정한다.
9. **DTO와 컨트롤러** `kr.govbenefit.account`
   - `dto.EmailVerificationRequest(email)`: 이메일 제약은 `SignUpRequest`와 같다(`@NotBlank`, `@Email`, `@Size(max = 254)`).
   - `dto.EmailVerificationConfirmRequest(email, code)`, `dto.EmailVerificationConfirmResponse(verificationToken, expiresAt)`.
   - `dto.SignUpRequest`: `verificationToken`(`@NotBlank`) 추가, `toString`에서 가림.
   - `dto.PasswordResetRequest(email)`, `dto.PasswordResetConfirmRequest(token, newPassword)`(`@NotBlank`, `@Password`, `toString`에서 가림).
   - `controller.EmailVerificationController`(`/api/auth/email-verification`, `/confirm`), `controller.PasswordResetController`(`/api/auth/password-reset`, `/confirm`). `AuthController`와 같은 `@Tag("계정")`을 쓰고 태그 설명에 이메일 인증·비밀번호 재설정을 더한다.
   - `@Operation`·`@ApiResponse`: 설계 9의 상태 코드, 429의 `Retry-After` 헤더(`@Header`), 가입 설명에 "먼저 이메일 인증으로 받은 `verificationToken`을 넣는다", 재설정 요청 설명에 "가입 여부와 관계없이 204", 확인 실패 코드별 클라이언트 동작.
10. **API 명세** `OpenApiConfig.DESCRIPTION`에 가입 순서(인증번호 요청 → 확인 → 가입)와 비밀번호 재설정 순서를 더한다.
11. **테스트**
    - 테스트 지원
      - `kr.govbenefit.account.mail.RecordingAccountMailSender`(테스트 소스, `@Component`): 보낸 메일 목록, `clear()`, `lastSignupCode(email)`, `lastResetToken(email)`(URL fragment에서 추출).
      - `kr.govbenefit.account.SignUpSteps`(테스트 소스): MockMvc로 인증번호 요청 → 가짜 발송에서 번호 확인 → 확인 → 가입을 한 번에 하는 도우미. 서비스 수준 테스트용으로 `EmailVerificationService`를 직접 부르는 메서드도 둔다.
      - 계정 테스트 `setUp`에 `DELETE FROM email_verification`과 `RecordingAccountMailSender.clear()`를 더한다.
    - 단위
      - `OpaqueTokensTest`: `RefreshTokenValuesTest`의 이름만 바꾼다.
      - `VerificationCodesTest`: 6자리 숫자, 앞자리 0 유지(`nextInt` 결과 7 → `000007`), 해시가 SHA-256 hex, `matches`.
      - `SendRateLimitTest`: 마지막 발송 + 59초는 1초 뒤, + 60초는 허용. 창 안 9회는 허용, 10회는 가장 오래된 발송 + 24시간까지 남은 시간.
      - `AccountMailConfigTest`(`ApplicationContextRunner`): `auth.mail.sender=logging`이면 `LoggingAccountMailSender` 빈, 지정하지 않으면 `AccountMailSender` 빈 없음.
    - `EmailVerificationApiTest`(`@SpringBootTest`, MockMvc, Testcontainers, `MutableClock`)
      - 인증번호를 요청하면 204이고 가짜 발송에 그 이메일로 메일이 한 건, 번호는 6자리.
      - 맞는 번호로 확인하면 200, 본문 키는 `verificationToken`·`expiresAt`뿐이고 `expiresAt`은 확인 시각 + 30분.
      - 시계를 10분 - 1초 옮기면 확인 성공, 10분 옮기면 `VERIFICATION_CODE_EXPIRED`.
      - 4번 틀린 뒤 맞는 번호는 성공. 5번 틀린 뒤 맞는 번호는 `VERIFICATION_ATTEMPTS_EXCEEDED`. 틀린 응답은 `VERIFICATION_CODE_MISMATCH`.
      - 60초 뒤 다시 받으면 새 번호로 확인 성공, 이전 번호는 `VERIFICATION_CODE_MISMATCH`.
      - 이미 확인한 번호로 다시 확인하면 `VERIFICATION_CODE_EXPIRED`. 요청한 적 없는 이메일도 `VERIFICATION_CODE_EXPIRED`.
      - 형식이 틀린 번호(`12345`, `abcdef`)는 400 `INVALID_INPUT`이고 `attempts`가 늘지 않는다.
      - 발송 제한: 59초 뒤 다시 요청하면 429 `TOO_MANY_REQUESTS`와 `Retry-After: 1`, 60초 뒤는 204. 60초 간격으로 10번 보낸 뒤 11번째는 429이고 `Retry-After`가 첫 발송 + 24시간까지의 초, 첫 발송 + 24시간 - 1초는 429, + 24시간은 204. 거절된 요청은 메일을 기록하지 않는다.
      - DB의 `code_hash`·`ticket_hash`가 원문의 SHA-256이고 원문이 어느 행에도 없다.
    - `SignUpApiTest`(준비 단계만 `SignUpSteps`로 바꾸고 기존 검증은 유지, `MutableClock`을 넣고 다음을 추가)
      - 티켓 없이 가입하면 400 `INVALID_INPUT`, 형식이 틀렸거나 DB에 없는 티켓은 403 `EMAIL_NOT_VERIFIED`.
      - A 이메일로 받은 티켓으로 B 이메일 가입은 403이고 계정이 없다.
      - 한 티켓으로 가입한 뒤 같은 티켓으로 다시 가입하면 403(다른 비밀번호, 대소문자만 다른 이메일 포함).
      - 확인 후 30분 - 1초는 가입 성공, 30분은 403.
      - 이미 가입된 이메일로 인증번호를 요청하면 204이고 메일이 가며, 확인 후 가입하면 409 `DUPLICATE_EMAIL`이고 계정은 하나다.
      - 이미 가입된 이메일로 티켓 없이(가짜 티켓) 가입하면 409가 아니라 403이다(가입 여부를 드러내지 않음).
    - `EmailVerificationConcurrencyTest`(`@SpringBootTest`, Testcontainers): 틀린 번호 20개를 `CountDownLatch`로 동시에 확인하면 `MISMATCH` 5건·`ATTEMPTS_EXCEEDED` 15건이고 `attempts`가 5, 그 뒤 맞는 번호도 `ATTEMPTS_EXCEEDED`. 같은 티켓으로 동시에 8번 가입하면 성공 1건이고 계정은 하나.
    - `SignUpConcurrencyTest`: 같은 이메일로 따로 확인한 티켓 두 개(60초 간격으로 다시 받아 각각 확인)로 동시에 가입하면 계정은 하나이고 나머지는 409. 기존 unique 제약 검증 테스트는 그대로 둔다.
    - `PasswordResetApiTest`(`@SpringBootTest`, MockMvc, Testcontainers, `MutableClock`)
      - 가입된 이메일과 가입되지 않은 이메일로 요청하면 둘 다 204이고 본문이 같으며(비어 있음), 메일은 가입된 이메일에만 한 건. 링크는 `auth.password-reset.url#token=<43자>`.
      - 재설정하면 204, 새 비밀번호로 로그인 200, 이전 비밀번호로 401 `INVALID_CREDENTIALS`.
      - 두 번 로그인해 받은 리프레시 토큰 두 개가 재설정 뒤 모두 재발급 401.
      - 쓴 토큰으로 다시 재설정하면 400 `INVALID_RESET_TOKEN`. 30분 - 1초는 성공, 30분은 거절. 다시 요청한 뒤 이전 토큰은 거절, 새 토큰은 성공. 형식 오류·없는 토큰도 같은 코드.
      - 새 비밀번호가 7자, 73바이트면 400 `INVALID_INPUT`이고 응답에 값이 없으며 토큰이 소비되지 않는다(다시 유효한 비밀번호로 성공).
      - 발송 제한: 60초 안에 다시 요청해도 204지만 메일은 한 건. 10번 뒤 11번째도 204이고 메일은 10건.
      - DB의 `token_hash`가 토큰의 SHA-256이고 원문이 없다.
    - `LoginApiTest`·`RefreshTokenApiTest`·`RefreshTokenConcurrencyTest`: 가입 준비만 `SignUpSteps`로 바꾸고 검증은 바꾸지 않고 통과한다.
    - `ApiDocsTest`: 새 API 네 개의 요청 스키마와 응답 코드(설계 9 + 500), 429의 `Retry-After` 헤더, `SignUpRequest.required`에 `verificationToken`, 가입 응답 코드에 403, 새 오류 코드가 `allowableValues`에 있는지.
      - 구현 중 추가: 이 테스트는 `local` 프로필을 켜므로 로그 구현과 가짜 구현이 함께 등록된다. `@SpringBootTest(properties = "auth.mail.sender=test")`로 로그 구현을 끈다(0020).
    - `AccessTokenAuthTest`·`BenefitSearchApiTest`·`BenefitDetailApiTest`·`ApiDocsClosedTest` 등 나머지는 바꾸지 않고 통과한다.
12. **문서**
    - `docs/decisions/0019-email-verification-password-reset.md`: 설계 1~7·9·10. 확인 후 가입과 티켓, 저장 모델(최근 행만 유효), 값과 해시(SHA-256 인증번호의 한계), 시도 예약 방식, 가입 판정 순서, 발송 제한, 재설정 흐름과 링크 형식(`/reset-password#token=`). 영향: 프론트 가입 폼과 재설정 화면, 429 `Retry-After`를 읽으려면 CORS 이슈에서 노출 헤더에 넣을 것, 탈퇴 이슈의 인증 기록 처리, 로그인 속도 제한이 `TOO_MANY_REQUESTS`를 쓸 것.
    - `docs/decisions/0020-account-mail-sender.md`: 설계 8. 영향: 실제 발송 이슈(`DEC-OPS`)가 `AccountMailSender`를 구현하고 비동기로 바꿀 것, 알림 메일이 생기면 이 경계를 넓힐지 판단할 것.
    - `docs/decisions/README.md` 목록 표에 0019·0020.
    - `0009`: 코드 표에 새 코드 여섯 개, 적용 위치에 `RateLimitedException`과 `Retry-After`.
    - `0017`: 적용 위치의 `RefreshTokenValues.java`를 `OpaqueTokens.java`로(깨진 경로 수정).
    - `docs/product/development.md`의 `DEC-AUTH`(로그인·탈퇴) 행
      - 결정: "이메일 소유 확인과 비밀번호 재설정은 첫 공개 전에 넣고, 소유 확인을 마치지 않은 계정은 개인 기능을 쓸 수 없다"를 "가입할 이메일로 보낸 인증번호를 가입 폼에서 확인한 뒤에만 계정을 만든다(확인 후 가입, 2026-10-16, #47). 비밀번호는 메일로 보낸 일회용 링크로 재설정하고, 재설정하면 그 계정의 리프레시 토큰을 모두 폐기한다"로 바꾼다.
      - 근거: 가입 후 확인에서 바꾼 이유(가입 목적이 곧 개인 기능이라 확인을 미뤄도 마찰이 줄지 않고 미확인 계정과 이메일 선점 문제만 남는다, 2026-10-16, #47).
      - 한계: 가입하지 않은 사람과 탈퇴한 사람의 이메일이 인증 기록에 남는다([결정 0019](../decisions/0019-email-verification-password-reset.md)).
      - 결정 기록 문단에 '확인 후 가입' 변경(2026-10-16, #47)을 한 줄 더한다.
    - `docs/product/requirements.md`의 `REQ-ACCOUNT-01`: "이메일 소유 확인을 마치지 않은 계정의 개인 기능 요청은 거절한다. 확인 수단은 유효기간이 있고 한 번만 쓸 수 있다"를 "가입할 이메일로 받은 인증번호를 확인해야 가입할 수 있다. 인증번호와 확인 결과는 유효기간이 있고 한 번만 쓸 수 있으며, 인증번호는 틀린 입력 횟수를 제한한다. 인증·재설정 메일은 이메일별로 발송 간격과 하루 횟수를 제한한다"로 바꾼다.

## 완료 조건 매핑

가입 전 이메일 인증

- [ ] 인증번호를 요청하면 가짜 발송에 그 이메일로 메일이 한 건 기록된다. → 설계 8. `EmailVerificationApiTest`의 요청.
- [ ] 맞는 인증번호로 확인한 이메일로만 가입할 수 있다. 확인하지 않았거나 확인한 것과 다른 이메일로 가입하면 거절된다. → 설계 1·5. `SignUpApiTest`의 티켓 없음·가짜 티켓·다른 이메일.
- [ ] 유효기간이 지난 인증번호는 거절된다(시계를 고정해 경계값 검증). → 설계 4. `EmailVerificationApiTest`의 10분 - 1초 / 10분.
- [ ] 틀린 입력이 제한 횟수를 넘으면 그 인증번호는 맞는 번호여도 거절된다(제한 직전·직후 경계값 검증). → 설계 4. `EmailVerificationApiTest`의 4번 / 5번 틀린 뒤, `EmailVerificationConcurrencyTest`의 동시 추측.
- [ ] 인증번호를 다시 받으면 새 번호로 확인할 수 있다. → 설계 2. `EmailVerificationApiTest`의 다시 받기.
- [ ] 한 번의 인증 확인으로는 계정을 하나만 만들 수 있고, 확인 결과에도 유효기간이 있다. → 설계 1·5. `SignUpApiTest`의 티켓 재사용·30분 경계, `EmailVerificationConcurrencyTest`의 같은 티켓 동시 가입.
- [ ] 이미 가입된 이메일로 가입하면 409 `DUPLICATE_EMAIL`이고 계정은 하나다. → 설계 5. `SignUpApiTest`, `SignUpConcurrencyTest`.

비밀번호 재설정

- [ ] 재설정을 요청하면 가입된 이메일에만 재설정 메일이 가고, 가입되지 않은 이메일로 요청해도 응답 상태와 본문이 같다. → 설계 7. `PasswordResetApiTest`의 요청.
- [ ] 재설정하면 새 비밀번호로 로그인되고 이전 비밀번호로는 `INVALID_CREDENTIALS`가 난다. → 설계 7. `PasswordResetApiTest`.
- [ ] 재설정하면 그 계정의 모든 로그인에서 받은 리프레시 토큰으로 더는 재발급할 수 없다. → 설계 7. `PasswordResetApiTest`의 두 번 로그인.
- [ ] 이미 쓴 재설정 수단이나 유효기간이 지난 재설정 수단은 거절된다(시계를 고정해 경계값 검증). → 설계 7. `PasswordResetApiTest`의 재사용·30분 - 1초 / 30분.
- [ ] 새 비밀번호에도 가입과 같은 규칙(8자 이상, UTF-8 72바이트 이하)을 적용한다. → 설계 9. `PasswordResetApiTest`의 7자·73바이트.

공통

- [ ] 테스트는 실제 메일을 보내지 않는다. → 설계 8. `RecordingAccountMailSender`만 등록되고 `AccountMailConfigTest`가 기본 설정에 실제 구현이 없음을 확인한다.
- [ ] 로그인·재발급·로그아웃·`GET /api/me`의 응답과 오류 코드는 바뀌지 않는다. 가입 API가 인증 확인을 요구하는 것만 달라진다. → 설계 9. `LoginApiTest`·`RefreshTokenApiTest`·`AccessTokenAuthTest`가 검증을 바꾸지 않고 통과한다.
- [ ] `DEC-AUTH`와 `REQ-ACCOUNT-01`이 '확인 후 가입' 방식으로 바뀌어 있고 바꾼 이유와 날짜가 기록되어 있다. → 단계 12.

## 검증 명령

- `backend/`에서 `./gradlew test`
- 로컬 확인(기본 테스트에 포함하지 않음): `SPRING_PROFILES_ACTIVE=local ./gradlew bootRun` 뒤
  ```sh
  curl -s -i -X POST localhost:8080/api/auth/email-verification -H 'Content-Type: application/json' \
    -d '{"email":"me@example.com"}'
  # 앱 로그의 인증번호로
  curl -s -X POST localhost:8080/api/auth/email-verification/confirm -H 'Content-Type: application/json' \
    -d '{"email":"me@example.com","code":"<번호>"}'
  curl -s -i -X POST localhost:8080/api/auth/signup -H 'Content-Type: application/json' \
    -d '{"email":"me@example.com","password":"correct-horse","verificationToken":"<티켓>"}'
  curl -s -i -X POST localhost:8080/api/auth/password-reset -H 'Content-Type: application/json' \
    -d '{"email":"me@example.com"}'
  # 앱 로그의 링크에서 #token= 뒤의 값으로
  curl -s -i -X POST localhost:8080/api/auth/password-reset/confirm -H 'Content-Type: application/json' \
    -d '{"token":"<토큰>","newPassword":"new-correct-horse"}'
  ```
  다시 요청했을 때의 429와 `Retry-After`, 재설정 뒤 이전 비밀번호 로그인의 401을 본다.

## 결정 필요

없음
