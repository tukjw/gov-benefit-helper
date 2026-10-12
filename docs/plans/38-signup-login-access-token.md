status: done

# 구현 계획 — #38 이메일·비밀번호 가입·로그인과 액세스 토큰 인증 제공

브랜치: `feat/38-signup-login-access-token` (develop `35878c8` 기준)

## 목표 및 범위

- 목표: 이메일·비밀번호로 가입·로그인하고, 로그인하면 15분짜리 액세스 토큰(JWT)을 받는다. 개인 API는 유효한 토큰이 있는 요청만 받는다. 이후 프로필·관심 저장이 쓸 인증 기반(토큰 검증, 요청자 식별, 401 응답)을 만든다.
- 포함:
  - 가입 `POST /api/auth/signup`, 로그인 `POST /api/auth/login`, 내 정보 `GET /api/me`.
  - 사용자 테이블(`user_account`)과 Flyway 마이그레이션.
  - Spring Security 구성: 기본 거부, 공개 경로 허용, 없는 경로 404 유지, 401 응답의 0009 형식.
  - 액세스 토큰 발급·검증, 요청자 ID를 컨트롤러에 넘기는 `@CurrentUserId`.
  - 오류 코드 추가(`UNAUTHENTICATED`, `INVALID_CREDENTIALS`, `DUPLICATE_EMAIL`)와 비밀번호 값 응답 제외.
  - 결정 문서 0013(비밀번호 저장과 규칙), 0014(액세스 토큰), 0015(API 인증 경계). 0009·0012 갱신. `DEC-AUTH`에 비밀번호 규칙 기록.
  - API 명세(0012): 새 API 설명, Bearer 인증 표시, `ApiDocsTest` 검증.
- 제외:
  - 리프레시 토큰·로그아웃·CSRF 방어: 후속 이슈 A2. 이번에는 쿠키를 쓰지 않으므로 CSRF 보호를 끈다(설계 6).
  - 이메일 소유 확인·비밀번호 재설정: 후속 이슈 B. 소유 확인 전 개인 기능 차단도 B에서 한다.
  - 로그인 실패 속도 제한, 탈퇴: 별도 이슈.
  - 비밀번호 해시 계산 중 DB 커넥션 점유 개선: #41.
  - 다른 사용자 데이터 접근 차단 검증: 프로필 이슈. 이번에는 "개인 API는 사용자 ID를 입력으로 받지 않는다"는 규칙만 0015로 정한다.
  - CORS: 프론트(`DEC-APP`)를 붙이는 이슈에서 허용 출처와 함께 정한다.
  - 공개 API에서 토큰이 있으면 개인화하는 선택적 인증. 필요해지면 0015를 대체한다.
  - 권한(역할)과 403 응답 형식. 역할이 없어 403이 나는 경로가 없다.

## 확인된 사실과 제약

