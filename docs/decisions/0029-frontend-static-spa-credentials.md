# 0029. 웹 프론트 정적 SPA 구조와 API 호출(쿠키 전송 허용)

- 상태: 채택
- 날짜: 2026-10-26
- 출처: #76, [플랜](../plans/76-vite-static-frontend.md). 쿠키 전송·폼 제출은 #73, [플랜](../plans/73-account-pages.md)
- 대체: [0027](0027-frontend-static-spa.md)

## 배경

웹 프론트는 Next.js(App Router)였고, 공개 화면(홈·검색·상세)은 Next 서버가 요청마다 API를 불러 렌더링했다(`DEC-APP`). 그래서 배포하려면 Node 서버를 계속 띄워 둬야 했다. 목표는 서버 없이 정적 파일 호스팅만으로 배포하는 것이다. 그런데 Next의 정적 내보내기는 빌드할 때 알 수 없는 혜택 상세 경로와 검색 조건 서버 렌더링을 지원하지 않는다.

브라우저가 API를 직접 부르게 되므로 API는 프론트 출처의 교차 출처 요청을 받아야 한다. 이 전환 전까지 백엔드에는 CORS 설정이 없었다.

0027은 쿠키 전송을 허용하지 않고, 쿠키로 인증하는 화면(#73)에서 정하기로 남겨 두었다. #73에서 리프레시 토큰 쿠키(`HttpOnly; Secure; SameSite=Strict; Path=/api/auth`)를 브라우저가 주고받아야 해 쿠키 전송을 허용한다. 발송 제한 응답의 `Retry-After`도 화면이 읽어야 한다. 그 밖의 결정은 0027과 같고, 폼 제출 방식(`action`)을 더한다.

## 결정

웹 프론트는 React + Vite SPA이고, 빌드 결과는 정적 파일이다. 라우팅은 React Router 데이터 모드(`createBrowserRouter`)를 쓰고, 화면 데이터는 경로의 `loader`가 API를 직접 불러 받는다. 폼 제출은 경로의 `action`이 처리한다. API는 `auth.csrf.allowed-origins`에 있는 출처의 브라우저 요청만 CORS로 허용하고, 쿠키 전송을 허용한다.

- 데이터를 받지 못하면 `loader`가 예외를 던지고 경로의 `errorElement`가 오류 화면을 그린다. 없는 자원은 `data(null, { status: 404 })`를 던져 찾을 수 없음 화면으로 보낸다. 입력이 잘못된 경우(400)처럼 화면 안에서 안내할 결과는 `loader`가 값으로 돌려준다.
- 값을 바꾸는 요청은 `<Form method="post">`와 경로의 `action`으로 보낸다. 입력 오류처럼 화면에서 안내할 결과는 `action`이 값으로 돌려주고 화면이 `useActionData()`로 그린다. 성공하면 `redirect`로 다음 화면에 보낸다.
- 화면 캐시 라이브러리는 두지 않는다. 이동할 때마다 `loader`가 다시 부른다.
- API 주소는 `VITE_API_BASE_URL`이고 빌드할 때 번들에 들어간다. 값 없이 빌드하면 빌드가 실패한다.
- 정적 호스팅은 없는 경로에도 `index.html`을 돌려줘야 한다(SPA fallback).
- 개발 서버와 `vite preview`는 3000번 포트를 쓴다. 로컬 API의 허용 출처(`http://localhost:3000`)와 맞춘다.
- CORS는 `/api/**`에 메서드 GET·POST·PUT·DELETE와 헤더 `Authorization`·`Content-Type`·`X-Requested-With`를 허용한다. 쿠키 전송(`Access-Control-Allow-Credentials: true`)을 허용하고, 응답 헤더 `Retry-After`를 노출한다(`Access-Control-Expose-Headers`).
- 프론트의 API 요청은 `credentials: "include"`로 보낸다. 쿠키 경로가 `/api/auth`라 다른 API에는 쿠키가 실리지 않는다.
- 허용하지 않은 출처의 요청과 사전 요청은 Spring CORS 처리가 403과 일반 텍스트 본문으로 거절한다. [결정 0009](0009-api-error-response.md)의 오류 본문 형식을 따르지 않는 예외다.

## 근거

- 화면이 F1(검색·상세) 하나뿐이고 F2~F4를 아직 시작하지 않아, 지금 바꾸는 비용이 가장 작다.
- `loader`는 "그리기 전에 데이터를 받고, 없으면 찾을 수 없음, 실패하면 오류 화면"이라는 #72의 서버 렌더링 흐름과 모양이 같아 화면 코드가 적게 바뀐다. TanStack Query 같은 캐시 라이브러리는 #72의 "화면은 캐시하지 않는다" 방침과 맞지 않고 의존성만 늘린다.
- `react-router@8.5.0`은 `react >=19.2.7`, `node >=22.22.0`이고, `vite@8.3.5`는 `node ^20.19.0 || >=22.12.0`이다. 저장소의 Node 26(`frontend/.nvmrc`)에서 동작한다(2026-10-26 `npm view`로 확인).
- 허용 출처 목록을 CSRF 방어와 함께 쓰면 두 목록이 어긋나 재발급만 막히거나 반대로 열리는 일이 없다. #45 플랜에서 CORS를 붙일 때 이 목록을 함께 쓰기로 했다.
- 프론트(`govbenefit.kr`)와 API(`api.govbenefit.kr`)는 출처가 달라 `fetch`가 쿠키를 보내려면 `credentials: "include"`와 `Access-Control-Allow-Credentials: true`가 모두 있어야 한다. 허용 출처를 와일드카드 없이 목록으로 지정하므로 함께 쓸 수 있다. 로컬의 `localhost:3000`과 `localhost:8080`도 포트만 다른 같은 사이트라 `SameSite=Strict` 쿠키가 실린다(#73에서 Chrome으로 확인).
- 쿠키를 허용해도 재발급의 CSRF 방어(`X-Requested-With` 헤더와 `Origin` 검사, [결정 0017](0017-refresh-token.md))는 그대로다. 다른 출처의 스크립트는 사전 요청에서 막히고, 허용 출처 목록은 CSRF와 같다.
- `Retry-After`는 CORS 기본 노출 헤더가 아니어서 노출하지 않으면 다른 출처의 스크립트가 읽지 못한다. 발송 제한 안내에 다시 시도할 수 있는 시각이 필요하다(#73).
- 폼 제출을 `action`으로 하면 진행 중 표시(`useNavigation`), 성공 뒤 이동(`redirect`), 로그인이 필요한 경우의 처리를 `loader`와 같은 방식으로 쓴다. 컴포넌트 이벤트 처리기로 보내면 화면마다 이를 따로 만들어야 한다.
- 브라우저는 CORS로 거절된 응답의 본문을 스크립트에 넘기지 않으므로 거절 본문의 형식은 클라이언트 분기에 쓰이지 않는다. 그래서 오류 형식을 맞추려고 CORS 거절 처리를 바꾸지 않았다(2026-10-26 사용자 결정).
- 한계:
  - 첫 HTML에는 내용이 없고 스크립트가 그린다. 스크립트를 실행하지 않는 검색 엔진과 링크 미리보기는 혜택별 제목·내용을 읽지 못한다.
  - SPA fallback 때문에 없는 경로와 없는 혜택도 HTTP 상태 코드는 200이다. 화면만 찾을 수 없음으로 바뀐다.
  - 스크립트를 받기 전까지는 빈 화면이다.

## 적용 위치

- `frontend/vite.config.ts`: 포트, `@` 별칭, `VITE_API_BASE_URL` 빌드 검사
- `frontend/index.html`, `frontend/src/main.tsx`, `frontend/src/router.tsx`: 진입점과 경로
- `frontend/src/pages/**/*Loader.ts`, `frontend/src/pages/account/**/*Route(s).ts`: 화면별 `loader`·`action`
- `frontend/src/lib/api/`, `frontend/src/lib/config.ts`: API 호출과 주소
- `frontend/.env.development`, `frontend/.env.example`, `.github/workflows/ci.yml`: `VITE_API_BASE_URL`
- `backend/src/main/java/kr/govbenefit/common/security/CorsConfig.java`, `SecurityConfig.java`: CORS
- `backend/src/main/resources/application.yaml`, `application-local.yaml`: `auth.csrf.allowed-origins`

## 영향

- 새 화면은 `src/router.tsx`에 경로를 더하고, API 데이터는 `loader`에서 받는다. 첫 로딩 중 문구가 필요하면 경로에 `hydrateFallbackElement`를 둔다.
- 화면을 서버에서 렌더링해야 하는 요구(검색 엔진 노출 등)가 생기면 이 결정을 다시 검토한다.
- 프론트 배포 위치(`DEC-OPS`)는 정적 파일 호스팅이면서 SPA fallback을 설정할 수 있어야 한다. 프론트 출처가 바뀌면 `auth.csrf.allowed-origins`를 고친다.
- 값을 바꾸는 새 화면은 `action`으로 제출한다. 로그인이 필요한 요청과 경로는 [결정 0030](0030-frontend-auth-session.md)을 따른다.
- 노출할 응답 헤더가 더 필요하면 `CorsConfig`의 노출 목록에 더한다.
