# 0017. 리프레시 토큰 저장·교체와 CSRF 방어

- 상태: 채택
- 날짜: 2026-10-14
- 출처: #45, [플랜](../plans/45-refresh-token-logout.md)

## 배경

`DEC-AUTH`는 로그인 유지를 리프레시 토큰(14일)으로 정했다. 이 토큰은 HttpOnly·Secure·SameSite 쿠키로만 주고받고, 쓸 때마다 새로 발급하며, 이미 교체·폐기된 토큰이 다시 쓰이면 같은 로그인에서 이어진 토큰을 모두 폐기하고, DB에는 해시만 저장한다. #45에서 처음 구현하면서 저장 모델, 만료 방식, 동시 재발급 처리, 쿠키 속성, CSRF 방어 방식을 정해야 했다. 비밀번호 재설정·탈퇴(계정의 토큰 전체 폐기)와 프론트의 재발급 호출이 이 결정을 따른다.

## 결정

리프레시 토큰은 토큰마다 한 행(`refresh_token`)에 SHA-256 해시로 저장하고, 같은 로그인은 `family_id`로 묶는다. 재발급할 때마다 새 토큰이 14일을 새로 받고(연장 만료), 교체된 토큰이 10초 유예 뒤에 다시 쓰이면 같은 가족을 모두 폐기한다. 재발급 요청은 `X-Requested-With` 헤더와 `Origin` 허용 목록으로 CSRF를 막는다.

- 값: `SecureRandom` 32바이트의 base64url(패딩 없음, 43자). 형식이 틀린 값은 DB를 조회하지 않고 거절한다.
- 저장: `token_hash`는 원문의 SHA-256(소문자 hex, `CHAR(64)`, unique). `user_id`는 `user_account`를 `ON DELETE CASCADE`로 참조한다.
- 교체: 이전 행에 `rotated_at`을 남기고 같은 `family_id`로 새 행을 만든다. 새 행의 `expires_at`은 발급 시각 + `auth.refresh-token.ttl`(14일)이다. `now < expires_at`일 때만 쓸 수 있다.
- 판정 순서(`RefreshTokenService.rotate`): 형식·존재 → 폐기(`revoked_at`) → 교체(`rotated_at`, 유예 판단) → 만료 → 조건부 UPDATE. 재사용 검사를 만료 검사보다 먼저 한다.
- 유예: 교체된 지 `auth.refresh-token.reuse-grace`(10초) 안이면 거절만 한다. 지났으면 같은 `family_id`의 폐기되지 않은 행을 모두 폐기하고 거절한다.
- 동시성: `UPDATE … SET rotated_at = ? WHERE id = ? AND rotated_at IS NULL AND revoked_at IS NULL`이 1행일 때만 교체한다. 0행이면 다른 요청이 방금 교체한 것으로 보고 폐기 없이 거절한다.
- 거절은 서비스가 빈 결과로 돌려주고 컨트롤러가 401 `UNAUTHENTICATED`로 바꾼다. 거절 사유는 나누지 않는다. 거절 응답에서 쿠키를 지우지 않는다.
- 로그아웃: 쿠키의 토큰이 속한 가족을 모두 폐기하고 `Max-Age=0` 쿠키를 보낸다. 쿠키가 없거나 쓸 수 없는 토큰이어도 204다. 액세스 토큰을 요구하지 않는다.
- 쿠키: `refresh_token`, `HttpOnly; Secure; SameSite=Strict; Path=/api/auth; Max-Age=1209600`. `Domain`은 지정하지 않는다.
- CSRF: `/api/auth/refresh`는 `X-Requested-With` 헤더(값 무관)가 있어야 하고, `Origin` 헤더가 있으면 `auth.csrf.allowed-origins`에 있어야 한다. 아니면 403 `CSRF_REJECTED`다. 검사는 컨트롤러보다 먼저 하는 `HandlerInterceptor`가 한다. 로그아웃은 검사하지 않는다.

## 근거

