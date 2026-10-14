status: in-progress

# 구현 계획 — #45 리프레시 토큰으로 로그인 유지와 로그아웃 제공

브랜치: `feat/45-refresh-token-logout` (develop `5045043` 기준)

## 목표 및 범위

- 목표: 로그인하면 액세스 토큰과 함께 14일짜리 리프레시 토큰을 쿠키로 받고, 액세스 토큰이 만료되면 재발급 API로 새 토큰을 받는다. 로그아웃하면 리프레시 토큰을 폐기한다. 탈취된 리프레시 토큰은 교체와 재사용 감지로 쓸 수 있는 기간을 줄인다(`DEC-AUTH`).
- 포함:
  - 리프레시 토큰 테이블(`refresh_token`)과 Flyway 마이그레이션.
  - 로그인 응답에 리프레시 토큰 쿠키 추가. 재발급 `POST /api/auth/refresh`, 로그아웃 `POST /api/auth/logout`.
  - 교체, 재사용 감지와 같은 로그인 토큰 전체 폐기, 동시 재발급 처리, 교체 직후 유예.
  - 재발급 경로의 CSRF 방어와 오류 코드 `CSRF_REJECTED`.
  - 재발급·로그아웃 경로에서 `Authorization` 헤더를 무시하는 처리.
  - 결정 문서 0017(리프레시 토큰)·0018(API 인증 경계, 0015 대체). 0009·0012 갱신. `DEC-AUTH`와 `REQ-ACCOUNT-01`에 연장 만료·유예 반영.
  - API 명세: 새 API 설명, 로그인·재발급 응답의 `Set-Cookie`, `ApiDocsTest` 검증.
- 제외:
  - 로그아웃의 CSRF 방어(2026-10-14 사용자 결정). 강제 로그아웃은 피해가 작고, 쿠키가 `SameSite=Strict`라 사이트 밖에서는 붙지 않는다.
  - 교체·만료된 행의 정리 작업. 행이 쌓이지만(설계 1) MVP 규모에서는 문제가 아니다. 후속 이슈로 제안한다.
  - CORS 설정: 프론트(`DEC-APP`)를 붙이는 이슈. 이번에 두는 허용 출처 목록(설계 5)을 그 이슈에서 함께 쓴다.
  - 로그인 실패 속도 제한, 탈퇴, 비밀번호 재설정 시 계정의 리프레시 토큰 전체 폐기, 기기별 로그인 목록: 별도 이슈. 스키마는 `user_id`로 계정 전체를 폐기할 수 있게 둔다.
  - 프론트의 재발급 직렬화(탭 사이 `navigator.locks` 등): 프론트 이슈. 0017 영향에 적는다.

## 확인된 사실과 제약

- `AccountService.login`은 `@Transactional(readOnly = true)`다. 리프레시 토큰을 저장하려면 쓰기 트랜잭션으로 바꿔야 한다.
- `GlobalExceptionHandler.handleBusiness`는 `UNAUTHENTICATED`에 `WWW-Authenticate: Bearer error="invalid_token"`을 붙인다.
- `SecurityConfig`는 `/api/auth/**`를 공개 경로로 둔다. 그러나 0015에 따라 공개 경로에서도 `Authorization` 헤더의 토큰을 검증하므로, 만료된 액세스 토큰을 붙여 재발급을 부르면 401이 된다. 재발급은 바로 이 상황에서 부르는 API다.
- `Clock` 빈은 `CollectionConfig`에 있다(`Asia/Seoul`). `JwtDecoder`의 만료 검증도 이 빈을 쓴다.
- 기존 테스트 `setUp`은 `DELETE FROM user_account`로 정리한다. `refresh_token`이 `user_account`를 FK로 참조하면 `ON DELETE CASCADE`가 없을 때 이 정리가 실패한다.
- `ApiDocsTest`는 `ErrorCode`의 모든 값이 `ApiErrorResponse.code`의 `allowableValues`에 있는지 확인한다. 새 코드는 스키마에도 넣어야 한다.
- MySQL InnoDB 기본 격리 수준은 `REPEATABLE READ`다. `UPDATE`는 최신 커밋된 행을 읽고 잠그지만(current read), 같은 트랜잭션의 일반 `SELECT`는 첫 읽기 시점 스냅샷을 본다.
- 결정 문서 README에 따라 채택된 결정 문서의 본문은 고치지 않는다. 이슈의 "0014·0015 갱신"은 0015를 새 문서로 대체하는 것으로 처리한다. 0014는 액세스 토큰 형식이 바뀌지 않아 고치지 않는다(0014 영향에 리프레시 토큰이 `AccessTokenIssuer`를 쓴다고 이미 적혀 있다).

