# 0030. 웹 프론트 로그인 상태와 인증 요청

- 상태: 채택
- 날짜: 2026-10-26
- 출처: #73, [플랜](../plans/73-account-pages.md)

## 배경

웹 화면에서 가입·로그인·로그인 유지를 처음 만들면서, 액세스 토큰을 어디에 두고 언제 재발급하며 로그인이 필요한 경로를 어떻게 막을지 정해야 했다. `DEC-AUTH`는 액세스 토큰(15분)을 브라우저 메모리에만 두고 리프레시 토큰(14일)을 `HttpOnly` 쿠키로 주고받기로 정했다. 리프레시 토큰은 쓸 때마다 교체되고, 교체된 토큰이 다시 쓰이면 같은 로그인의 토큰을 모두 폐기한다([결정 0017](0017-refresh-token.md)). 그래서 여러 탭이 같은 쿠키로 동시에 재발급하면 로그인이 풀릴 수 있다. 개인 화면(#74)도 같은 방식을 써야 한다.

## 결정

액세스 토큰은 `src/lib/auth/session.ts`의 모듈 변수에만 두고, 앱을 열 때 재발급을 한 번 불러 로그인 상태를 되살린다. 로그인이 필요한 API는 `authFetch`로 부르고, 로그인이 필요한 경로의 `loader`·`action`은 `memberOnly`로 감싼다.

- 로그인 상태는 `unknown`(첫 재발급 결과 전) · `guest` · `member`(이메일)이다. 화면은 `useSession()`으로 구독한다. 액세스 토큰을 React 상태·`localStorage`·`sessionStorage`·쿠키에 쓰지 않는다.
- `main.tsx`가 그리기 전에 `ensureSession()`을 시작한다. 공개 화면은 기다리지 않고, 로그인이 필요한 경로와 로그인 화면의 `loader`만 결과를 기다린다. 재발급이 401이면 비회원이다. 헤더는 결과가 나오기 전 로그인 영역을 비워 둔다.
- 재발급은 탭 안에서는 진행 중인 요청을 함께 기다리고, 탭 사이에서는 Web Locks(`navigator.locks`)로 차례로 보낸다.
- `authFetch`는 액세스 토큰을 `Authorization: Bearer`로 붙인다. 401이면 재발급하고 한 번 다시 보내며, 재발급이 401이면 `AuthRequiredError`를 던진다. 만료 시각으로 미리 재발급하지 않는다.
- `memberOnly(request, run)`은 비회원이거나 `run`이 `AuthRequiredError`를 던지면 `/login?returnTo=<현재 경로>`로 보낸다. `returnTo`는 `safeReturnTo`로 `/`로 시작하고 `//`·`/\`로 시작하지 않는 경로만 받는다.
- 로그아웃·탈퇴·비밀번호 재설정 뒤에는 `signOut()`으로 메모리의 토큰을 지운다.

## 근거

- 모듈 변수는 컴포넌트 밖의 `loader`·`action`과 화면이 같은 값을 쓴다. React Context에 두면 `loader`에서 읽을 수 없다. 저장소에 두지 않아 XSS로 오래 쓸 토큰이 새지 않는다(`DEC-AUTH`).
- 앱을 열 때마다 재발급을 부르면 비회원도 401 요청이 한 번 생긴다. 대안인 JS로 읽을 수 있는 "로그인함" 표시 쿠키는 요청 한 번을 줄이지만 쿠키와 응답을 하나 더 관리해야 하고 실제 상태와 어긋날 수 있어 택하지 않았다(2026-10-26 사용자 결정).
- 재발급 API 문서는 다른 탭이 먼저 교체했으면 잠시 뒤 다시 시도하라고 한다. 처음에는 401이면 1초 뒤 한 번 더 보내기로 했으나, 쿠키가 없는 비회원도 열 때마다 1초를 기다리게 된다. Web Locks로 탭끼리 차례로 보내면 뒤 탭이 앞 탭이 교체한 쿠키로 보내므로 재시도가 필요 없다. 탭 세 개를 동시에 열어 재발급 세 번이 모두 200인 것을 Chrome에서 확인했다(#73). Web Locks를 지원하지 않는 브라우저에서는 잠금 없이 보낸다.
- 액세스 토큰의 `expiresAt`으로 미리 재발급하면 브라우저와 서버의 시계 차이를 따져야 한다. 401을 받고 한 번 다시 보내는 것으로 만료 뒤 요청이 이어진다.
- `returnTo` 검사는 로그인 뒤 다른 사이트로 보내는 열린 리다이렉트를 막는다.
- 한계:
  - 다른 탭에서 로그아웃해도 이 탭은 액세스 토큰이 만료(최대 15분)될 때까지 로그인 상태로 보인다. 탭 사이 로그인 상태를 맞추지 않는다.
  - 새로고침할 때마다 리프레시 토큰이 교체된다.
  - 리프레시 쿠키가 `Secure`라 로컬에서도 `http://localhost`로 열어야 한다. `127.0.0.1`은 다른 사이트이고 허용 출처도 아니다.

## 적용 위치

- `frontend/src/lib/auth/session.ts`: 로그인 상태, `ensureSession`, `refreshAccessToken`, `signIn`, `signOut`
- `frontend/src/lib/auth/authFetch.ts`: `authFetch`, `AuthRequiredError`
- `frontend/src/lib/auth/guard.ts`: `memberOnly`, `loginPath`, `safeReturnTo`
- `frontend/src/main.tsx`: 시작할 때 복원
- `frontend/src/lib/api/auth.ts`, `frontend/src/lib/api/account.ts`: 계정 API
- `frontend/src/components/layout/AccountLinks.tsx`: 헤더의 로그인 상태
- `frontend/src/pages/ErrorPage.tsx`: 새어 나온 `AuthRequiredError` 처리

## 영향

- 로그인이 필요한 API(#74의 프로필·관심 혜택 등)는 `authFetch`로 부른다. 직접 `Authorization`을 붙이지 않는다.
- 로그인이 필요한 경로의 `loader`·`action`은 `memberOnly`로 감싼다.
- 탭 사이 로그인 상태를 맞춰야 하는 요구가 생기면 `BroadcastChannel` 등을 이 결정에 더해 검토한다.