- **토큰마다 한 행**: 완료 조건의 "교체된 이전 토큰"을 행 상태로 바로 판정한다. 로그인당 한 행에 현재 해시만 두고 토큰에 세션 ID를 넣는 방식은 행이 쌓이지 않지만, 해시가 다를 때 재사용과 위조를 구분하지 못하고 세션 ID를 아는 사람이 그 세션을 폐기할 수 있다. 부모 행을 가리키는 체인은 가족을 폐기할 때 재귀 조회가 필요하다. 한 행씩 쌓이는 비용(활발한 로그인 하나에 14일 동안 최대 약 1,300행)은 MVP 규모에서 문제가 아니다.
- **연장 만료(2026-10-14 사용자 결정)**: 계속 쓰는 사용자는 다시 로그인하지 않는다. 한계: 가족 전체의 상한이 없어, 공격자가 탈취한 토큰을 계속 교체하면 재사용 감지 말고는 끝나지 않는다.
- **SHA-256**: 원문의 엔트로피가 256비트라 무차별 대입이 불가능하므로 느린 해시가 필요 없다. BCrypt는 솔트 때문에 해시로 조회할 수 없다. 서버 키를 쓰는 HMAC은 얻는 것이 적고 키 교체 부담이 생긴다. hex `CHAR(64)`는 기존 `content_hash`와 같은 표기다.
- **유예(2026-10-14 사용자 결정)**: 액세스 토큰을 브라우저 메모리에만 두므로(`DEC-AUTH`) 탭을 열거나 새로고침할 때마다 재발급을 부른다. 탭 두 개가 동시에 부르거나 응답을 잃어 다시 보내면 엄격한 방식에서는 정상 사용자의 가족이 폐기된다. 10초는 탭 동시 열기(수 ms)와 클라이언트 재시도(수 초)를 덮는 값이다. 한계: 공격자가 먼저 교체하고 정상 사용자가 10초 안에 이전 토큰을 쓰면 감지하지 못하고, 연장 만료라 공격자의 가족이 이어진다. 정상 사용자의 재발급은 보통 15분 간격이라 10초 안에 겹칠 가능성은 작다. 값을 키울수록 이 틈이 넓어진다. 유예 중에 같은 새 토큰을 다시 돌려주는 방식은 새 토큰 원문을 보관해야 해서 해시만 저장하는 규칙과 맞지 않는다.
- **조건부 UPDATE**: InnoDB의 `UPDATE`는 최신 커밋된 행을 읽고 잠그므로 동시 요청 중 하나만 1행을 받는다. 같은 트랜잭션의 일반 `SELECT`는 `REPEATABLE READ` 스냅샷을 보므로, 진 요청이 다시 읽어도 바뀐 값이 보이지 않는다. 그래서 다시 읽지 않고 방금 교체된 것으로 본다. 조건에서 `rotated_at IS NULL`을 빼면 `RefreshTokenConcurrencyTest`가 실패하는 것을 확인했다(2026-10-14). `SELECT … FOR UPDATE`는 락 대기가 생기고, `@Version`은 충돌 예외를 거절로 바꾸는 처리가 더 든다.
- **거절을 빈 결과로**: 트랜잭션 안에서 예외를 던지면 재사용 감지로 한 폐기가 롤백된다.
- **거절 응답에서 쿠키를 지우지 않음**: 동시 재발급에서 진 응답이 이긴 응답보다 늦게 도착하면 새 쿠키를 지운다.
- **로그아웃에 액세스 토큰을 요구하지 않음**: 만료된 뒤에도 로그아웃할 수 있어야 한다. 교체된 이전 토큰으로도 가족을 폐기하지만, 이전 토큰을 가진 사람은 재발급으로도 폐기할 수 있어 새로 생기는 위험이 없다.
- **쿠키 경로**: `Path=/api/auth`로 개인 API 요청에 쿠키를 싣지 않는다. `__Host-` 접두사는 `Path=/`를 요구해 쓰지 않는다. 브라우저는 `http://localhost`를 보안 컨텍스트로 보므로 local에서도 `Secure`를 끄지 않는다.
- **CSRF 방어**: 프론트(`govbenefit.kr`)와 API(`api.govbenefit.kr`)는 같은 사이트라 `SameSite`가 같은 사이트의 다른 서브도메인에서 온 요청을 막지 못한다. 또 `SameSite`는 브라우저 동작이라 테스트로 거절을 검증할 수 없다. 사이트를 넘는 폼 전송은 커스텀 헤더를 붙일 수 없고, 다른 출처의 `fetch`가 헤더를 붙이면 CORS preflight가 걸린다. `Origin` 허용 목록은 CORS가 나중에 넓어져도 다른 출처를 막는다. Spring Security의 `CookieCsrfTokenRepository`(double-submit)는 표준 구현이지만, 쿠키를 API 호스트에서 발급하므로 프론트 스크립트가 읽으려면 `Domain=govbenefit.kr`로 넓히거나 토큰을 따로 내려줘야 하고, 처음 토큰을 받는 흐름이 추가된다. 경로가 하나뿐이라 얻는 것에 비해 비싸다. 검사를 토큰 처리보다 먼저 해, 막지 못한 요청이 교체나 재사용 감지를 일으키지 않게 한다.
- **로그아웃 CSRF 제외(2026-10-14 사용자 결정)**: 강제 로그아웃은 피해가 작고, 쿠키가 `SameSite=Strict`라 사이트 밖에서는 붙지 않는다.
- **`CSRF_REJECTED`를 따로 둠**: 클라이언트 구현 오류라 원인을 드러내도 공격자에게 정보가 되지 않고, 개발 중 원인을 바로 안다.