- Spring Boot `4.1.1`. 보안 starter는 `spring-boot-starter-security-oauth2-resource-server`(4.1.1 존재 확인, 2026-10-09). 예전 이름 `spring-boot-starter-oauth2-resource-server`는 4.1.1 pom 설명에 deprecated로 표시된다. 테스트용은 `spring-boot-starter-security-oauth2-resource-server-test`. resource server는 `spring-security-oauth2-jose`(Nimbus)를 포함하므로 JWT 라이브러리를 따로 넣지 않는다.
- Jackson은 3(`tools.jackson`)이다. 테스트는 `JsonMapper`를 주입받는다.
- 정렬 규칙은 `utf8mb4_0900_ai_ci`다(결정 0001). 대소문자·악센트를 무시하므로 email unique 제약도 대소문자를 무시한다.
- MySQL 8.0부터 auto_increment 카운터가 재시작 뒤에도 유지되어 탈퇴한 사용자의 ID를 다시 쓰지 않는다.
- `GlobalExceptionHandler`는 MVC 안의 예외만 0009 형식으로 바꾼다. 보안 필터에서 거절한 요청은 거치지 않으므로 401 본문을 따로 써야 한다.
- `handleMethodArgumentNotValid`는 거절한 값(`getRejectedValue`)을 `errors[].value`에 그대로 넣는다. 비밀번호를 그대로 쓰면 응답에 원문이 나간다.
- `ErrorCode.from(401)`은 지금 `REQUEST_FAILED`다.
- `spring.web.resources.add-mappings: false`라 없는 경로는 `NoHandlerFoundException`을 거쳐 404 `NOT_FOUND`가 된다. `BenefitSearchApiTest`(`/api/unknown` → 404, `POST /api/benefits` → 405 `REQUEST_FAILED`)와 `ApiDocsClosedTest`(local 밖 문서 경로 → 404)가 이 동작을 확인한다.
- `Clock` 빈은 `CollectionConfig`에 있다(`Asia/Seoul`).
- `application.yaml`은 작업 디렉터리의 `.env`를 읽는다. local 프로필은 `.env` 없이 실행되어야 한다(저장소 지침).
- 테스트는 프로필 없이(`ApiDocsTest`만 `local`) `@SpringBootTest`로 뜬다. `src/test/resources`에 `application*.yaml`이 없다.
- Spring Security 7.1.1의 `BCryptPasswordEncoder`는 72바이트를 넘는 입력의 `encode`에서 예외를 던지고, `matches`는 앞 72바이트만 비교해 `true`를 돌려준다(2026-10-10 직접 실행해 확인). 가입은 입력 검증에서 막고, 로그인은 비교 전에 막는다.
- `JwtTimestampValidator`의 기본 시계 오차 허용은 60초다.

## 설계 결정

1. **Spring Security + OAuth2 Resource Server(Nimbus)로 발급·검증한다.** 검증은 `BearerTokenAuthenticationFilter`가, 발급은 `NimbusJwtEncoder`가 한다.
   - 대안: 직접 만든 JWT 필터(jjwt). 흐름이 코드로 보이지만 예외 처리·`SecurityContext` 설정·401 연결을 다시 구현한다.
   - 대안: Security 없이 `HandlerInterceptor`. 가장 가볍지만 A2의 CSRF·쿠키와 이후 CORS를 직접 만들어야 하고 Security를 들이면 다시 쓴다.
2. **비밀번호는 BCrypt(`DelegatingPasswordEncoder`, `{bcrypt}` 접두사)로 저장하고, 8자 이상·72바이트 이하만 받는다(2026-10-09 사용자 결정).** 결정 문서 0013, `DEC-AUTH`에 규칙 기록.
   - 글자 수는 코드 포인트로 센다(이모지 1자). 바이트는 UTF-8이다. 한글만 쓰면 24자까지다. 문자 종류 조합 규칙은 두지 않는다.
   - 대안: Argon2id. 길이 제한이 없지만 BouncyCastle 의존성과 메모리 파라미터 조정이 필요하다. 접두사가 있어 나중에 바꿀 수 있다.
   - 대안: SHA-256으로 줄인 뒤 BCrypt. password shucking 위험이 있어 쓰지 않는다.
3. **이메일은 선조회와 unique 제약을 함께 쓴다.** 선조회가 일반적인 중복을 거절하고, 동시 요청은 `uk_user_account_email`이 막는다. 저장은 `saveAndFlush`로 바로 실행해 위반을 서비스 안에서 잡고 409 `DUPLICATE_EMAIL`로 바꾼다. 제약 이름이 다른 위반은 그대로 던진다.
   - 이메일은 `toLowerCase(Locale.ROOT)`로 바꿔 저장·조회한다. 컬럼 정렬 규칙은 기본값(0001)을 그대로 쓴다. 비교는 정렬 규칙도 대소문자를 무시하므로 저장값만 한 형태로 맞춘다.
   - 대안: email 컬럼만 `utf8mb4_bin`. 비교 규칙이 명시적이지만 0001의 예외가 생긴다.
