status: in-progress

# 구현 계획 — #51 로그인한 사용자의 회원 탈퇴 제공

브랜치: `feat/51-account-withdrawal` (develop `192a240` 기준)

## 목표 및 범위

- 목표: 로그인한 사용자가 비밀번호를 다시 확인한 뒤 탈퇴하고, 탈퇴하면 계정과 지금 있는 사용자 데이터가 즉시 지워진다(`REQ-ACCOUNT-01`, `DEC-AUTH`).
- 포함:
  - `POST /api/me/withdrawal`(본문에 비밀번호). 성공하면 204와 함께 `refresh_token` 쿠키를 지운다.
  - `user_account` 행 삭제. 프로필·속성·리프레시 토큰·비밀번호 재설정 토큰은 기존 FK `ON DELETE CASCADE`로 함께 지워진다.
  - 오류 코드 `PASSWORD_MISMATCH`(400).
  - 탈퇴는 계정 행을 잠가 프로필 저장·중복 탈퇴와 순서를 정한다(설계 5).
  - API 명세, `ApiDocsTest`, `DEC-AUTH` 행, 결정 0009의 코드 표.
- 제외:
  - 관심 목록·알림 설정·미발송 알림 삭제: 해당 데이터를 추가하는 이슈(이슈 본문).
  - `email_verification`에 남는 이메일 삭제: 결정 0019대로 남긴다(설계 4).
  - 비밀번호 재확인의 시도 횟수 제한: 로그인 속도 제한과 함께 #52(설계 3).
  - 남은 액세스 토큰을 인증 단계에서 일괄 거절: 지금처럼 서비스가 계정 존재를 확인한다(설계 6).
  - 탈퇴 완료 메일: 요구사항에 없다.
  - 탈퇴와 같은 순간에 들어온 로그인·재발급이 500이 되는 경합: 한계로 둔다(설계 5).

## 확인된 사실과 제약

- 사용자 데이터 테이블의 FK는 모두 `ON DELETE CASCADE`다.
  - `refresh_token.user_id`(V5), `password_reset_token.user_id`(V7), `user_profile.user_id`(V9) → `user_account.id`.
  - `user_profile_attribute.user_id`(V9) → `user_profile.user_id`.
  - 결정 0017 영향과 #49 플랜이 탈퇴를 이 CASCADE로 처리한다고 적었다.
- `email_verification`은 계정과 FK가 없고 행을 지우지 않는다(결정 0019, 2026-10-16 사용자 결정). 0019 영향에 "탈퇴 이슈가 이메일 기준 삭제를 판단한다"고 남아 있다. `DEC-AUTH` 한계에 "탈퇴한 사람의 이메일이 인증 기록에 남는다"가 이미 있다.
- 남은 액세스 토큰: `AccountService.me`, `ProfileService.get`·`put`은 계정이 없으면 `UNAUTHENTICATED`(401)를 던진다(`AccessTokenAuthTest.토큰_주인의_계정이_없으면_거절한다`). 액세스 토큰 검증 자체는 DB를 보지 않는다(결정 0014·0018).
- MySQL 8은 auto_increment 카운터를 유지해 ID를 다시 쓰지 않는다. 탈퇴 전 토큰의 `sub`가 같은 이메일로 다시 가입한 사용자를 가리키지 않는다(결정 0014).
- 남은 리프레시 토큰: 행이 지워지므로 `RefreshTokenService.rotate`가 `findByTokenHash`에서 빈 결과를 내고 401이다.
- 리프레시 토큰 쿠키는 `Path=/api/auth`라 `/api/me/**` 요청에는 붙지 않는다. `Set-Cookie`의 `Path`는 쿠키를 보낼 범위일 뿐이므로 `/api/me/withdrawal` 응답에서 `RefreshTokenCookies.clear()`로 지울 수 있다.
- 탈퇴 요청은 `Authorization` 헤더로만 인증하고 쿠키를 읽지 않으므로 CSRF 방어 대상이 아니다(결정 0017의 CSRF 방어는 쿠키로 인증하는 재발급만).
- 동시성(REPEATABLE READ, InnoDB)
  - `ProfileService.put`은 `findByIdForUpdate`로 계정 행을 잠근 뒤 프로필을 쓴다. 탈퇴가 같은 행을 먼저 잠그면 직렬화된다.
  - `AccountService.login`과 `RefreshTokenService.rotate`는 계정 행을 잠그지 않고 `refresh_token`을 INSERT한다. 탈퇴가 먼저 커밋되면 INSERT의 FK 검사가 실패해 500이 된다.
  - 저장소 메서드(`save`)에서 난 예외는 Spring 트랜잭션을 rollback-only로 만든다. 서비스 안에서 잡아 빈 결과로 바꾸면 커밋할 때 `UnexpectedRollbackException`이 난다.