## 설계 결정

1. **토큰마다 한 행을 두고, 같은 로그인은 `family_id`로 묶는다.** 결정 문서 0017.
   - 로그인하면 새 `family_id`(UUID)로 첫 행을 만든다. 재발급하면 이전 행에 `rotated_at`을 남기고 같은 `family_id`로 새 행을 만든다.
   - 대안: 로그인당 한 행에 현재 해시만 두고 토큰에 세션 ID를 넣는 방식. 행이 쌓이지 않지만, 해시가 다를 때 재사용과 위조를 구분하지 못하고 세션 ID를 아는 사람이 그 세션을 폐기할 수 있다. 토큰 형식도 두 부분으로 나뉜다.
   - 대안: 부모 행을 가리키는 체인. 가족 전체를 폐기하려면 재귀 조회가 필요하다.
2. **만료는 연장 방식이다(2026-10-14 사용자 결정).** 새 행의 `expires_at`은 발급 시각 + 14일이다. 계속 쓰는 동안은 로그인이 유지된다.
   - 한계: 가족 전체의 상한이 없으므로, 탈취된 토큰을 공격자가 계속 교체하면 재사용 감지 말고는 끝나지 않는다. 0017 근거에 적는다.
   - 유효 판정은 `now < expires_at`이다. 발급 후 정확히 14일이 된 순간부터 거절한다.
3. **토큰 값과 저장.** `SecureRandom` 32바이트를 패딩 없는 base64url(43자)로 쓴다. DB에는 SHA-256 해시만 저장한다. 표기는 기존 `content_hash`와 같은 소문자 hex `CHAR(64)`다(구현 중 기존 관례에 맞춰 `BINARY(32)`에서 바꿈).
   - 엔트로피가 256비트라 느린 해시가 필요 없다. BCrypt는 솔트 때문에 해시로 조회할 수 없다. 서버 키를 쓰는 HMAC은 얻는 것이 적고 키 교체 부담이 생긴다.
   - 43자 base64url이 아닌 값은 DB를 조회하지 않고 거절한다.
4. **쿠키**: 이름 `refresh_token`, `HttpOnly; Secure; SameSite=Strict; Path=/api/auth; Max-Age=<14일>`. `Domain`은 지정하지 않아 API 호스트에만 저장된다.
   - `Path=/api/auth`로 개인 API 요청에는 쿠키를 싣지 않는다. 이 때문에 `Path=/`를 요구하는 `__Host-` 접두사는 쓰지 않는다.
   - 브라우저는 `http://localhost`를 보안 컨텍스트로 보므로 local에서도 `Secure`를 끄지 않는다.
   - 로그아웃은 같은 이름·경로로 `Max-Age=0`을 보내 쿠키를 지운다.
5. **재발급의 CSRF 방어는 커스텀 헤더와 `Origin` 검사로 한다.** 결정 문서 0017.
   - `X-Requested-With` 헤더가 비어 있으면 거절한다. 사이트를 넘는 폼 전송은 헤더를 붙일 수 없고, 다른 출처의 `fetch`가 헤더를 붙이면 CORS preflight가 걸린다. 지금은 CORS를 허용하지 않으므로 preflight가 통과하지 못한다.
   - `Origin` 헤더가 있으면 `auth.csrf.allowed-origins`에 있어야 한다. 없으면(브라우저가 아닌 클라이언트) 헤더 검사만 한다. 같은 사이트의 다른 서브도메인은 `SameSite`로 막히지 않으므로 이 검사가 막는다. 값은 `application.yaml`에 `https://govbenefit.kr`(`DEC-APP`), `application-local.yaml`에 `http://localhost:3000`이다.
   - 거절은 403 `CSRF_REJECTED`다. 클라이언트 구현 오류라 원인을 드러내도 정보가 되지 않는다.
   - 검사는 토큰을 처리하기 전에 한다. 막지 못한 요청이 교체나 재사용 감지를 일으키지 않게 하기 위해서다. `HandlerInterceptor`를 `/api/auth/refresh`에 등록해 컨트롤러보다 먼저 거절한다. 인터셉터에서 던진 예외도 `GlobalExceptionHandler`가 0009 형식으로 바꾼다.
   - 대안: Spring Security `CsrfFilter` + `CookieCsrfTokenRepository`(double-submit). 표준 구현이지만 쿠키를 API 호스트에서 발급하므로 프론트 스크립트가 읽으려면 `Domain=govbenefit.kr`로 넓히거나 토큰을 따로 내려줘야 하고, 처음 토큰을 받는 흐름이 추가된다. 경로가 하나뿐이고 서버가 상태를 두지 않아 얻는 것에 비해 비싸다.
   - 대안: `SameSite`만 사용. 같은 사이트의 서브도메인을 막지 못하고 브라우저 동작이라 테스트로 거절을 검증할 수 없다.
