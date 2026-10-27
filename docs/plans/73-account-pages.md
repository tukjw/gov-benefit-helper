status: done

# 구현 계획 — #73 이메일로 가입·로그인하고 비밀번호 재설정·탈퇴하는 웹 화면 제공

브랜치: `feat/73-account-pages` (develop `fa817a7` 기준)

## 목표 및 범위

- 목표: 계정 API(#38, #45, #47, #51)를 쓰는 웹 화면을 만들어 가입 → 로그인 → 로그인 유지 → 로그아웃, 비밀번호 재설정, 탈퇴까지 브라우저에서 끝낼 수 있게 한다(F2·F4). 액세스 토큰 보관, 자동 재발급, 로그인이 필요한 경로의 처리 방식을 여기서 정해 #74가 그대로 쓰게 한다.
- 포함:
  - 프론트: 인증 상태 모듈, 인증 API 호출, 로그인이 필요한 경로 처리, 가입·로그인·비밀번호 재설정 요청·새 비밀번호·탈퇴 화면, 헤더의 로그인 상태 표시와 로그아웃, 토스트.
  - 백엔드: CORS의 쿠키 전송 허용과 `Retry-After` 응답 헤더 노출, 테스트.
  - 결정 문서(0027을 대체하는 0029, 프론트 인증 처리 0030)와 결정 목록.
- 제외:
  - 프로필·관심 혜택·조건 비교 화면과 그 메뉴: #74.
  - 탭 사이 로그인 상태 동기화. 다른 탭에서 로그아웃해도 이 탭은 메모리의 액세스 토큰이 만료(최대 15분)될 때까지 로그인 상태로 보이고, 그 뒤 재발급이 실패하면 비회원이 된다. 완료 조건에 없고 `DEC-AUTH`의 한계(로그아웃 뒤 액세스 토큰 유효)와 같은 범위다.
  - 로그인 시도 속도 제한(#52, `REQ-ACCOUNT-01` P1).
  - 계정 API의 동작·응답 변경. CORS 설정만 바꾼다.
  - 프론트 테스트 러너 도입(#76에서 제외한 것과 같다). 화면 흐름은 로컬 백엔드로 직접 확인한다(2026-10-26 사용자 결정). 인증 모듈의 단위 테스트가 필요해지면 Vitest 도입을 별도 이슈로 만든다.

## 확인된 사실과 제약

- 계정 API(`kr.govbenefit.account.controller`):
  - 가입 인증번호 요청 `POST /api/auth/email-verification {email}` → 204. 같은 이메일에 60초에 한 번, 최근 24시간 10번까지 보낸다. 넘으면 429 `TOO_MANY_REQUESTS`와 `Retry-After`(초). 이미 가입된 이메일인지는 알려주지 않는다.
  - 인증번호 확인 `POST /api/auth/email-verification/confirm {email, code}` → 200 `{verificationToken, expiresAt}`(30분). 실패는 400 `VERIFICATION_CODE_MISMATCH`(다시 입력), `VERIFICATION_CODE_EXPIRED`·`VERIFICATION_ATTEMPTS_EXCEEDED`(번호를 다시 받음), `INVALID_INPUT`(6자리 숫자 아님). 가장 최근 번호와 비교하므로 다시 받기 전의 이전 번호는 `MISMATCH`다. `EXPIRED`는 10분이 지났거나 이미 맞힌 번호다(`EmailVerificationService.confirm`).
  - 가입 `POST /api/auth/signup {email, password, verificationToken}` → 201 `{email, createdAt}`. 토큰은 주지 않는다. 403 `EMAIL_NOT_VERIFIED`(티켓 만료·사용·다른 이메일), 409 `DUPLICATE_EMAIL`, 400 `INVALID_INPUT`(`errors[].field`).
  - 로그인 `POST /api/auth/login {email, password}` → 200 `{accessToken, tokenType, expiresAt}`와 리프레시 쿠키. 401 `INVALID_CREDENTIALS`.
  - 재발급 `POST /api/auth/refresh` → 200 `LoginResponse`와 새 쿠키. `X-Requested-With` 헤더가 필요하고(`CsrfOriginInterceptor`), 401 `UNAUTHENTICATED`. 다른 탭이 먼저 재발급했으면 쿠키가 이미 바뀌었으므로 401이면 잠시 뒤 한 번 다시 시도하라고 API 문서에 적혀 있다(교체 직후 10초 유예).
  - 로그아웃 `POST /api/auth/logout` → 204와 쿠키 삭제. 쿠키가 없어도 같은 응답이다.
  - 재설정 요청 `POST /api/auth/password-reset {email}` → 가입 여부·발송 제한과 관계없이 204. 재설정 `POST /api/auth/password-reset/confirm {token, newPassword}` → 204. 400 `INVALID_RESET_TOKEN`. 성공하면 그 계정의 리프레시 토큰이 모두 폐기된다.
  - 내 정보 `GET /api/me` → `{email, createdAt}`. 탈퇴 `POST /api/me/withdrawal {password}` → 204와 쿠키 삭제. 400 `PASSWORD_MISMATCH`. 둘 다 `Authorization: Bearer`가 필요하다.
  - 비밀번호 규칙은 8자 이상, UTF-8 72바이트 이하다(`PasswordRule`).
  - 재설정 메일의 링크는 `auth.password-reset.url#token=<토큰>`이다. 값은 `application.yaml`에서 `https://govbenefit.kr/reset-password`, `application-local.yaml`에서 `http://localhost:3000/reset-password`다. 그래서 토큰을 쿼리가 아니라 URL 해시에서 읽는다.
- 리프레시 쿠키(`RefreshTokenCookies`)는 `HttpOnly; Secure; SameSite=Strict; Path=/api/auth`이고 `Domain`이 없다. 로컬의 `localhost:3000`과 `localhost:8080`은 포트만 다른 같은 사이트이고, 운영의 `govbenefit.kr`과 `api.govbenefit.kr`도 같은 사이트(`DEC-APP`)이므로 브라우저가 API에 쿠키를 붙인다. 단 다른 출처라서 `fetch`에 `credentials: "include"`가 있어야 하고, 응답에 `Access-Control-Allow-Credentials: true`가 있어야 쿠키를 저장·전송한다.
- CORS(`CorsConfig`): 허용 출처는 `auth.csrf.allowed-origins`, 메서드는 GET·POST·PUT·DELETE, 요청 헤더는 `Authorization`·`Content-Type`·`X-Requested-With`다. 쿠키 전송은 #73에서 정하기로 남겨 두었고(#76 플랜 제외 항목, 결정 0027), `CorsTest`가 `Access-Control-Allow-Credentials`가 없음을 확인한다.
- `Retry-After`는 CORS 기본 노출 응답 헤더(Cache-Control, Content-Language, Content-Length, Content-Type, Expires, Last-Modified, Pragma)가 아니다. `Access-Control-Expose-Headers`에 넣지 않으면 다른 출처의 스크립트가 읽을 수 없다.
- 프론트(`frontend/src`):
  - React Router 데이터 모드이고, 화면 데이터는 경로의 `loader`로 받는다(결정 0027). 오류 화면은 레이아웃 아래의 경로 없는 자식 경로의 `errorElement`다(`router.tsx`).
  - `lib/api/client.ts`에는 `apiGet`과 `ApiError(status, code, errors)`만 있다. POST, 쿠키, 인증 헤더 처리가 없고, `ApiError`는 응답 헤더를 들고 있지 않다.
  - `main.tsx`는 `StrictMode`로 그린다.
  - `SiteHeader.tsx`와 `NavLinks.tsx`에 "#73에서 붙인다", "#74에서 붙인다"는 주석이 있다. 헤더에는 유틸리티 줄이 아직 없다.
  - `components/ui/`에는 `Badge`, `EmptyState`, `InfoBox`가 있다. 토스트는 없다.
  - 프론트 코드에서 `localStorage`를 쓰는 곳은 화면 설정(`lib/preferences.ts`)뿐이다.
- 디자인(`docs/design/README.md`, 시안 README):
  - F2는 가운데 단일 열 폼(최대 560px), 라벨·입력 높이·에러 표시, 로그인 뒤 원래 화면으로 돌아가기(`returnTo`), 로그인 토스트를 따르고, 간편인증은 이메일·비밀번호로 바꾼다. F4는 시안에 없어 F2의 폼과 안내 박스로 만든다. 탈퇴 버튼은 위험 색이다.
  - 헤더 유틸리티 줄은 오른쪽 정렬 14px이고, 비로그인이면 로그인·회원가입, 로그인이면 사용자 표시와 로그아웃이다. 모바일은 메뉴 패널 아래에 같은 버튼을 둔다. 로그인 버튼 문구는 '로그인'이다.
  - 토스트는 화면 하단 가운데 고정, `role="status"`, 2.8초 뒤 사라진다. 에러 문구는 `role="alert"`, 입력은 `aria-invalid`·`aria-describedby`다.
- 결정 목록의 `채택` 결정 중 이 이슈가 따를 것: 0009(오류는 `code`·`status`로 분기), 0014·0017(토큰), 0019(인증·재설정), 0027(SPA, loader, CORS 출처 목록), 0028(CSS Modules와 토큰, `lucide-react`).
- 0027은 결정 내용에 "쿠키 전송은 허용하지 않는다"가 있고, 채택된 결정 문서의 본문은 고치지 않는다(`docs/decisions/README.md`). 그래서 쿠키 전송을 허용하려면 0027을 새 결정 문서로 대체해야 한다.

## 설계 결정

1. **결정 문서 두 개로 올린다.** #74의 개인 화면도 같은 방식을 따라야 한다.
   - [0029](../decisions/0029-frontend-static-spa-credentials.md)는 0027을 대체한다. 내용은 0027과 같고 CORS 쿠키 전송 허용·`Retry-After` 노출(설계 10)과 폼 제출(`action`, 설계 6)만 바뀐다. 처음에는 인증 처리 문서 하나만 두려 했으나, 0027의 결정 내용과 어긋나 대체가 필요했다(구현 중 확인).
   - [0030](../decisions/0030-frontend-auth-session.md)은 프론트 인증 처리다. 설계 2~5가 그 내용이다.
2. **액세스 토큰은 모듈 변수에만 둔다.** `src/lib/auth/session.ts`가 `{ status: "unknown" | "guest" | "member", email, accessToken }`을 들고, 화면은 `useSyncExternalStore`로 구독한다. React 상태·Context에 토큰을 넣지 않아, 로더·액션 같은 컴포넌트 밖 코드도 같은 값을 쓴다. 저장소와 쿠키에는 쓰지 않는다(`DEC-AUTH`).
3. **로그인 상태는 앱을 열 때 재발급을 한 번 불러 되살린다.** `main.tsx`에서 `restoreSession()`을 시작하고 기다리지 않는다. 공개 화면은 응답을 기다리지 않고 그리고, 로그인이 필요한 경로의 로더와 로그인 화면의 로더만 `await ensureSession()`으로 결과를 기다린다. 재발급이 401이면 비회원이다. 결과가 나오기 전에는 헤더의 로그인 영역을 비워 둬, 로그인 버튼이 잠깐 보였다가 바뀌지 않게 한다.
   - 대안: JS로 읽을 수 있는 "로그인함" 표시 쿠키를 두고 그 쿠키가 있을 때만 재발급을 부른다. 비회원의 401 요청 한 번을 줄이지만 쿠키와 서버 응답을 하나 더 관리해야 하고 실제 상태와 어긋날 수 있다. 제안대로 정했다(2026-10-26 사용자 결정).
4. **재발급은 탭 안에서는 하나만 보내고, 탭 사이에서는 Web Locks로 차례로 보낸다.** 진행 중인 재발급 `Promise`를 모듈 변수로 공유해, 여러 요청이 동시에 401을 받아도 한 번만 보낸다. 탭 사이에서는 `navigator.locks.request`로 잠가, 뒤 탭이 앞 탭이 교체한 쿠키로 보낸다. 재발급이 401이면 비회원으로 바꾼다.
   - 처음 안: 401이면 1초 뒤 한 번 더 시도한다(API 문서의 안내). 쿠키가 없는 비회원도 열 때마다 1초를 기다리게 되어 구현 중 바꿨다. Web Locks를 지원하지 않는 브라우저에서는 잠금 없이 보낸다.
5. **로그인이 필요한 API는 `authFetch`로 부르고, 로그인이 필요한 경로는 로더에서 막는다.**
   - `authFetch`는 메모리의 액세스 토큰을 `Authorization`에 붙인다. 401 `UNAUTHENTICATED`면 재발급하고 원래 요청을 한 번 다시 보낸다. 재발급이 실패하면 `AuthRequiredError`를 던진다. 액세스 토큰의 `expiresAt`으로 미리 재발급하지 않는다. 만료 직후 401을 받고 다시 보내는 것만으로 완료 조건이 채워지고, 시계 차이를 따질 필요가 없다.
   - `memberOnly(request, run)`으로 로더·액션을 감싼다. 비회원이면 `redirect("/login?returnTo=<현재 경로+쿼리>")`를 던진다. 로더·액션에서 `AuthRequiredError`가 나와도 같은 곳으로 보낸다.
   - `returnTo`는 `/`로 시작하고 `//`나 `/\`로 시작하지 않는 값만 받고, 아니면 `/`로 간다. 다른 사이트로 보내는 열린 리다이렉트를 막는다.
6. **폼 제출은 경로의 `action`과 React Router `<Form method="post">`로 한다.** 오류는 `action`이 `{ fieldErrors, formError, retryAt }`을 돌려주고 화면이 `useActionData()`로 그린다. 진행 중 표시는 `useNavigation().state === "submitting"`으로 버튼을 막는다. 데이터는 로더, 변경은 액션으로 #76의 구조와 맞춘다.
   - 대안: 컴포넌트의 이벤트 처리기와 `useState`. 단계가 있는 가입 폼은 더 짧게 쓸 수 있지만 진행 표시·리다이렉트·오류 처리를 화면마다 따로 만들어야 한다.
7. **가입은 한 경로(`/signup`) 안의 세 단계다.** 이메일 → 인증번호 → 비밀번호 순서이고, `intent` 값(`send-code`, `confirm-code`, `signup`)으로 액션을 나눈다. 확인한 이메일과 가입 티켓은 액션 결과로 받아 컴포넌트 상태에만 두고, 다음 단계 제출 때 숨은 입력으로 보낸다. URL에 티켓을 넣지 않는다. 새로고침하면 처음부터 다시 한다(티켓은 30분 동안만 쓸 수 있다). 가입하면 같은 비밀번호로 바로 로그인해 `returnTo`로 보내고 "가입되었습니다." 토스트를 띄운다.
8. **오류는 API의 `code`로 입력 칸에 붙인다.**
   - `INVALID_INPUT`의 `errors[].field` → 같은 이름의 입력 칸.
   - `VERIFICATION_CODE_MISMATCH` → 인증번호 칸 "인증번호가 맞지 않습니다."
   - `VERIFICATION_CODE_EXPIRED`·`VERIFICATION_ATTEMPTS_EXCEEDED` → 인증번호 칸에 API 문구를 보여주고 [인증번호 다시 받기]를 강조한다.
   - `DUPLICATE_EMAIL` → 이메일 표시 아래 "이미 가입된 이메일입니다."와 로그인·비밀번호 재설정 링크.
   - `EMAIL_NOT_VERIFIED` → 이메일 단계로 돌아가 "인증 시간이 지났습니다. 인증번호를 다시 받아 주세요."
   - `INVALID_CREDENTIALS` → 폼 위 오류 "이메일 또는 비밀번호가 올바르지 않습니다."
   - `PASSWORD_MISMATCH` → 탈퇴 화면의 비밀번호 칸.
   - `INVALID_RESET_TOKEN` → 새 비밀번호 화면을 재설정 다시 요청 안내로 바꾼다.
   - 그 밖의 실패는 폼 위 공통 오류 "요청을 처리하지 못했습니다. 잠시 뒤 다시 시도해 주세요."
   - 비밀번호 규칙(8자 이상, UTF-8 72바이트 이하)은 보내기 전에도 같은 문구로 확인하고, 서버 검증을 기준으로 둔다.
9. **발송 제한은 다시 받을 수 있는 시각으로 알린다.** 인증번호 요청이 429면 `Retry-After` 초를 현재 시각에 더해 "오후 3:42 이후에 다시 받을 수 있습니다."로 보여준다. 60초 간격 제한과 하루 10번 제한이 모두 같은 문구다. 다시 받기 버튼은 그 시각까지 막는다. 재설정 요청은 API가 제한 여부를 알려주지 않으므로(가입 여부를 드러내지 않기 위해서) 요청 뒤 항상 같은 안내를 보여주고, 안내에 "메일이 오지 않으면 1분 뒤 다시 요청할 수 있으며, 하루 10번까지 보냅니다."를 넣는다.
10. **CORS는 쿠키 전송을 허용하고 `Retry-After`를 노출한다.** 허용 출처는 명시 목록이라 `allowCredentials`와 함께 쓸 수 있다. 쿠키는 `Path=/api/auth`라 개인 API 요청에는 실리지 않는다. 재발급의 CSRF 방어(`X-Requested-With` + `Origin` 검사)는 그대로다.
11. **로그아웃은 액션만 있는 `/logout` 경로로 한다.** 헤더의 `<Form method="post" action="/logout">`가 API 로그아웃을 부르고, 메모리의 토큰을 지운 뒤 홈으로 보내 "로그아웃되었습니다." 토스트를 띄운다. API 호출이 실패해도 메모리는 지운다.
12. **탈퇴 화면은 `/account/withdrawal`이다.** #74의 개인 화면 경로와 겹치지 않게 계정 관련 화면을 `/account` 아래에 둔다. 진입 링크는 헤더의 이메일 표시다(2026-10-26 사용자 결정).

## 단계별 구현 계획

1. 백엔드 CORS
   - `backend/src/main/java/kr/govbenefit/common/security/CorsConfig.java`: `setAllowCredentials(true)`, `setExposedHeaders(List.of(HttpHeaders.RETRY_AFTER))`. Javadoc의 "쿠키 전송은 아직 허용하지 않는다(#73에서 정한다)"를 결정 10으로 고친다.
   - `backend/src/test/java/kr/govbenefit/common/security/CorsTest.java`:
     - `허용한_출처의_요청에는_허용_헤더가_붙는다`: `Access-Control-Allow-Credentials`가 `true`인지 확인으로 바꾼다.
     - 새 테스트 `허용한_출처의_요청에는_Retry_After를_노출한다`: `Access-Control-Expose-Headers`에 `Retry-After`가 있다.
     - 새 테스트 `허용한_출처의_사전_요청은_쿠키_전송을_허용한다`: `POST /api/auth/refresh` 사전 요청(`x-requested-with`)에 `Access-Control-Allow-Credentials: true`.
2. API 클라이언트 (`frontend/src/lib/api/`)
   - `client.ts`: `ApiError`에 `retryAfterSeconds: number | null`을 더하고 응답의 `Retry-After`를 읽는다. `apiRequest<T>(method, path, { query, body, headers, signal })`를 더한다. JSON 본문, `credentials: "include"`, 204면 `undefined`를 돌려준다. `apiGet`은 `apiRequest`의 GET이다. 쿠키 경로가 `/api/auth`라 공개 API에는 쿠키가 실리지 않는다.
   - `types.ts`: `LoginResponse`, `EmailVerificationConfirmResponse`, `AccountResponse`.
   - `auth.ts`: `requestSignupCode`, `confirmSignupCode`, `signUp`, `login`, `refresh`(`X-Requested-With: fetch`), `logout`, `requestPasswordReset`, `resetPassword`, `getAccount(accessToken)`(로그인 직후 세션에 넣기 전 이메일 조회). `account.ts`: `getMe`, `withdraw`(둘 다 `authFetch`).
3. 인증 상태 (`frontend/src/lib/auth/`)
   - `session.ts`: 상태와 `getSession`, `useSession()`, `getAccessToken`, `signIn(tokens)`(로그인 뒤 `getAccount`로 이메일을 채운다), `signOut()`, `ensureSession()`(앱을 연 뒤 한 번만 복원), `refreshAccessToken()`(설계 3·4).
   - `authFetch.ts`: `authFetch<T>(method, path, options)`와 `AuthRequiredError`(설계 5). 보내는 사이 다른 요청이 재발급했으면 그 토큰으로 다시 보낸다.
   - `guard.ts`: `memberOnly(request, run)`(설계 5. 비회원 확인과 `AuthRequiredError` 처리를 한 함수에 둔다), `loginPath(returnTo)`, `safeReturnTo(value)`.
   - `password.ts`: 비밀번호 규칙 확인(`TextEncoder`로 바이트 수).
   - `main.tsx`: 그리기 전에 `restoreSession()`을 시작한다.
4. 공통 UI
   - `src/components/ui/Toast.tsx`·`Toast.module.css`와 `src/lib/toast.ts`(`showToast(message)`, 2.8초 뒤 사라짐). `RootLayout`에 붙인다. 로그인 뒤 리다이렉트처럼 화면이 바뀐 뒤에 띄워야 하므로 컴포넌트 밖에서 부를 수 있는 모듈 상태로 둔다.
   - `src/components/form/TextField.tsx`·`.module.css`: 라벨, 입력, 도움말, 오류(`aria-invalid`, `aria-describedby`, `role="alert"`). `src/components/form/FormError.tsx`: 폼 위 공통 오류.
   - `src/pages/account/AuthLayout.module.css`: 가운데 단일 열(최대 560px)과 제목·버튼 크기. 시안 7의 수치를 따른다.
5. 헤더
   - `SiteHeader.tsx`: 유틸리티 줄 `AccountLinks`. `unknown`이면 비우고, 비회원이면 [로그인]·[회원가입](로그인 링크에 현재 경로를 `returnTo`로 붙인다), 로그인이면 이메일과 [로그아웃](설계 11)이다. "#73에서 붙인다" 주석을 지운다.
   - 좁은 화면에서도 유틸리티 줄을 메뉴 패널에 넣지 않고 오른쪽 위에 그대로 둔다. 시안은 메뉴 패널 아래에 버튼을 두지만, 메뉴를 열지 않아도 로그인 상태가 보이는 편이 낫다(2026-10-27 사용자 결정). 긴 이메일은 줄임표로 자른다. `MobileMenu.tsx`는 바꾸지 않는다.
   - `src/components/layout/AccountLinks.tsx`와 `SiteHeader.module.css`의 유틸리티 줄 스타일.
6. 로그인 (`src/pages/account/login/`)
   - `loginLoader`: `await ensureSession()` 뒤 이미 로그인했으면 `returnTo`로 보낸다.
   - `loginAction`: `login` → `signIn` → `redirect(safeReturnTo(returnTo))`, "로그인되었습니다." 토스트. 실패는 설계 8.
   - `LoginPage.tsx`: 이메일, 비밀번호, [로그인], 비밀번호 재설정·회원가입 링크. `returnTo`를 숨은 입력과 다른 링크에 이어 붙인다.
7. 가입 (`src/pages/account/signup/`)
   - `signupAction`: `intent`별로 `requestSignupCode`, `confirmSignupCode`, `signUp` → `login` → `signIn` → 리다이렉트(설계 7·8·9).
   - `SignupPage.tsx`: 단계 표시(1 이메일, 2 인증번호, 3 비밀번호). 2단계에 인증번호 입력과 [인증번호 다시 받기], 이메일 바꾸기, 인증번호 유효시간(10분) 안내. 3단계에 비밀번호와 규칙 안내.
8. 비밀번호 재설정 (`src/pages/account/password/`)
   - `/forgot-password`의 `ForgotPasswordPage.tsx`와 `forgotPasswordAction`: 요청 뒤 입력한 이메일과 관계없이 같은 안내(설계 9). 형식 오류만 입력 칸에 붙인다.
   - `/reset-password`의 `ResetPasswordPage.tsx`와 `resetPasswordAction`: `location.hash`에서 `token`을 읽어 숨은 입력으로 보내고, 읽은 뒤 `history.replaceState`로 해시를 지워 주소창·방문 기록에 남기지 않는다. 토큰이 없거나 `INVALID_RESET_TOKEN`이면 다시 요청하는 링크를 보여준다. 성공하면 메모리의 토큰을 지우고(`signOut`) "비밀번호를 바꿨습니다. 새 비밀번호로 로그인해 주세요." 토스트와 함께 `/login`으로 보낸다.
9. 탈퇴 (`src/pages/account/withdrawal/`)
   - `withdrawalLoader`: `memberOnly` 안에서 `getMe`로 이메일을 받는다.
   - `withdrawalAction`: `memberOnly` 안에서 `withdraw` → `signOut` → 홈으로 리다이렉트, "탈퇴했습니다." 토스트. `PASSWORD_MISMATCH`는 비밀번호 칸 오류.
   - `WithdrawalPage.tsx`: 위험 안내 박스("계정과 프로필, 관심 혜택, 로그인 정보가 바로 지워지고 되돌릴 수 없습니다. 같은 이메일로 다시 가입하면 새 계정입니다."), 계정 이메일, 비밀번호, 위험 색 [탈퇴하기].
   - 탈퇴 화면으로 가는 링크는 헤더의 이메일 표시에 둔다. #74가 개인 화면을 만들면 그쪽으로 옮길 수 있다.
10. 라우팅 (`src/router.tsx`)
    - 오류 경계 아래에 `/login`, `/signup`, `/forgot-password`, `/reset-password`, `/account/withdrawal`, `/logout`(액션만)을 더한다. 각 화면은 `usePageTitle`과 `Breadcrumb`를 쓴다.
    - `ErrorPage`가 `AuthRequiredError`를 받으면 로그인 화면으로 보낸다. 로더·액션 밖의 경로로 새어 나온 경우의 안전장치다.
11. 문서
    - `docs/decisions/0029-frontend-static-spa-credentials.md`: 0027을 대체한다(설계 1). 0027은 `상태` 줄만 `대체됨 → 0029`로 바꾼다.
    - `docs/decisions/0030-frontend-auth-session.md`: 설계 결정 2~5. 한계(새로고침마다 재발급 한 번, 탭 사이 로그아웃 미동기화, `Secure` 쿠키라 로컬도 `localhost`로 열어야 함)를 적는다.
    - `docs/decisions/README.md` 목록: 0027 상태, 0029·0030 추가.
    - 코드 주석과 `docs/product/development.md`(`DEC-APP`, `DEC-OPS`)의 0027 참조를 0029로 바꾼다. 끝난 플랜과 0028 본문은 기록이라 그대로 둔다.
    - 주석 정리: `NavLinks.tsx`의 "#74에서 붙인다"는 그대로 둔다.

## 완료 조건 매핑

| 완료 조건 | 구현 단계 | 확인 방법 |
| --- | --- | --- |
| 인증번호 확인 → 가입 → 로그인까지 끝낼 수 있다. 인증번호를 틀리거나 만료된 경우, 이미 가입된 이메일인 경우에는 항목별 오류가 보인다. | 2, 3, 7 | 로컬에서 가입하고 백엔드 로그의 인증번호(`LoggingAccountMailSender`)를 넣어 가입 → 로그인 상태로 홈에 오는지 확인. 틀린 번호와 다시 받기 전의 이전 번호(`MISMATCH`), 이미 맞힌 번호(`EXPIRED`), 이미 가입한 이메일로 각각 해당 칸의 오류 확인 |
| 로그인한 뒤 새로고침해도 로그인 상태가 유지되고, 액세스 토큰이 만료돼도 사용자가 다시 로그인하지 않고 요청이 이어진다. | 3, 5, 9 | 로그인 뒤 새로고침해 헤더에 이메일이 보이는지 확인. `--auth.jwt.access-token-ttl=30s` 인자로 백엔드를 띄우고 30초 뒤 `/account/withdrawal`을 열어 네트워크 탭에서 `GET /api/me` 401 → `refresh` 200 → `GET /api/me` 200 순서와 화면 표시 확인 |
| 리프레시 토큰이 만료·폐기됐으면 로그인 화면으로 가고, 로그인하면 원래 화면으로 돌아온다(`returnTo`). | 3, 5, 6 | 위 설정으로 로그인한 뒤 다른 창에서 비밀번호를 재설정해(모든 리프레시 토큰 폐기) 30초 뒤 `/account/withdrawal`로 이동하면 `/login?returnTo=%2Faccount%2Fwithdrawal`로 가고, 새 비밀번호로 로그인하면 탈퇴 화면으로 돌아오는지 확인. 로그아웃 상태로 `/account/withdrawal`을 직접 열어도 같다. `returnTo=//evil.example`이면 홈으로 간다 |
| 로그아웃한 뒤 새로고침하면 로그아웃 상태다. | 5, 11(설계) | 로그아웃 뒤 새로고침해 헤더가 로그인·회원가입인지, `refresh`가 401인지 확인 |
| 비밀번호 재설정 요청은 가입 여부와 관계없이 같은 안내를 보여준다. 재설정한 뒤에는 새 비밀번호로만 로그인할 수 있다. | 8 | 가입한 이메일과 가입하지 않은 이메일로 요청해 같은 안내인지 확인. 로그의 링크로 재설정한 뒤 이전 비밀번호는 `INVALID_CREDENTIALS`, 새 비밀번호는 로그인되는지 확인. 같은 링크를 다시 열면 다시 요청하는 안내가 보인다 |
| 발송 간격이나 하루 횟수 제한에 걸리면 다시 시도할 수 있는 시점을 알 수 있는 안내가 보인다. | 1, 2, 7, 8 | 가입 2단계에서 60초 안에 [인증번호 다시 받기]를 눌러 시각 안내와 버튼 비활성화 확인(`CorsTest`의 노출 헤더 확인). 재설정 요청 화면의 안내에 간격·횟수가 적혀 있는지 확인 |
| 탈퇴하면 로그아웃 상태가 되고, 같은 이메일로 다시 가입할 수 있다. | 9, 7 | 탈퇴 뒤 헤더가 로그인·회원가입이고 새로고침해도 같은지, 같은 이메일로 가입이 끝나는지 확인. 틀린 비밀번호는 비밀번호 칸 오류 |
| 액세스 토큰이 저장소(localStorage·sessionStorage)나 JS로 읽을 수 있는 쿠키에 남지 않는다. | 3 | 로그인 뒤 개발자 도구의 Application 탭에서 Local Storage·Session Storage에 화면 설정 키만 있는지, 쿠키는 `HttpOnly`인 `refresh_token`뿐인지, 콘솔의 `document.cookie`가 빈 문자열인지 확인. `grep -rn "localStorage\|sessionStorage\|document.cookie" frontend/src` 결과가 `lib/preferences.ts`뿐인지 확인 |

## 검증 명령

- `backend/`에서 `./gradlew test`.
- `frontend/`에서 `npm run lint`, `npm run typecheck`, `VITE_API_BASE_URL=http://localhost:8080 npm run build`.
- 화면 확인: `docker compose -f docker-compose.local.yml up -d`, `backend/`에서 `SPRING_PROFILES_ACTIVE=local ./gradlew bootRun --args='--auth.jwt.access-token-ttl=30s'`, `frontend/`에서 `npm run dev`. 위 표의 확인 방법을 Chrome에서 `http://localhost:3000`으로 따른다(`127.0.0.1`은 `localhost`와 다른 사이트라 쿠키가 실리지 않는다). 빌드 결과도 `npx serve@14.2.6 -s dist -l 3000`으로 로그인 → 새로고침 → 로그아웃을 한 번 확인한다.

## 제안 (이번 범위 밖)

- `backend/src/main/resources/application-local.yaml`의 `csrf.allowed-origins` 주석 "로컬 프론트(Next.js 기본 포트)"는 #76 이후 맞지 않는다. Vite 개발 서버를 3000으로 고정했다는 뜻으로 고치는 것을 별도 정리 이슈로 제안한다.

## 결정 필요

없음. 로그인 상태 복원 방식(설계 3), 탈퇴 화면 경로(설계 12), 프론트 테스트 러너(제외 항목)는 2026-10-26 사용자 결정으로 제안대로 정했다.