- 로그인 속도 제한은 `DEC-AUTH`에 정해져 있지만 구현되지 않았고 대응 이슈도 없다.

## 설계 결정

1. **삭제는 `user_account` 행 하나를 지우고 DB CASCADE에 맡긴다.** 2026-10-19 사용자 결정.
   - 스키마가 이미 이 방식이고 한 문장이라 원자적이다. 삭제 범위는 `WithdrawalApiTest`가 테이블별 행 수로 확인해, 이후 CASCADE를 빠뜨린 테이블이 생기면 FK 위반으로 드러난다.
   - 대안: 서비스가 테이블마다 삭제. CASCADE와 중복이고 `account`가 `profile` 저장소에 의존하게 된다.
   - 대안: `AccountWithdrawn` 이벤트를 각 도메인이 받아 삭제. 받을 쪽이 아직 없어 과설계다. 관심 목록·알림이 붙을 때도 FK CASCADE로 충분하면 그대로 둔다.
   - soft delete는 `DEC-AUTH`(즉시 삭제, 유예·복구 없음)에 맞지 않는다.
2. **탈퇴는 비밀번호를 다시 확인한다.** 2026-10-19 사용자 결정. `DEC-AUTH` 행에 추가한다.
   - 되돌릴 수 없는 삭제라 방치된 로그인이나 새어 나간 액세스 토큰(최대 15분)만으로 실행되지 않게 한다.
   - 비교는 로그인과 같다. 72바이트를 넘으면 비교하지 않고 불일치다(결정 0013).
   - 대안: 액세스 토큰만으로 탈퇴. 가장 단순하지만 위의 위험이 남는다.
   - 대안: 최근 로그인 시각(`auth_time`) 확인. 액세스 토큰 형식과 리프레시 흐름이 바뀌어 결정 0014를 대체해야 한다.
3. **API는 `POST /api/me/withdrawal`, 불일치는 `PASSWORD_MISMATCH`(400)다.** 2026-10-19 사용자 결정.
   - `DELETE /api/me`는 본문(비밀번호)을 실어야 해 일부 클라이언트·프록시와 맞지 않는다. `/api/auth/**`는 공개 경로라 인증 경계(결정 0018)에 예외가 생긴다.
   - 401(`INVALID_CREDENTIALS`)을 쓰면 프론트가 토큰 만료로 보고 재발급·재로그인 흐름으로 간다. 403은 CSRF·가입 티켓 거절에 쓰여 의미가 섞인다. 같은 성격의 `VERIFICATION_CODE_MISMATCH`가 400이다.
   - 시도 횟수 제한은 두지 않는다. 액세스 토큰을 가진 사람은 `/api/me`로 이메일을 알 수 있고, 제한 없는 로그인 API로 같은 대입을 할 수 있어 이 API가 새 공격면을 만들지 않는다. #52에서 로그인과 함께 다룬다. `DEC-AUTH` 한계에 적는다.
4. **`email_verification` 행은 남긴다.** 2026-10-19 사용자 결정. 결정 0019를 그대로 따르므로 0019·`DEC-AUTH` 문구는 바꾸지 않는다.
   - 대안: 탈퇴할 때 그 이메일의 행 삭제. 개인 데이터가 남지 않지만 인증번호 발송 제한이 초기화되고 이슈 범위가 넓어진다.
5. **탈퇴만 계정 행을 잠그고, 로그인·재발급과의 경합은 한계로 둔다.** 2026-10-19 사용자 결정.
   - 탈퇴: `findByIdForUpdate`로 잠그고 확인한 뒤 지운다. 프로필 저장과 순서가 정해지고, 이미 없으면(동시에 두 번 탈퇴, 탈퇴 뒤 남은 토큰) 401이다.
   - 한계: 탈퇴가 커밋되는 순간에 같은 사용자의 로그인·재발급이 `refresh_token`을 INSERT하면 FK 위반으로 500이 된다. 데이터가 남거나 토큰이 발급되지는 않는다. 비밀번호를 다시 입력해 탈퇴하는 사람이 같은 순간에 다른 탭에서 로그인·재발급해야 생기므로 다루지 않는다.
   - 대안: 로그인·재발급도 계정 행을 잠금. 500을 401로 막지만 기존 로그인·재발급 코드를 바꾸고 요청마다 잠금 쿼리가 붙는다.
   - 대안: FK 위반을 잡아 401로 바꿈. 저장소 예외가 트랜잭션을 rollback-only로 만들어 서비스 안에서 처리할 수 없고, 컨트롤러나 전역 처리기에서 바꾸면 다른 FK 위반까지 숨긴다.
   - 한계: 같은 사용자의 비밀번호 재설정과 탈퇴가 동시에 오면 교착이 생길 수 있다(재설정은 토큰 행 → 계정 행, 탈퇴는 계정 행 → 토큰 행 순서). InnoDB가 한쪽을 롤백해 그 요청이 500이 된다. 같은 이유로 다루지 않는다.
