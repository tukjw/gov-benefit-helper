# 0018. API 인증 경계

- 상태: 채택
- 날짜: 2026-10-14
- 출처: #45, [플랜](../plans/45-refresh-token-logout.md)
- 대체: [0015](0015-api-auth-boundary.md)

## 배경

#45에서 리프레시 토큰 쿠키로 인증하는 재발급·로그아웃이 생겨 0015를 대체한다. 바뀐 점은 두 가지다. 재발급·로그아웃은 `Authorization` 헤더를 읽지 않는다. CSRF 방어는 Spring Security 필터가 아니라 재발급 경로의 인터셉터([0017](0017-refresh-token.md))가 한다. 그 밖의 경계는 0015와 같다.

#38에서 처음으로 로그인이 필요한 API(`/api/me`)가 생겼다. 이후 프로필·관심 목록·조건 비교 등 개인 API가 계속 늘어나므로, 새 API를 만들 때마다 보호 여부를 따로 챙기지 않아도 되는 기준이 필요했다. 동시에 비회원 검색·상세(`DEC-AUTH` 공개 범위)와 없는 경로의 404 응답([0009](0009-api-error-response.md))은 그대로 유지해야 했다.

## 결정

API는 기본으로 막고, 로그인 없이 쓰는 경로만 `SecurityConfig.PUBLIC_PATHS`에 둔다.

- 공개 경로: `/api/benefits/**`, `/api/auth/**`, `/error`, 문서 경로(`/v3/api-docs/**`, `/swagger-ui.html`, `/swagger-ui/**`). 메서드와 관계없이 허용해 잘못된 메서드는 MVC가 405로 응답한다.
- 그 밖의 경로는 유효한 액세스 토큰([0014](0014-access-token.md))이 `Authorization: Bearer` 헤더에 있어야 한다. 없거나 유효하지 않으면 401 `UNAUTHENTICATED`와 `WWW-Authenticate: Bearer` 헤더(토큰을 보냈으면 `error="invalid_token"`)다.
- 처리할 API가 없는 경로는 토큰과 관계없이 404 `NOT_FOUND`다. 경로는 있지만 메서드가 다르면 인증되지 않은 요청에 401을 먼저 준다.
- 개인 API는 사용자 ID를 경로·파라미터·본문으로 받지 않는다. 컨트롤러는 `@CurrentUserId Long userId`로 토큰의 `sub`만 쓴다. 응답에도 사용자 ID를 넣지 않는다.
- 공개 경로에 유효하지 않은 토큰을 보내면 401이다. 보낸 토큰은 항상 검증한다. 단 `POST /api/auth/refresh`와 `POST /api/auth/logout`은 `Authorization` 헤더를 읽지 않고 리프레시 토큰 쿠키로만 판단한다.
- 세션을 만들지 않고, Spring Security의 CSRF 필터와 폼 로그인·HTTP Basic·로그아웃 필터는 끈다. 쿠키로 인증하는 재발급의 CSRF 방어는 [0017](0017-refresh-token.md)의 인터셉터가 한다.

## 근거