## 적용 위치

- `backend/src/main/resources/db/migration/V5__create_refresh_token.sql`
- `backend/src/main/resources/application.yaml`: `auth.refresh-token`, `auth.csrf.allowed-origins`(`https://govbenefit.kr`)
- `backend/src/main/resources/application-local.yaml`: `auth.csrf.allowed-origins`(`http://localhost:3000`)
- `backend/src/main/java/kr/govbenefit/account/domain/RefreshToken.java`, `OpaqueTokens.java`(#47에서 `RefreshTokenValues.java`의 이름을 바꿈)
- `backend/src/main/java/kr/govbenefit/account/repository/RefreshTokenRepository.java`: 조건부 UPDATE와 가족 폐기
- `backend/src/main/java/kr/govbenefit/account/service/RefreshTokenService.java`, `AccountService.java`
- `backend/src/main/java/kr/govbenefit/account/controller/AuthController.java`, `RefreshTokenCookies.java`
- `backend/src/main/java/kr/govbenefit/account/config/RefreshTokenProperties.java`
- `backend/src/main/java/kr/govbenefit/common/security/CsrfOriginInterceptor.java`, `CsrfConfig.java`, `CsrfProperties.java`
- `backend/src/test/java/kr/govbenefit/account/controller/RefreshTokenApiTest.java`, `account/service/RefreshTokenConcurrencyTest.java`

## 영향

- 프론트는 재발급에 `X-Requested-With` 헤더를 붙이고, 탭 사이에서 재발급을 하나로 묶는다(`navigator.locks` 등). 재발급이 401이면 잠시 뒤 한 번 다시 시도하고, 그래도 401이면 로그인 화면으로 보낸다.
- CORS를 붙이는 이슈는 `auth.csrf.allowed-origins`와 같은 출처를 허용한다. CORS를 넓히면 커스텀 헤더 방어가 약해지므로 `Origin` 검사를 빼지 않는다.
- 쿠키로 인증하는 경로를 더하면 `CsrfConfig`에 등록할지 정한다.
- 비밀번호 재설정은 그 계정의 모든 리프레시 토큰을 `user_id`로 폐기한다(`REQ-ACCOUNT-01`). 탈퇴는 `user_account`를 지우면 `ON DELETE CASCADE`로 함께 지워진다.
- 교체·만료된 행은 지우지 않으므로 쌓인다. 행 수가 문제가 되면 만료된 지 일정 기간이 지난 가족을 지우는 작업을 둔다.
- 가족 전체의 상한이 필요해지면(연장 만료의 한계) 로그인 시각을 가족에 두고 이 결정을 대체한다.