6. **남은 액세스 토큰은 지금처럼 서비스가 계정 존재를 확인해 거절한다.**
   - 완료 조건(프로필 조회·수정 거절)을 이미 충족한다. 인증 단계에서 매 요청 계정을 조회하는 방식은 결정 0018을 바꾸고 모든 요청에 쿼리가 붙어 이 이슈 범위를 넘는다.

## 단계별 구현 계획

1. **오류 코드** `common.api.ErrorCode`
   - `PASSWORD_MISMATCH(HttpStatus.BAD_REQUEST, "비밀번호가 맞지 않습니다.")`. 주석: 탈퇴할 때 다시 입력한 비밀번호가 틀렸다. 같은 요청을 고쳐 보내면 된다.
   - 구현 중 추가: `ApiErrorResponse.code`의 설명과 `allowableValues`에도 넣는다. `ApiDocsTest`가 이 목록이 `ErrorCode`와 같은지 확인한다.
2. **DTO** `account.dto.WithdrawalRequest(String password)`
   - `@NotEmpty(message = "비밀번호를 입력해야 합니다.")`. 길이 규칙은 검사하지 않는다(맞지 않으면 불일치, `LoginRequest`와 같다).
   - `toString()`은 `WithdrawalRequest[password=****]`.
3. **서비스** `account.service.AccountService`
   - `@Transactional void withdraw(Long userId, String password)`
     1. `userAccountRepository.findByIdForUpdate(userId)`. 없으면 `BusinessException(UNAUTHENTICATED)`.
     2. `PasswordRule.isWithinMaxBytes`가 거짓이거나 `passwordEncoder.matches`가 거짓이면 `BusinessException(PASSWORD_MISMATCH)`.
     3. `userAccountRepository.delete(account)`. 로그 `계정을 탈퇴했습니다. userId={}`(info).
   - 클래스 주석의 기능 목록에 탈퇴를 더한다.
4. **컨트롤러** `account.controller.MeController`
   - `RefreshTokenCookies`를 주입한다.
   - `@PostMapping("/withdrawal") ResponseEntity<Void> withdraw(@CurrentUserId Long userId, @Valid @RequestBody WithdrawalRequest request)`: `accountService.withdraw` → `204`, `Set-Cookie: refreshTokenCookies.clear()`.
   - `@Operation(summary = "탈퇴", security = BEARER_AUTH)` 설명: 비밀번호를 다시 확인하고 계정과 프로필·토큰을 즉시 지운다. 되돌릴 수 없다. 같은 이메일로 다시 가입하면 새 사용자다. 이미 받은 액세스 토큰은 버린다.
   - `@ApiResponse`: 204(`Set-Cookie` 헤더 설명은 로그아웃과 같다), 400 `INVALID_INPUT`(비밀번호 없음), 400 `PASSWORD_MISMATCH`(예시), 401 `UNAUTHENTICATED`. 400 두 가지는 한 응답 설명에 함께 적는다.
5. **API 명세**
   - `OpenApiConfig.DESCRIPTION` 첫 줄 목록에 탈퇴를 넣고 항목 하나를 더한다: 탈퇴(`POST /api/me/withdrawal`)는 비밀번호를 다시 받고 계정·프로필·토큰을 즉시 지운다.
   - `AuthController`의 `@Tag(name = "계정")` 설명에 탈퇴를 넣는다.