6. **재발급 판정 순서.** `RefreshTokenService.rotate`가 한 트랜잭션에서 처리한다.
   1. 형식이 틀리거나 해시로 찾은 행이 없으면 거절.
   2. `revoked_at`이 있으면 거절(가족이 이미 폐기됨).
   3. `rotated_at`이 있으면 재사용이다. 교체된 지 유예 시간(설계 8) 안이면 거절만 하고, 지났으면 같은 `family_id`의 폐기되지 않은 행을 모두 폐기하고 거절.
   4. `now >= expires_at`이면 거절.
   5. 조건부 UPDATE로 교체하고 새 행을 만든다(설계 7).
   - 재사용 검사를 만료 검사보다 먼저 한다. 만료된 이전 토큰이 다시 나타난 것도 탈취 신호다.
   - 거절해도 폐기는 커밋되어야 한다. 서비스는 예외를 던지지 않고 `Optional`로 결과를 돌려주며, 컨트롤러가 비어 있으면 `BusinessException(UNAUTHENTICATED)`을 던진다. 트랜잭션 안에서 던지면 폐기가 롤백된다.
7. **동시 재발급은 조건부 UPDATE로 하나만 통과시킨다.** `UPDATE refresh_token SET rotated_at = ? WHERE id = ? AND rotated_at IS NULL AND revoked_at IS NULL`이 1행이면 교체에 성공한다.
   - 0행이면 1단계에서 읽은 뒤 다른 요청이 방금 교체했거나 폐기한 것이다. 같은 트랜잭션에서 다시 읽으면 `REPEATABLE READ` 스냅샷이라 바뀐 값이 보이지 않으므로 다시 읽지 않는다. 방금 교체된 것이므로 유예 안으로 보고 폐기하지 않고 거절한다.
   - 대안: `SELECT … FOR UPDATE`. 두 번째 요청이 락을 기다린 뒤 최신 상태를 본다. 락 범위를 잘못 잡으면 데드락이 날 수 있고, 조건부 UPDATE로 충분하다.
   - 대안: `@Version` 낙관적 락. 충돌이 예외로 오므로 그 예외를 거절로 바꾸는 처리가 필요하다.
8. **교체 직후 유예(2026-10-14 사용자 결정).** 교체된 지 `auth.refresh-token.reuse-grace`(10초) 안에 이전 토큰이 다시 오면 거절만 하고 가족을 폐기하지 않는다. 결정 문서 0017.
   - 이유: 액세스 토큰을 메모리에만 두므로(`DEC-AUTH`) 탭을 열거나 새로고침할 때마다 재발급을 부른다. 탭 두 개가 동시에 부르거나 응답을 잃어 다시 보내면, 엄격 방식에서는 정상 사용자의 가족이 폐기된다.
   - 유예 중 거절도 401 `UNAUTHENTICATED`로 같다. 이긴 요청의 `Set-Cookie`로 쿠키 저장소가 새 토큰으로 바뀌므로, 클라이언트는 재발급이 401이면 잠시 뒤 한 번 다시 시도하고 그래도 401이면 로그인 화면으로 보낸다. 0017 영향에 적는다.
   - 한계: 공격자가 먼저 교체하고 정상 사용자가 10초 안에 이전 토큰을 쓰면 감지하지 못하고, 연장 만료(설계 2)라 공격자의 가족이 계속 이어진다. 정상 사용자의 재발급은 보통 15분 간격이라 10초 안에 겹칠 가능성은 작다.
   - 10초는 탭 동시 열기(수 ms)와 클라이언트 재시도(수 초)를 덮는 값이다. 값을 키울수록 위 한계의 틈이 넓어진다.
   - 유예 중에 새 토큰을 다시 돌려주는 방식은 새 토큰 원문을 보관해야 해서 쓰지 않는다(설계 3).