4. **로그인 실패는 가입 여부와 상관없이 같은 응답(401 `INVALID_CREDENTIALS`)이고, 미가입 이메일에도 가짜 해시를 비교한다.** 응답 시간으로 가입 여부를 추측하지 못하게 한다. 가짜 해시는 서비스 생성 시 임의 문자열을 한 번 인코딩해 둔다. 72바이트를 넘는 비밀번호는 비교 없이 같은 실패로 처리한다(규칙이 공개되어 있어 시간 차이가 정보가 되지 않는다).
   - 한계: 가입 API의 409가 가입 여부를 드러낸다. 완료 조건이 요구하는 동작이라 받아들이고, 이메일 소유 확인(B)에서 "가입 요청은 항상 같은 응답, 결과는 메일" 방식을 검토한다. 0013에 적는다.
5. **액세스 토큰은 HS256으로 서명하고, `sub`에 `user_account.id`를 넣는다.** 결정 문서 0014.
   - 클레임: `iss`(`auth.jwt.issuer`), `sub`(사용자 ID 문자열), `iat`, `exp`(`iat` + 15분), `token_use: "access"`. 검증은 서명·`exp`(시계 오차 0)·`iss`·`token_use`다. `token_use`는 이후 다른 용도의 JWT와 섞이지 않게 한다.
   - 키: `auth.jwt.secret`(환경변수 `JWT_SECRET`, base64로 인코딩한 32바이트 이상). 비어 있으면 기동에 실패한다. 단, `auth.jwt.generate-secret-if-missing: true`이면 기동할 때 임의 키를 만들고 WARN 로그를 남긴다. 이 설정은 `application-local.yaml`과 테스트 설정에만 둔다. local은 `.env` 없이 실행되어야 하고, 지침상 키를 저장소에 둘 수 없기 때문이다. local에서는 재시작하면 기존 토큰이 무효가 된다.
   - 대안: RS256/ES256. 검증을 다른 서버로 나눌 때 필요하다. 지금은 검증하는 서버가 하나라 키쌍 관리 비용만 생긴다.
   - 대안: `sub`에 별도 공개 ID(UUID). 노출되는 곳이 JWT `sub`뿐이고(설계 7), 액세스 토큰이 15분이라 나중에 바꾸기 쉬워 지금 두지 않는다.
   - 만료 판정에 `Clock` 빈을 쓴다. 발급은 `AccessTokenIssuer.issue(userId, issuedAt)`로 시각을 받을 수 있게 해 테스트에서 지난 시각의 토큰을 만든다.
6. **보안 필터 체인은 기본 거부이며 상태를 두지 않는다.**
   - 허용: `/api/benefits/**`, `/api/auth/**`(메서드 무관. `POST /api/benefits`가 지금처럼 405가 되게), `/error`, 문서 경로(`/v3/api-docs/**`, `/swagger-ui.html`, `/swagger-ui/**`). 문서 경로는 local 밖에서는 핸들러가 없어 404다(0012 유지).
   - 그 밖은 `authenticated()`. 세션은 `STATELESS`, `formLogin`·`httpBasic`·`logout`은 끈다. CSRF 보호는 끈다. 인증을 `Authorization` 헤더로만 받아 브라우저가 자동으로 붙이는 자격 증명이 없기 때문이다. A2에서 리프레시 토큰 쿠키를 도입할 때 재발급 경로에 CSRF 방어를 넣는다.
   - 공개 경로에 잘못된 토큰을 보내면 resource server 기본 동작대로 401이다. 보낸 토큰은 항상 검증한다. 클라이언트는 401이면 토큰을 버리거나 재발급한다. 0015에 적는다.