- 기본으로 막으면 새 개인 API를 허용 목록에 넣는 것을 잊어도 열리지 않는다. 반대로 개인 경로 접두사만 막는 방식은 접두사 밖에 둔 API가 그대로 열린다. `@PreAuthorize`를 API마다 붙이는 방식도 빠뜨리면 열린다.
- 없는 경로까지 401로 바꾸면 기존 404 응답과 테스트(`BenefitSearchApiTest`, `ApiDocsClosedTest`)가 바뀐다. `ApiAuthenticationEntryPoint`가 401을 쓰기 직전에 `RequestMappingHandlerMapping`으로 처리할 API가 있는지 확인한다. 확인은 인증에 실패한 요청에서만 일어난다. 컨트롤러의 공개 표시를 보고 판단하는 방식은 판단이 MVC 단계로 늦어지고 모든 요청에서 핸들러를 찾는다. 401과 404가 구분되어 보호된 경로가 있다는 것은 드러나지만, API 경로는 명세로 공개되는 정보다.
- 사용자 ID를 입력으로 받지 않으면 다른 사용자 ID를 넣어 남의 데이터를 읽는 요청(`REQ-ACCOUNT-01`)이 API 형태에서부터 생기지 않는다.
- 공개 경로에서 잘못된 토큰을 무시하는 방식은 클라이언트에 편하지만, 검색 결과에 "저장한 혜택" 표시처럼 토큰이 있으면 개인화하는 기능이 생기면 다시 바꿔야 한다. Resource Server 기본 동작을 따르고, 클라이언트는 401이면 토큰을 재발급하거나 버린다.
- 재발급은 액세스 토큰이 만료됐을 때 부르는 API다. 클라이언트의 공통 요청 함수가 만료된 토큰을 붙여 보내면 보낸 토큰을 검증하는 규칙 때문에 401이 되므로, 두 경로에서만 헤더를 읽지 않는다(`SecurityConfig.bearerTokenResolver`). 클라이언트에 헤더를 붙이지 말라고 요구하는 방식은 놓치기 쉽다.
- CSRF는 브라우저가 자동으로 붙이는 자격 증명(쿠키)을 악용하는 공격이다. 쿠키로 인증하는 경로는 재발급·로그아웃뿐이고 개인 API는 `Authorization` 헤더로만 받으므로 해당하지 않는다. Spring Security의 CSRF 필터는 토큰 저장소와 배포 흐름이 필요해 쓰지 않는다([0017](0017-refresh-token.md)).
- 보안 필터에서 거절한 요청은 `GlobalExceptionHandler`를 거치지 않으므로 entry point가 [0009](0009-api-error-response.md) 형식으로 본문을 쓴다.
- `WWW-Authenticate`는 RFC 6750의 최소 형식(`Bearer`, `Bearer error="invalid_token"`)만 쓴다. Spring Security 7.1.1의 `BearerTokenAuthenticationEntryPoint`는 디코딩 오류 문구(`error_description`, 예: `Signed JWT rejected: Invalid signature`)와 RFC 9728 보호 자원 메타데이터 주소(`resource_metadata="…/.well-known/oauth-protected-resource"`)를 넣는다. 이 서버는 메타데이터 문서를 제공하지 않고, 해석기가 `null`을 돌려주면 `resource_metadata="null"`이 된다(2026-10-10 확인). 거절 이유는 응답에 나누지 않는다는 0009·0014의 방침과도 맞춘다.

## 적용 위치

- `backend/src/main/java/kr/govbenefit/common/security/SecurityConfig.java`: 필터 체인과 공개 경로, 재발급·로그아웃의 `Authorization` 무시(`bearerTokenResolver`)
- `backend/src/main/java/kr/govbenefit/common/security/ApiAuthenticationEntryPoint.java`: 401·404 응답
- `backend/src/main/java/kr/govbenefit/common/security/CurrentUserId.java`, `CurrentUserIdArgumentResolver.java`, `CurrentUserIdConfig.java`: 요청자 ID
- `backend/src/main/java/kr/govbenefit/common/api/GlobalExceptionHandler.java`: 토큰 주인이 없을 때의 401
- `backend/src/test/java/kr/govbenefit/common/security/AccessTokenAuthTest.java`, `account/controller/RefreshTokenApiTest.java`

## 영향

- 새 공개 API는 `SecurityConfig.PUBLIC_PATHS`에 경로를 추가하고, 토큰 없이 호출하는 테스트를 둔다.
- 새 개인 API는 `@CurrentUserId`로 사용자를 정하고, 다른 사용자 ID를 입력으로 받지 않는다. 명세에는 `@Operation(security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))`를 단다.
- 쿠키로 인증하는 경로를 더하면 `bearerTokenResolver`와 CSRF 방어([0017](0017-refresh-token.md)) 적용 여부를 함께 정한다.
- 프론트(`DEC-APP`)를 붙일 때 CORS를 이 필터 체인에 추가한다.
- 공개 API에서 토큰이 있으면 개인화하는 기능이 필요하면 이 결정을 대체한다.