9. **로그아웃은 쿠키만 보고, 결과와 관계없이 204다.** 쿠키의 토큰으로 행을 찾으면 그 `family_id`의 폐기되지 않은 행을 모두 폐기한다. 쿠키가 없거나, 형식이 틀리거나, 없는 토큰이어도 204를 주고 쿠키를 지운다.
   - 액세스 토큰을 요구하지 않는다. 만료된 뒤에도 로그아웃할 수 있어야 한다.
   - 교체된 이전 토큰으로 로그아웃해도 가족을 폐기한다. 이전 토큰을 가진 사람은 재발급으로도 가족을 폐기할 수 있으므로 새로 생기는 위험이 없다.
10. **재발급·로그아웃 경로는 `Authorization` 헤더를 무시한다.** 결정 문서 0018(0015 대체).
    - `DefaultBearerTokenResolver`를 감싸 `POST /api/auth/refresh`, `POST /api/auth/logout`에서는 `null`을 돌려준다. 만료된 액세스 토큰을 붙여 재발급을 불러도 쿠키로만 판단한다.
    - 그 밖의 공개 경로는 0015대로 보낸 토큰을 검증한다.
    - 대안: 클라이언트가 재발급에 헤더를 붙이지 않게 한다. 공통 요청 함수가 헤더를 붙이는 흔한 구현에서 놓치기 쉽다.
11. **응답과 오류.**
    - 로그인: 200, 본문은 지금과 같고 `Set-Cookie`만 추가한다.
    - 재발급: 200, 본문은 로그인과 같은 `LoginResponse`(`accessToken`, `tokenType`, `expiresAt`)와 새 `Set-Cookie`. 같은 모양이라 DTO를 나누지 않는다.
    - 재발급 실패: 401 `UNAUTHENTICATED`(0009). 쿠키 없음·형식 오류·없는 토큰·만료·교체·폐기를 나누지 않는다. `WWW-Authenticate` 헤더도 지금 핸들러대로 붙인다. 401은 이 헤더가 있어야 하고(RFC 9110) 쿠키용 표준 방식이 없다.
    - CSRF 거절: 403 `CSRF_REJECTED`. 0009 표에 추가한다.
    - 로그아웃: 204, 본문 없음.

## 단계별 구현 계획

1. **마이그레이션** `V5__create_refresh_token.sql`
   ```sql
   -- 리프레시 토큰(#45, 결정 0017). 원문은 저장하지 않고 SHA-256 해시(소문자 hex)만 둔다.
   -- family_id는 한 번의 로그인에서 교체로 이어진 토큰을 묶는다. 탈퇴하면 계정과 함께 지운다(DEC-AUTH).
   CREATE TABLE refresh_token (
       id         BIGINT      NOT NULL AUTO_INCREMENT,
       user_id    BIGINT      NOT NULL,
       family_id  CHAR(36)    NOT NULL,
       token_hash CHAR(64)    NOT NULL,
       issued_at  DATETIME(6) NOT NULL,
       expires_at DATETIME(6) NOT NULL,
       rotated_at DATETIME(6) NULL,
       revoked_at DATETIME(6) NULL,
       PRIMARY KEY (id),
       CONSTRAINT uk_refresh_token_hash UNIQUE (token_hash),
       CONSTRAINT fk_refresh_token_user FOREIGN KEY (user_id) REFERENCES user_account (id) ON DELETE CASCADE,
       INDEX idx_refresh_token_family (family_id)
   );
   ```
2. **설정**
   - `application.yaml`: `auth.refresh-token.ttl: 14d`, `auth.refresh-token.reuse-grace: 10s`, `auth.csrf.allowed-origins: https://govbenefit.kr`.
   - `application-local.yaml`: `auth.csrf.allowed-origins: http://localhost:3000`.
3. **공통 API** `kr.govbenefit.common.api`
   - `ErrorCode.CSRF_REJECTED(HttpStatus.FORBIDDEN, "허용되지 않은 출처의 요청입니다.")`.
   - `ApiErrorResponse.code`의 `@Schema` 설명과 `allowableValues`에 추가.