7. **없는 경로는 인증 전에도 404를 유지한다.** `ApiAuthenticationEntryPoint`가 401을 쓰기 전에 `RequestMappingHandlerMapping.getHandler(request)`로 처리할 핸들러가 있는지 본다.
   - `null`이면 404 `NOT_FOUND`. 핸들러가 있거나, 경로는 맞는데 메서드·미디어 타입이 달라 예외가 나면 401 `UNAUTHENTICATED`다. 인증 정보 없는 요청에는 401을 먼저 준다(예: 토큰 없는 `POST /api/me` → 401).
   - 필터 단계라 경로가 파싱되어 있지 않을 수 있다. `ServletRequestPathUtils.hasParsedRequestPath`가 거짓이면 `parseAndCache`한 뒤 조회한다.
   - 401 응답에는 `WWW-Authenticate: Bearer` 헤더를 붙인다(토큰이 있었으면 `error="invalid_token"`). Spring 기본 entry point는 `error_description`(디코딩 오류 문구)과 제공하지 않는 `resource_metadata` 주소를 넣으므로 쓰지 않고 헤더를 직접 쓴다(구현 중 로컬 실행으로 확인).
   - 토큰이 유효한 요청은 그대로 MVC로 가므로 없는 경로는 지금처럼 `NoHandlerFoundException` → 404다.
   - 대안: 컨트롤러에 공개 표시(`@PublicApi`)가 없으면 인증을 요구. 판단이 MVC 단계로 늦어지고 모든 요청에서 핸들러를 확인한다.
   - 대안: `@PreAuthorize`. 붙이지 않은 API가 열리므로 기본 거부가 아니다.
8. **개인 API는 사용자 ID를 경로·파라미터·본문으로 받지 않고 토큰에서만 얻는다.** 결정 문서 0015. 컨트롤러는 `@CurrentUserId Long userId` 파라미터로 받는다. `/api/me` 응답에도 ID를 넣지 않는다.
9. **오류 응답.** 0009 표에 추가한다.
   - `UNAUTHENTICATED`(401): 토큰 없음·형식 오류·위조·만료·토큰 주인 없음. 원인을 나누지 않는다. 클라이언트는 401이면 재발급(A2) 또는 로그인으로 보낸다. 토큰 주인이 없을 때(`ApiException`)도 필터의 401과 같이 `WWW-Authenticate: Bearer error="invalid_token"`을 붙인다.
   - `INVALID_CREDENTIALS`(401): 로그인 실패. 로그인 화면의 실패와 토큰 만료를 클라이언트가 구분하게 `UNAUTHENTICATED`와 나눈다.
   - `DUPLICATE_EMAIL`(409): 이미 가입된 이메일.
   - `ErrorCode.from(401)`은 `UNAUTHENTICATED`로 바꾼다.
   - 상태와 코드를 함께 던지는 `ApiException(HttpStatus status, ErrorCode code)`을 `common.api`에 두고 `GlobalExceptionHandler`가 처리한다. 계정 패키지 예외를 공통 핸들러가 알지 않게 하기 위해서다.
   - 입력 오류에서 `password` 항목은 `value`를 `null`로 둔다. `GlobalExceptionHandler`의 `SENSITIVE_FIELDS = Set.of("password")`로 처리하고 0009 영향에 적는다.