6. **테스트**
   - `account.controller.WithdrawalApiTest`(`@SpringBootTest`, MockMvc, Testcontainers). 계정은 `SignUpSteps.signUp(email, password)`로 가입하고 `POST /api/auth/login`으로 액세스 토큰과 리프레시 쿠키를 받는다.
     - 탈퇴하면 204이고 `Set-Cookie`가 `refresh_token`을 지운다(`Max-Age=0`, `Path=/api/auth`).
     - 프로필(속성 포함)을 저장하고 재설정 토큰을 발급받은 뒤 탈퇴하면 그 사용자의 `user_account`·`user_profile`·`user_profile_attribute`·`refresh_token`·`password_reset_token` 행이 0건이다(`JdbcTemplate`로 센다).
     - 탈퇴 뒤 남은 액세스 토큰으로 `GET /api/me`, `GET`·`PUT /api/me/profile`, `POST /api/me/withdrawal`이 401 `UNAUTHENTICATED`다.
     - 탈퇴 뒤 남은 리프레시 쿠키로 `POST /api/auth/refresh`가 401이다.
     - 탈퇴한 이메일로 다시 인증·가입·로그인하면 새 ID이고 `GET /api/me/profile`의 값이 모두 `null`이다. 이전 액세스 토큰은 여전히 401이다.
     - 토큰 없이 탈퇴하면 401이고 계정이 남는다.
     - 비밀번호가 틀리면 400 `PASSWORD_MISMATCH`, 비밀번호가 없거나 빈 문자열이면 400 `INVALID_INPUT`, 73바이트(맞는 72바이트 비밀번호 뒤에 한 글자)면 400 `PASSWORD_MISMATCH`. 어느 경우든 계정·프로필·토큰이 그대로이고 그 뒤 맞는 비밀번호로 탈퇴된다.
     - 본문에 다른 사용자의 ID(`userId`)를 넣어도 토큰 주인만 탈퇴한다.
     - A가 탈퇴해도 B의 계정·프로필·리프레시 토큰·재설정 토큰이 그대로이고, B의 액세스 토큰으로 프로필 조회가 200, B의 리프레시 쿠키로 재발급이 200이다.
   - `account.service.WithdrawalConcurrencyTest`(`@SpringBootTest`, Testcontainers, 기존 `RefreshTokenConcurrencyTest`처럼 `CountDownLatch`): 같은 사용자의 탈퇴 두 개를 동시에 실행하면 하나만 성공하고 다른 하나는 `UNAUTHENTICATED`다.
   - `ApiDocsTest`: `/api/me/withdrawal`의 `post`가 bearer 보안을 요구하고, 요청 스키마가 `password`만 있으며(`userId` 없음), 응답 코드가 204·400·401·500이다.
   - 기존 테스트는 바꾸지 않고 통과한다.
7. **문서**
   - `docs/product/development.md`의 `DEC-AUTH` 행
     - 결정: "탈퇴는 비밀번호를 다시 확인하고(2026-10-19, #51)"를 탈퇴 문장에 넣는다.
     - 이유: 되돌릴 수 없는 삭제라 방치된 로그인이나 새어 나간 액세스 토큰만으로 실행되지 않게 한다.
     - 한계: 로그인과 탈퇴의 비밀번호 확인에 아직 시도 제한이 없다.
   - `docs/decisions/0009-api-error-response.md` 코드 표에 `PASSWORD_MISMATCH` | 400 | 탈퇴할 때 다시 입력한 비밀번호가 틀렸다. #51에서 추가. (#47까지 코드를 추가할 때마다 이 표에 행을 더했다.)

## 완료 조건 매핑

- [ ] 탈퇴하면 그 사용자의 계정·프로필·리프레시 토큰·비밀번호 재설정 토큰이 남지 않는다. → 설계 1. `WithdrawalApiTest`의 테이블별 행 수.
- [ ] 탈퇴 뒤 남은 액세스 토큰으로 프로필 조회·수정을 요청하면 인증되지 않은 요청으로 거절된다. → 설계 6. `WithdrawalApiTest`의 남은 액세스 토큰.
- [ ] 탈퇴 뒤 남은 리프레시 토큰으로 액세스 토큰을 재발급받을 수 없다. → 설계 1. `WithdrawalApiTest`의 남은 리프레시 쿠키.
- [ ] 같은 이메일로 다시 가입하면 이전 프로필이 없는 새 사용자다. → 결정 0014(ID 재사용 없음). `WithdrawalApiTest`의 재가입.
- [ ] 인증되지 않은 탈퇴 요청은 거절된다. → 결정 0018. `WithdrawalApiTest`의 토큰 없음, `ApiDocsTest`.
- [ ] 한 사용자의 탈퇴가 다른 사용자의 계정·프로필·토큰에 영향을 주지 않는다. → `WithdrawalApiTest`의 A·B 사용자, 본문의 `userId`.

## 검증 명령

- `backend/`에서 `./gradlew test`
- 로컬 확인: `SPRING_PROFILES_ACTIVE=local ./gradlew bootRun` 뒤 가입·로그인으로 액세스 토큰을 받고
  ```sh
  curl -si -X POST localhost:8080/api/me/withdrawal -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"password":"wrong-password"}'
  curl -si -X POST localhost:8080/api/me/withdrawal -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"password":"<가입한 비밀번호>"}'
  curl -si localhost:8080/api/me/profile -H "Authorization: Bearer $TOKEN"
  ```
  첫 요청이 400 `PASSWORD_MISMATCH`, 둘째가 204와 `refresh_token` 삭제 쿠키, 셋째가 401인지 본다.

## 결정 필요

없음