4. **보안** `kr.govbenefit.common.security`
   - `CsrfProperties`(`@ConfigurationProperties("auth.csrf")` record): `List<String> allowedOrigins`.
   - `CsrfOriginInterceptor`(`HandlerInterceptor`): 설계 5. `preHandle`에서 `BusinessException(CSRF_REJECTED)`.
   - `CsrfConfig`(`WebMvcConfigurer`): 인터셉터를 `/api/auth/refresh`에 등록, `CsrfProperties` 활성화.
   - `SecurityConfig`: `oauth2ResourceServer`에 `bearerTokenResolver(...)`(설계 10). 클래스·메서드 주석의 CSRF 설명을 0017·0018에 맞게 고친다. `csrf` 필터는 계속 끈다(방어는 인터셉터가 한다).
5. **계정** `kr.govbenefit.account`
   - `domain.RefreshToken`(엔티티, `refresh_token`): `id`, `userId`, `familyId`, `tokenHash`(`String`, `char(64)`), `issuedAt`, `expiresAt`, `rotatedAt`, `revokedAt`. `static RefreshToken issue(Long userId, String familyId, String tokenHash, LocalDateTime now, Duration ttl)`, `isRotated()`, `isRevoked()`, `isExpiredAt(LocalDateTime)`.
   - `domain.RefreshTokenValues`: `String generate()`(설계 3), `boolean isWellFormed(String)`, `String hash(String)`(소문자 hex).
   - `repository.RefreshTokenRepository`
     - `Optional<RefreshToken> findByTokenHash(String)`
     - `@Modifying int markRotated(Long id, LocalDateTime now)`: 설계 7의 조건부 UPDATE.
     - `@Modifying int revokeFamily(String familyId, LocalDateTime now)`: `revoked_at IS NULL`인 행만.
   - `config.RefreshTokenProperties`(`@ConfigurationProperties("auth.refresh-token")` record): `Duration ttl`, `Duration reuseGrace`. `config.AccountConfig`가 활성화한다.
   - `service.RefreshTokenService`
     - `String issueNewFamily(Long userId)`: 새 `family_id`로 첫 행. 원문을 돌려준다. 쿠키의 `Max-Age`는 `RefreshTokenProperties.ttl`이다.
     - `Optional<Rotation> rotate(String rawToken)`: 설계 6·7·8. 성공하면 사용자 ID와 새 원문(`Rotation`).
     - `void revokeFamilyOf(String rawToken)`: 설계 9.
     - 시각은 `Clock` 빈으로 정한다.
   - `service.AccountService`
     - `login`: 쓰기 트랜잭션으로 바꾸고, 성공하면 `AccessTokenIssuer.issue`와 `RefreshTokenService.issueNewFamily`의 결과를 함께 돌려준다(`service.AuthTokens(AccessToken, String)`).
     - `Optional<AuthTokens> refresh(String rawToken)`: `rotate` 결과로 새 액세스 토큰을 발급한다.
     - `logout(String rawToken)`.
   - `controller.RefreshTokenCookies`: 설계 4의 `ResponseCookie` 생성과 삭제 쿠키.
   - `controller.AuthController`
     - `login`: `Set-Cookie`를 붙인다. 본문은 그대로다.
     - `POST /refresh`: `@CookieValue(name = "refresh_token", required = false)`. 실패하면 `BusinessException(UNAUTHENTICATED)`.
     - `POST /logout`: 204와 삭제 쿠키.
     - `@Operation`·`@ApiResponse`: 로그인·재발급의 `Set-Cookie` 헤더(`@Header`), 재발급 401·403, 로그아웃 204. 재발급 설명에 `X-Requested-With` 헤더 필요, 유예 중 401이면 한 번 다시 시도하라는 안내.