10. **응답 형식.**
    - 가입: 201, `{ "email", "createdAt" }`. 토큰은 주지 않는다. 소유 확인(B) 전에는 개인 기능을 쓸 수 없게 되므로 자동 로그인 이득이 작다.
    - 로그인: 200, `{ "accessToken", "tokenType": "Bearer", "expiresAt" }`. 리프레시 토큰 쿠키는 A2에서 붙인다.
    - 내 정보: 200, `{ "email", "createdAt" }`. 토큰은 유효하지만 사용자 행이 없으면 401 `UNAUTHENTICATED`(탈퇴 이슈 대비).
    - 시각은 `ApiTimes.toOffset`(0010).
    - 계정 컨트롤러는 `ResponseEntity<응답 DTO>`를 반환하고 상태 코드도 여기서 정한다(2026-10-11 사용자 결정). 혜택 API(#29·#30)는 DTO를 바로 반환하므로 #40에서 맞춘다.

## 단계별 구현 계획

1. **의존성** `backend/build.gradle`
   - `implementation 'org.springframework.boot:spring-boot-starter-security-oauth2-resource-server'`
   - `testImplementation 'org.springframework.boot:spring-boot-starter-security-oauth2-resource-server-test'`
2. **마이그레이션** `V4__create_user_account.sql`
   ```sql
   -- 회원 계정. email은 소문자로 저장한다(#38).
   CREATE TABLE user_account (
       id            BIGINT       NOT NULL AUTO_INCREMENT,
       email         VARCHAR(254) NOT NULL,
       password_hash VARCHAR(100) NOT NULL,
       created_at    DATETIME(6)  NOT NULL,
       PRIMARY KEY (id),
       CONSTRAINT uk_user_account_email UNIQUE (email)
   );
   ```
3. **설정**
   - `application.yaml`: `auth.jwt.secret: ${JWT_SECRET:}`, `auth.jwt.issuer: gov-benefit-helper`, `auth.jwt.access-token-ttl: 15m`, `auth.jwt.generate-secret-if-missing: false`.
   - `application-local.yaml`: `auth.jwt.generate-secret-if-missing: true`.
   - `src/test/resources/config/application.yaml`(새 파일): `auth.jwt.generate-secret-if-missing: true`. `classpath:/config/`는 기본 위치라 main의 `application.yaml`을 가리지 않고 덮어쓴다. 기존 테스트가 그대로 뜨는지 확인한다.
   - `backend/.env.example`: `#JWT_SECRET=` 항목과 생성 방법(`openssl rand -base64 32`), local은 비워 두면 임의 키라는 설명.
4. **공통 API** `kr.govbenefit.common.api`
   - `ErrorCode`: `UNAUTHENTICATED("인증이 필요합니다.")`, `INVALID_CREDENTIALS("이메일 또는 비밀번호가 올바르지 않습니다.")`, `DUPLICATE_EMAIL("이미 가입된 이메일입니다.")`. `from`에서 401 → `UNAUTHENTICATED`.
   - `ApiException(HttpStatus status, ErrorCode code)`(`RuntimeException`).
   - `GlobalExceptionHandler.handleApiException(ApiException)`: 상태와 `ApiErrorResponse.of(code)`.
   - `GlobalExceptionHandler.handleMethodArgumentNotValid`: `SENSITIVE_FIELDS`의 값은 `null`.
   - `ApiErrorResponse.code`의 `@Schema` 설명과 `allowableValues`에 새 코드를 더한다.
5. **보안** `kr.govbenefit.common.security`
   - `JwtProperties`(`@ConfigurationProperties("auth.jwt")` record): `secret`, `issuer`, `accessTokenTtl`, `generateSecretIfMissing`.
   - `JwtConfig`: `SecretKey jwtSecretKey(JwtProperties)`(설계 5의 키 규칙. 32바이트 미만이면 기동 실패), `JwtEncoder`(`NimbusJwtEncoder.withSecretKey`), `JwtDecoder`(`NimbusJwtDecoder.withSecretKey(...).macAlgorithm(HS256)`, 검증기 `DelegatingOAuth2TokenValidator`(`JwtTimestampValidator(Duration.ZERO)` + `setClock(clock)`, `JwtIssuerValidator`, `token_use` 검증)).
   - `AccessTokenIssuer`: `AccessToken issue(Long userId)`, `AccessToken issue(Long userId, Instant issuedAt)`. 중첩 record `AccessToken`(`String value`, `Instant expiresAt`). 발급 시각을 초로 잘라 응답의 `expiresAt`과 토큰 `exp`를 맞춘다.
   - `ApiAuthenticationEntryPoint`(`AuthenticationEntryPoint`): 설계 7. 본문은 `JsonMapper`로 `ApiErrorResponse`를 쓴다.
   - `SecurityConfig`: `SecurityFilterChain`(설계 6), `oauth2ResourceServer(jwt)`와 `authenticationEntryPoint(apiAuthenticationEntryPoint)`, `PasswordEncoder`(`PasswordEncoderFactories.createDelegatingPasswordEncoder()`).
   - `CurrentUserId`(파라미터 애너테이션)와 `CurrentUserIdArgumentResolver`: `SecurityContextHolder`의 `JwtAuthenticationToken`에서 `Long.valueOf(getName())`. `WebMvcConfigurer`로 등록.
   - 자동 구성의 기본 사용자(`Using generated security password` 로그)가 만들어지지 않는지 기동 로그로 확인한다.
6. **계정** `kr.govbenefit.account`
   - `domain.UserAccount`(엔티티, `user_account`): `id`, `email`, `passwordHash`, `createdAt`. `static UserAccount create(String email, String passwordHash, LocalDateTime now)`.
   - `domain.Emails.normalize(String)`: `toLowerCase(Locale.ROOT)`.
   - `repository.UserAccountRepository`: `Optional<UserAccount> findByEmail(String)`, `boolean existsByEmail(String)`.
   - `dto.SignUpRequest`: `email`(`@NotBlank`, `@Email`, `@Size(max = 254)`), `password`(`@NotNull`, `@Password`). 메시지는 한국어(0009).
   - `dto.Password`·`dto.PasswordValidator`: 코드 포인트 8 미만이면 `8자 이상이어야 합니다.`, UTF-8 72바이트 초과면 `72바이트 이하여야 합니다(한글은 24자).`. `null`은 통과(`@NotNull`이 맡는다).
   - `dto.LoginRequest`: `email`(`@NotBlank`), `password`(`@NotEmpty`). 형식 검증을 하지 않아 형식이 틀린 이메일도 `INVALID_CREDENTIALS`다.
   - `dto.AccountResponse`(`email`, `createdAt`), `dto.LoginResponse`(`accessToken`, `tokenType`, `expiresAt`). `@Schema` 설명과 시각 예시(`ApiTimes.EXAMPLE`).
   - `service.AccountService`
     - `AccountResponse signUp(SignUpRequest)`: 정규화 → `existsByEmail`이면 `ApiException(CONFLICT, DUPLICATE_EMAIL)` → 인코딩 → `saveAndFlush`. `DataIntegrityViolationException`의 제약 이름에 `uk_user_account_email`이 있으면 같은 409, 아니면 다시 던진다.
     - `LoginResponse login(LoginRequest)`: 설계 4. 성공하면 `AccessTokenIssuer.issue(id)`.
     - `AccountResponse me(Long userId)`: 없으면 `ApiException(UNAUTHORIZED, UNAUTHENTICATED)`.
   - `controller.AuthController`(`/api/auth`): `POST /signup`(201), `POST /login`. `controller.MeController`(`/api/me`): `GET`. 모두 `ResponseEntity`를 반환한다(설계 10). `@Operation`, `@ApiResponse`(400·401·409와 `ApiErrorResponse`).
7. **API 명세** `OpenApiConfig`
   - `DESCRIPTION`을 혜택 API와 계정 API를 함께 설명하도록 고치고, 인증 방법(`Authorization: Bearer <액세스 토큰>`, 15분)을 적는다.
   - `bearerAuth` 보안 스키마(HTTP bearer, JWT)를 등록하고 `MeController`에 `@SecurityRequirement(name = "bearerAuth")`.
8. **테스트**
   - `PasswordValidatorTest`(단위): 7자 거절·8자 통과, 이모지 8개(코드 포인트 8) 통과, 한글 24자(72바이트) 통과·25자 거절, ASCII 72자 통과·73자 거절, `null` 통과.
   - `SignUpApiTest`(`@SpringBootTest`, MockMvc, Testcontainers): 가입 201과 응답, DB의 `password_hash`가 원문과 다르고 `{bcrypt}`로 시작하며 `PasswordEncoder.matches`로 맞는지, 잘못된 이메일·빈 비밀번호·7자·73바이트·빈 이메일이 항목별 `errors`이고 `password`의 `value`가 `null`인지, 중복(대소문자만 다른 이메일 포함) 409 `DUPLICATE_EMAIL`, 저장된 이메일이 소문자인지.
   - `SignUpConcurrencyTest`(`@SpringBootTest`, Testcontainers): 같은 이메일로 `AccountService.signUp`을 스레드 8개에서 `CountDownLatch`로 동시에 부른다. 성공 1, 나머지는 `DUPLICATE_EMAIL`의 `ApiException`, `user_account` 행 1개. 선조회를 통과한 경합이 실제로 생겼는지는 보장하지 않으므로, `existsByEmail`을 건너뛴 경로(제약 위반 변환)는 미리 넣은 행과 같은 이메일을 `saveAndFlush`로 저장하는 서비스 경로 테스트로 따로 확인한다.
   - `LoginApiTest`: 맞는 로그인 → 토큰, `expiresAt - iat`이 15분, 그 토큰으로 `/api/me`가 본인 이메일. 사용자 두 명이 각자 토큰으로 자기 정보만 받는지. 비밀번호 틀림·미가입 이메일·73바이트 비밀번호가 같은 상태·본문(`INVALID_CREDENTIALS`)인지. 이메일 대문자로 로그인해도 성공. 72바이트 비밀번호 뒤에 글자를 붙이면 로그인할 수 없다(BCrypt의 앞 72바이트 비교).
   - `AccessTokenAuthTest`: `/api/me` 요청이 다음에서 401 `UNAUTHENTICATED`이고 `WWW-Authenticate`가 있는지 — 헤더 없음, `Bearer` 없는 헤더, 임의 문자열, 다른 키로 서명, 페이로드의 `sub`를 바꾼 토큰(서명 유지), `alg: none`, `iss` 다름, `token_use` 없음, `issuedAt = now - 15분 - 1초`. `issuedAt = now - 14분 59초`는 200. 토큰은 유효하지만 사용자 행을 지운 경우 401. 토큰 없는 `POST /api/me`는 401. 토큰 없는 `/api/unknown`은 404 `NOT_FOUND`, 유효한 토큰의 `/api/unknown`도 404.
   - `JwtSecretTest`(단위): 키 없음은 기동 실패, 허용 시 32바이트 임의 키, 31바이트·base64 아님은 실패, 설정 문자열에 키가 드러나지 않음.
   - 기존 `BenefitSearchApiTest`·`BenefitDetailApiTest`·`ApiDocsClosedTest`가 바꾸지 않고 통과한다(비회원 API와 404 유지).
   - `ApiDocsTest`: 새 경로의 응답 코드(`/api/auth/signup` 201·400·409·500, `/api/auth/login` 200·400·401·500, `/api/me` 200·401·500)와 스키마 참조, `/api/me`의 `bearerAuth` 요구, 새 시각 필드(`AccountResponse.createdAt`, `LoginResponse.expiresAt`)의 형식.
   - 계정·인증 테스트는 공공 API 클라이언트를 쓰는 경로가 없어 `Gov24StubServer`를 두지 않는다. 혜택 API 테스트의 요청 수 0 확인은 그대로다.
9. **문서**
   - `docs/decisions/0013-password-storage.md`: 설계 2·4. BCrypt·접두사·규칙·가짜 해시·72바이트 처리, 409의 가입 여부 노출 한계.
   - `docs/decisions/0014-access-token.md`: 설계 5. 서명·키 설정·클레임·검증·시계 오차 0·`sub`의 의미, 공개 ID를 둘 시점(사용자 식별자를 URL이나 외부에 노출해야 할 때).
   - `docs/decisions/0015-api-auth-boundary.md`: 설계 6·7·8. 기본 거부, 공개 경로 목록, 없는 경로 404, 개인 API는 사용자 ID를 입력으로 받지 않음, 공개 경로의 잘못된 토큰 401, CSRF를 끈 이유와 A2에서 바꿀 점. 영향에 "새 공개 API는 `SecurityConfig` 허용 목록에 추가한다".
   - `docs/decisions/README.md` 목록 표에 0013~0015.
   - `0009`: 코드 표에 세 코드, 적용 위치에 `ApiException`·`ApiAuthenticationEntryPoint`, 영향에 비밀번호 항목 `value` 제외.
   - `0012`: 적용 위치에 `SecurityConfig`(문서 경로 허용)와 `OpenApiConfig`의 `bearerAuth`.
   - `docs/product/development.md`의 `DEC-AUTH`(로그인·탈퇴) 행에 "비밀번호는 8자 이상·72바이트 이하(2026-10-09, #38)"를 더한다.

## 완료 조건 매핑

- [ ] 이메일과 비밀번호로 가입할 수 있고, 형식이 잘못된 이메일이나 빈 비밀번호는 항목별 오류로 거절된다. → 6단계 `SignUpRequest`·`PasswordValidator`, `SignUpApiTest`·`PasswordValidatorTest`. 400 `INVALID_INPUT`의 `errors[].field`가 `email`·`password`이고 비밀번호 `value`는 `null`이다.
- [ ] 이미 가입된 이메일로 가입하면 거절된다. 같은 이메일로 동시에 가입을 요청해도 계정은 하나만 생긴다(Testcontainers MySQL로 검증). → 설계 3, 2·6단계. `SignUpApiTest`의 409 `DUPLICATE_EMAIL`(대소문자만 다른 경우 포함), `SignUpConcurrencyTest`의 행 1개와 제약 위반 변환.
- [ ] 저장된 비밀번호 값이 원문과 다르고, 원문을 되돌려 얻을 수 없는 형태다. → 설계 2. `SignUpApiTest`가 `password_hash`가 원문과 다르고 `{bcrypt}` 형식이며 `matches`로만 확인됨을 본다.
- [ ] 맞는 이메일·비밀번호로 로그인하면 액세스 토큰을 받는다. 그 토큰으로 내 정보 확인 API를 호출하면 토큰 주인의 계정 정보만 돌아온다. → 설계 5·8·10. `LoginApiTest`의 두 사용자 시나리오.
- [ ] 비밀번호가 틀린 경우와 가입되지 않은 이메일인 경우 모두 같은 응답으로 거절되어, 응답만으로는 가입 여부를 알 수 없다. → 설계 4. `LoginApiTest`가 두 경우의 상태·본문 전체가 같음을 확인한다.
- [ ] 토큰 없이, 위조·변조된 토큰으로, 발급 후 15분이 지난 토큰으로 개인 API를 요청하면 모두 거절된다. → 설계 5·6·7. `AccessTokenAuthTest`.
- [ ] 비회원 검색·상세 API는 이전과 같이 로그인 없이 동작한다. → 설계 6. 기존 `BenefitSearchApiTest`·`BenefitDetailApiTest`가 바꾸지 않고 통과한다.

## 검증 명령

- `backend/`에서 `./gradlew test`
- 로컬 확인(기본 테스트에 포함하지 않음): `SPRING_PROFILES_ACTIVE=local ./gradlew bootRun` 뒤
  ```sh
  curl -s -i -X POST localhost:8080/api/auth/signup -H 'Content-Type: application/json' \
    -d '{"email":"me@example.com","password":"correct-horse"}'
  TOKEN=$(curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
    -d '{"email":"me@example.com","password":"correct-horse"}' | jq -r .accessToken)
  curl -s -i localhost:8080/api/me -H "Authorization: Bearer $TOKEN"
  curl -s -i localhost:8080/api/me
  curl -s -i localhost:8080/api/unknown
  curl -s -i 'localhost:8080/api/benefits?keyword=청년'
  ```
  기동 로그에 임의 JWT 키 생성 WARN이 있고 `Using generated security password`가 없는지 본다.

## 결정 필요

없음
