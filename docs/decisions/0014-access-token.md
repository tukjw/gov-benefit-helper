# 0014. 액세스 토큰 형식과 검증

- 상태: 채택
- 날짜: 2026-10-10
- 출처: #38, [플랜](../plans/38-signup-login-access-token.md)

## 배경

`DEC-AUTH`는 로그인 유지 수단을 액세스 토큰(JWT, 15분)으로 정했다. #38에서 토큰을 처음 발급·검증하면서 서명 방식, 키 관리, 클레임, 만료 판정을 정해야 했다. 리프레시 토큰(A2), 탈퇴, 이후 개인 API 모두 이 형식을 쓴다.

## 결정

액세스 토큰은 HS256으로 서명한 JWT이며, Spring Security OAuth2 Resource Server(Nimbus)로 발급·검증한다.

- 헤더: `alg: HS256`, `typ: JWT`.
- 클레임: `iss`(`auth.jwt.issuer`), `sub`(`user_account.id` 문자열), `iat`, `exp`(`iat` + 15분), `token_use: "access"`. 시각은 초 단위이고 응답의 `expiresAt`은 `exp`와 같다.
- 검증: 서명(HS256만), `typ`, `exp`(시계 오차 허용 0, `Clock` 빈 기준, `exp` 필수), `iss`, `token_use`. 하나라도 맞지 않으면 401 `UNAUTHENTICATED`다.
- 키: `auth.jwt.secret`(환경변수 `JWT_SECRET`, base64로 인코딩한 32바이트 이상). 없거나 짧으면 기동에 실패한다. `auth.jwt.generate-secret-if-missing: true`이면 없을 때 기동할 때 임의 키를 만들고 WARN을 남긴다. 이 설정은 `application-local.yaml`과 테스트 설정에만 둔다.

## 근거

- 표준 구현을 쓰면 서명·만료·헤더 해석을 직접 짜지 않는다. 직접 만든 필터(jjwt)는 예외 처리와 `SecurityContext` 설정을 다시 구현한다. Security 없이 인터셉터로 처리하면 A2의 CSRF와 이후 CORS를 직접 만들어야 한다.
- 발급과 검증을 같은 서버가 하므로 대칭키로 충분하다. RS256/ES256은 검증하는 서버를 나눌 때(JWKS 배포) 필요하다.
- `JwtTimestampValidator`의 기본 시계 오차 허용은 60초다. 그대로 두면 "15분이 지나면 거절"이 최대 16분이 된다. 발급과 검증이 같은 서버라 시계 차이가 없으므로 0으로 둔다. 기본값으로 되돌리면 `AccessTokenAuthTest`의 만료 테스트가 실패하는 것을 확인했다(2026-10-10).
- `token_use`는 이메일 확인·재설정 등 다른 용도의 JWT를 같은 키로 만들더라도 액세스 토큰으로 쓰이지 않게 한다.
- `sub`에 내부 ID를 쓴다. 개인 API가 사용자 ID를 입력으로 받지 않고([0015](0015-api-auth-boundary.md)) 응답에도 넣지 않으므로 바깥에 보이는 곳은 토큰 주인이 읽는 `sub`뿐이다. 순번으로 알 수 있는 것은 대략의 가입 순서 정도다. 별도 공개 ID(UUID)는 컬럼·인덱스·변환이 늘어나는데 쓸 곳이 아직 없다. 액세스 토큰은 15분이면 만료되므로 나중에 바꿔도 옛 토큰이 금방 사라진다.
- MySQL 8.0부터 auto_increment 카운터가 재시작 뒤에도 유지되어 ID를 다시 쓰지 않는다. 탈퇴 전 토큰의 `sub`가 같은 이메일로 다시 가입한 새 사용자를 가리키지 않는다.
- local은 `.env` 없이 실행되어야 하고(저장소 지침), 키를 저장소에 둘 수 없어 임의 키를 만든다. 운영에서 같은 방식을 쓰면 재시작이나 서버 증설 때 토큰이 무효가 되므로 기본값은 기동 실패다.

## 적용 위치

- `backend/build.gradle`: `spring-boot-starter-security-oauth2-resource-server`
- `backend/src/main/java/kr/govbenefit/common/security/JwtProperties.java`, `JwtConfig.java`: 키와 발급·검증기
- `backend/src/main/java/kr/govbenefit/common/security/AccessTokenIssuer.java`: 클레임
- `backend/src/main/resources/application.yaml`, `application-local.yaml`, `backend/src/test/resources/config/application.yaml`: `auth.jwt`
- `backend/.env.example`: `JWT_SECRET`

## 영향

- 운영 배포(O2)는 `JWT_SECRET`을 비밀 값으로 주입해야 한다. 서버가 여럿이면 같은 키를 쓴다.
- 리프레시 토큰(A2)은 이 액세스 토큰을 새로 발급할 때 `AccessTokenIssuer`를 쓴다. 리프레시 토큰은 JWT가 아니라 DB에 해시를 저장하는 값이다(`DEC-AUTH`).
- 클레임이나 서명 방식을 바꾸면 이 결정을 대체한다. 사용자 식별자를 URL이나 외부 시스템에 노출해야 하면 공개 ID를 도입하고 `sub`를 함께 바꾼다.