6. **API 명세** `OpenApiConfig`의 `DESCRIPTION`에 로그인 유지 방법(쿠키, 재발급, 로그아웃)을 더한다.
7. **테스트**
   - `RefreshTokenValuesTest`(단위): 생성 값이 43자 base64url, 두 번 생성한 값이 다름, 형식 판정(42자·44자·`+`/`/` 포함 거절), 해시가 원문의 SHA-256 소문자 hex 64자이고 같은 입력에 같다.
   - `RefreshTokenApiTest`(`@SpringBootTest`, MockMvc, Testcontainers). 고정·이동 가능한 테스트용 시계(`kr.govbenefit.MutableClock`)를 `@Primary` 빈으로 넣는다.
     - 로그인 응답의 `Set-Cookie`가 `HttpOnly`·`Secure`·`SameSite=Strict`·`Path=/api/auth`·`Max-Age=1209600`이고 본문 키는 `accessToken`·`tokenType`·`expiresAt`뿐.
     - 재발급하면 새 액세스 토큰과 새 쿠키(이전과 다른 값), 새 액세스 토큰으로 `/api/me` 200.
     - 시계를 14일 - 1초 이동하면 재발급 성공, 14일 이동하면 401. 재발급한 토큰은 그 시점부터 다시 14일(연장).
     - 교체된 이전 토큰을 유예 시간 + 1초 뒤에 쓰면 401이고, 최신 토큰도 401.
     - 교체된 이전 토큰을 유예 시간 안에 쓰면 401이지만 최신 토큰은 재발급에 성공.
     - 같은 사용자가 두 번 로그인한 뒤 한쪽에서 재사용이 감지되어도 다른 쪽 토큰은 재발급에 성공.
     - `X-Requested-With`가 없으면 403 `CSRF_REJECTED`, 허용 목록에 없는 `Origin`이면 403, 허용된 `Origin`이면 200. 403으로 거절된 뒤에도 그 토큰이 교체되지 않았다(다시 재발급 성공).
     - 쿠키 없음·형식 오류·DB에 없는 토큰이 401 `UNAUTHENTICATED`이고 본문이 0009 형식.
     - 만료된 액세스 토큰을 `Authorization`에 붙여도 쿠키가 유효하면 재발급 200(설계 10).
     - 로그아웃하면 204와 `Max-Age=0` 쿠키, 그 토큰으로 재발급 401, 로그아웃 전 액세스 토큰으로 `/api/me`는 200. 쿠키 없이 로그아웃해도 204.
     - DB의 어떤 행에도 쿠키 원문이 없고 `token_hash`가 원문의 SHA-256과 같다.
   - `RefreshTokenConcurrencyTest`(`@SpringBootTest`, Testcontainers): 같은 원문으로 `AccountService.refresh`를 스레드 8개에서 `CountDownLatch`로 동시에 부른다. 성공 1, 나머지는 빈 결과, 같은 가족의 `rotated_at`이 있는 행 1개와 새 행 1개, 성공한 새 토큰이 폐기되지 않았는지(유예).
   - `LoginApiTest`: 기존 검증을 바꾸지 않고 통과한다. `setUp`의 `DELETE FROM user_account`는 FK `ON DELETE CASCADE`로 토큰도 지운다.
   - `ApiDocsTest`: `/api/auth/refresh`(200·401·403·500), `/api/auth/logout`(204·500)의 응답 코드, 로그인·재발급 200의 `Set-Cookie` 헤더, `CSRF_REJECTED`가 `allowableValues`에 있는지.
   - 기존 `SignUpApiTest`·`AccessTokenAuthTest`·`BenefitSearchApiTest`·`BenefitDetailApiTest`·`ApiDocsClosedTest`가 바꾸지 않고 통과한다.
8. **문서**
   - `docs/decisions/0017-refresh-token.md`: 설계 1~9. 저장 모델, 연장 만료와 한계, 값·해시, 쿠키 속성, CSRF 방어 방식과 제외한 대안, 판정 순서, 동시성, 유예와 한계, 로그아웃. 영향에 프론트의 재발급 직렬화·401 한 번 재시도, CORS 이슈가 `auth.csrf.allowed-origins`를 함께 쓸 것, 비밀번호 재설정·탈퇴가 `user_id`로 폐기할 것, 행 정리 작업.
   - `docs/decisions/0018-api-auth-boundary.md`: 0015를 대체. 0015 내용에 재발급·로그아웃의 `Authorization` 무시(설계 10)와 CSRF 방어 위치(인터셉터, 0017)를 반영한다. 0015는 상태 줄만 `대체됨 → 0018`. 코드 주석의 `결정 0015` 참조는 `결정 0018`로 바꾼다.
   - `docs/decisions/README.md` 목록 표에 0017·0018, 0015 상태.
   - `0009`: 코드 표에 `CSRF_REJECTED`, 적용 위치에 `CsrfOriginInterceptor`.
   - `0012`: 적용 위치에 쿠키 헤더 문서화를 더한다.
   - `docs/product/development.md`의 `DEC-AUTH`(로그인·탈퇴) 행: 리프레시 토큰은 쓸 때마다 14일로 연장, 교체 직후 10초 안의 재사용은 거절만 하고 폐기하지 않음(2026-10-14, #45).
   - `docs/product/requirements.md`의 `REQ-ACCOUNT-01`: 만료 문장에 연장을, 재사용 문장에 유예 예외를 더한다.

## 완료 조건 매핑

이슈의 완료 조건 4번째 항목은 유예 결정(설계 8)과 맞지 않아 이슈 수정이 필요하다. 아래 4번째 항목은 수정안이며, 이슈를 고친 뒤 그 문구로 바꾼다.

- [ ] 로그인 응답에 리프레시 토큰이 HttpOnly·Secure·SameSite 쿠키로 담기고, 응답 본문에는 리프레시 토큰이 없다. → 설계 4·11. `RefreshTokenApiTest`의 쿠키 속성과 본문 키.
- [ ] 유효한 리프레시 토큰으로 재발급하면 새 액세스 토큰과 새 리프레시 토큰 쿠키를 받고, 새 액세스 토큰으로 `GET /api/me`가 성공한다. → 설계 6·11. `RefreshTokenApiTest`.
- [ ] 발급 후 14일이 지난 리프레시 토큰으로 재발급하면 거절된다(시계를 고정해 경계값 검증). → 설계 2. `RefreshTokenApiTest`의 14일 - 1초 / 14일.
- [ ] (수정안) 교체된 이전 리프레시 토큰을 유예 시간이 지난 뒤 다시 쓰면 거절되고, 같은 로그인에서 이어진 최신 리프레시 토큰도 더는 재발급에 쓸 수 없다. 유예 시간 안에 다시 쓰면 거절되지만 최신 리프레시 토큰은 계속 쓸 수 있다. → 설계 6·8. `RefreshTokenApiTest`의 유예 전후.
- [ ] 재사용이 감지되어도 같은 사용자의 다른 로그인에서 받은 리프레시 토큰은 계속 쓸 수 있다. → 설계 1. `RefreshTokenApiTest`의 두 번 로그인.
- [ ] 같은 리프레시 토큰으로 동시에 재발급을 요청해도 둘 다 성공하지 않는다(Testcontainers MySQL로 검증). → 설계 7. `RefreshTokenConcurrencyTest`.
- [ ] CSRF 방어를 통과하지 못한 재발급 요청은 거절된다. → 설계 5. `RefreshTokenApiTest`의 헤더·`Origin` 검사와 거절 뒤 토큰 미교체.
- [ ] 로그아웃한 뒤 그 리프레시 토큰으로 재발급하면 거절된다. 이미 발급된 액세스 토큰은 만료 전까지 유효하다(`DEC-AUTH`의 한계). → 설계 9. `RefreshTokenApiTest`의 로그아웃.
- [ ] 리프레시 토큰이 없거나, 형식이 틀리거나, DB에 없는 재발급 요청은 401로 거절되고 응답 형식은 결정 0009를 따른다. → 설계 3·11. `RefreshTokenApiTest`.
- [ ] DB에 리프레시 토큰 원문이 저장되지 않는다. → 설계 3. `RefreshTokenApiTest`의 해시 비교.
- [ ] 기존 가입·로그인·`GET /api/me`의 응답과 오류 코드는 바뀌지 않는다. 로그인 응답에 쿠키가 추가되는 것만 예외다. → 설계 11. 기존 `SignUpApiTest`·`LoginApiTest`·`AccessTokenAuthTest`가 바꾸지 않고 통과한다.

## 검증 명령

- `backend/`에서 `./gradlew test`
- 로컬 확인(기본 테스트에 포함하지 않음): `SPRING_PROFILES_ACTIVE=local ./gradlew bootRun` 뒤
  ```sh
  curl -s -X POST localhost:8080/api/auth/signup -H 'Content-Type: application/json' \
    -d '{"email":"me@example.com","password":"correct-horse"}'
  curl -s -i -c jar -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
    -d '{"email":"me@example.com","password":"correct-horse"}'
  curl -s -i -b jar -c jar -X POST localhost:8080/api/auth/refresh -H 'X-Requested-With: curl'
  curl -s -i -b jar -X POST localhost:8080/api/auth/refresh
  curl -s -i -b jar -c jar -X POST localhost:8080/api/auth/logout
  ```
  `Set-Cookie` 속성, 헤더 없는 재발급의 403, 로그아웃 뒤 재발급의 401을 본다.

## 결정 필요

없음
