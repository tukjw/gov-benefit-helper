status: done

# 구현 계획 — #76 웹 프론트엔드를 정적 파일로 배포할 수 있게 React + Vite로 전환

브랜치: `refactor/76-vite-static-frontend` (develop `1105ea3` 기준)

## 목표 및 범위

- 목표: Next.js(App Router) 프론트를 React + Vite SPA로 바꿔, 빌드 결과를 Node 서버 없이 정적 파일 호스팅만으로 배포할 수 있게 한다. 지금 있는 화면(홈·검색·상세·찾을 수 없음·오류)과 동작은 그대로 둔다. 브라우저가 API를 직접 부르므로 백엔드에 CORS를 추가한다.
- 포함:
  - 프론트: 빌드 도구·라우팅·데이터 불러오기·화면 설정 스크립트·린트·타입 검사 구성을 바꾼다. 화면 컴포넌트와 CSS Modules는 옮기고 Next 전용 API만 바꾼다.
  - 백엔드: 프론트 출처의 브라우저 요청을 허용하는 CORS 설정과 테스트.
  - CI의 프론트 빌드 환경 변수, `AGENTS.md`, `DEC-APP`·`DEC-OPS`, 개발 순서 문서, 개요 문서, 결정 문서.
- 제외:
  - 화면 모양·문구·동작 변경. 옮기면서 Next 전용 코드 때문에 바뀌는 부분만 고친다.
  - CORS의 쿠키 전송 허용(`allowCredentials`): #73. 지금 화면은 쿠키를 쓰지 않는다(2026-10-26 사용자 결정).
  - 배포 위치와 호스팅 설정: `DEC-OPS`. 이 이슈는 어디서든 모든 경로를 `index.html`로 돌려주면 동작하게만 만든다.
  - 끝난 이슈의 플랜(#47, #58, #64, #72) 수정. 플랜은 기록이라 고치지 않는다(2026-10-26 사용자 결정, 이슈 본문의 작업 내용과 완료 조건도 함께 고쳤다).
  - `docs/design/handoff/README.md`의 Next.js 권장 문구. 시안 원본을 고치지 않고 보관한다는 방침(`docs/design/README.md`)을 따른다.
  - 테스트 러너 도입, 검색 엔진용 사전 렌더링.

## 확인된 사실과 제약

- 프론트(`frontend/`): `next@16.4.2`, `react@19.3.0`, `cacheComponents: true`. Node는 `.nvmrc`의 26이다.
  - Next 전용 API를 쓰는 곳: `next/link`(10개 파일), `next/navigation`의 `notFound`(`app/benefits/[serviceId]/page.tsx`)와 `usePathname`(`components/layout/ActiveNavLinks.tsx`), `Metadata`·`generateMetadata`, `PageProps`·`LayoutProps`, `app/error.tsx`의 `retry`.
  - API는 서버 컴포넌트 `app/benefits/_components/BenefitSearch.tsx`와 `app/benefits/[serviceId]/page.tsx`에서만 부른다. `lib/api/client.ts`는 `cache: "no-store"`, `lib/api/benefits.ts`는 React `cache`를 쓴다. 둘 다 서버에서만 의미가 있다.
  - 검색창(`components/SearchBox.tsx`)과 필터(`app/benefits/_components/FilterPanel.tsx`)는 `<form action="/benefits" method="get">`이다. SPA에서 이대로 두면 제출할 때마다 문서 전체를 다시 불러온다.
  - 화면 설정 스크립트(`lib/preferences.ts`의 `PREFERENCES_SCRIPT`)는 `components/InlineScript.tsx`로 `<head>`에 넣는다. `usePreference`의 세 번째 인자는 서버 렌더링·하이드레이션용 기본값이다.
  - 브라우저에서 쓰는 클라이언트 컴포넌트는 `RegionSelect`, `FilterDisclosure`, `ViewToggle`, `MobileMenu`, `ActiveNavLinks`, `error.tsx`다. `SiteHeader`·`MobileMenu`는 `usePathname` 때문에 `<Suspense>`로 감싼 우회가 있다.
  - API 주소는 `NEXT_PUBLIC_API_BASE_URL`이고 `.env.development`에 로컬 기본값(`http://localhost:8080`)이 있다. 값이 없으면 `lib/config.ts`의 `apiBaseUrl()`이 예외를 던진다.
- 패키지(2026-10-26 `npm view`로 확인):
  - `vite@8.3.5`는 `node ^20.19.0 || >=22.12.0`, `@vitejs/plugin-react@6.1.2`.
  - `react-router@8.5.0`은 `react >=19.2.7`, `node >=22.22.0`이다. 패키지의 타입 정의에 `createBrowserRouter`, `RouterProvider`, `useLoaderData`, `useRouteError`, `isRouteErrorResponse`, `Form`, `useNavigation`이 있다.
  - Vite React 템플릿의 린트 구성은 `@eslint/js`, `typescript-eslint`, `eslint-plugin-react-hooks`, `eslint-plugin-react-refresh`, `globals`다.
- React Router 데이터 모드의 첫 로딩(`docs/explanation/hydration.md`): 로더가 끝나기 전에는 경로 컴포넌트 대신 `hydrateFallbackElement`를 그리고, 없으면 가장 가까운 상위 경로의 대체 화면을 쓴다. 대체 화면 안에는 `<Outlet/>`을 둘 수 없다.
- Vite는 `VITE_` 접두사 환경 변수만 `import.meta.env`로 번들에 넣는다. `vite dev`는 `.env.development`를, `vite build`는 `.env.production`을 읽는다. `vite preview`는 없는 경로에 `index.html`을 돌려준다.
- 백엔드:
  - `SecurityConfig`에 CORS 설정이 없다. 공개 경로는 `/api/benefits/**`, `/api/auth/**`, `/api/matches/**`, `/api/profile/options`다.
  - 컨트롤러가 쓰는 메서드는 GET, POST, PUT, DELETE다. 요청 헤더는 `Authorization`, `Content-Type`, 재발급의 `X-Requested-With`(`CsrfOriginInterceptor`)를 쓴다.
  - 허용 출처 목록 `auth.csrf.allowed-origins`(`CsrfProperties`)가 이미 있다. `application.yaml`은 `https://govbenefit.kr`, `application-local.yaml`은 `http://localhost:3000`이다. #45 플랜은 CORS를 붙일 때 이 목록을 함께 쓰기로 적어 두었다.
  - Spring의 기본 CORS 처리는 허용하지 않은 `Origin`의 요청과 사전 요청을 403으로 거절한다. 테스트로 확인한다.
- 문서에서 서버 렌더링을 설명하는 곳: `docs/product/development.md` 41행(화면 순서 안내)과 116행(`DEC-APP`), `docs/product/overview.md` 24행(Next.js 웹), `docs/decisions/0026`의 배경·근거, `AGENTS.md` 8·20행. 끝난 플랜은 제외한다.
- [결정 0024](../decisions/0024-frontend-package-and-ci.md)의 결정 내용(npm, `.nvmrc`, `changes` → `frontend-check` → `frontend` 구조, 검사 명령 이름)은 그대로 유지된다. 근거의 `next typegen`은 당시 기록이므로 대체하지 않는다.
- [결정 0026](../decisions/0026-frontend-styling-and-preferences.md)은 배경과 근거가 Next 서버 렌더링(`cacheComponents`, 하이드레이션)을 전제로 하고, 적용 위치가 `app/` 경로라 그대로 둘 수 없다.

## 설계 결정

1. **프론트 구조는 [결정 0027](../decisions/0027-frontend-static-spa.md)로 올린다.** #73·#74의 화면도 따라야 한다.
   - React + Vite SPA이고, 라우팅은 React Router 데이터 모드(`createBrowserRouter`)를 쓴다.
   - 화면 데이터는 경로의 `loader`에서 API를 직접 불러 받는다. 그리기 전에 데이터를 받고, 실패하면 경로의 `errorElement`로 간다. 지금의 "서버에서 받아 그리고, 없으면 찾을 수 없음, 실패하면 오류 화면" 흐름과 같은 모양이라 화면 코드가 적게 바뀐다. TanStack Query 같은 캐시 라이브러리는 두지 않는다. 캐시하지 않는다는 #72의 방침과 같다.
   - API 주소는 `VITE_API_BASE_URL`이며 빌드할 때 번들에 들어간다. 값 없이 빌드하면 실패하게 해, 운영 빌드에 주소가 빠지거나 로컬 주소가 들어가지 않게 한다.
   - 정적 호스팅은 모든 경로에 `index.html`을 돌려줘야 한다(SPA fallback). 그래서 없는 경로도 상태 코드는 200이다.
   - API의 CORS 허용 출처는 `auth.csrf.allowed-origins`를 함께 쓴다. 출처 목록을 하나로 두어 CSRF 허용 출처와 CORS 허용 출처가 어긋나지 않게 한다. 쿠키 전송 허용은 #73에서 정한다.
2. **스타일·화면 설정 결정은 [결정 0028](../decisions/0028-frontend-styling-and-preferences-spa.md)로 0026을 대체한다.** 결정 내용(KRDS 토큰만 따름, CSS Modules와 CSS 변수, rem, `pretendard-gov` 동적 서브셋, `lucide-react`, `localStorage` + 첫 페인트 전 스크립트 + `<html data-view>`)은 그대로다. 바뀌는 것은 스크립트를 넣는 방법(`index.html`)과 근거(서버가 없으므로 하이드레이션 불일치가 없다)와 적용 위치다.
3. **화면 설정 스크립트는 Vite 플러그인이 `index.html`의 `<head>`에 넣는다.** `vite.config.ts`의 `transformIndexHtml`에서 `src/lib/preferences.ts`의 `PREFERENCES_SCRIPT`를 불러와 넣는다. 저장 키와 허용 값을 `lib/preferences.ts` 한 곳에 둔다. `index.html`에 스크립트를 직접 쓰면 키가 두 곳에 생긴다.
4. **파일 위치는 Vite 관례대로 `src/` 아래로 옮긴다.** `app/`과 `_components`는 Next 라우팅 관례라 남기지 않는다. `git mv`로 옮겨 이력을 유지하고, `@/*` 별칭은 `src/*`를 가리킨다.
   - `app/page.tsx` → `src/pages/HomePage.tsx`
   - `app/benefits/page.tsx` → `src/pages/benefits/BenefitsPage.tsx`, `app/benefits/_components/` → `src/pages/benefits/components/`
   - `app/benefits/[serviceId]/page.tsx` → `src/pages/benefits/detail/BenefitDetailPage.tsx`, `_components/` → `src/pages/benefits/detail/components/`
   - `app/not-found.tsx`·`app/error.tsx`·`app/benefits/[serviceId]/not-found.tsx` → `src/pages/NotFoundPage.tsx`·`src/pages/ErrorPage.tsx`·`src/pages/benefits/detail/BenefitNotFound.tsx`
   - `app/layout.tsx` → `src/components/layout/RootLayout.tsx`, `app/globals.css` → `src/globals.css`, `app/icon.svg` → `public/icon.svg`
   - `components/`, `lib/` → `src/components/`, `src/lib/`
5. **검색창과 필터는 React Router의 `<Form method="get">`으로 바꾼다.** 문서를 다시 불러오지 않고 URL만 바꿔 경로 로더를 다시 실행한다. 입력 이름과 제출 결과 URL은 지금과 같다.
6. **로딩 표시는 지금 문구를 그대로 쓴다.** 첫 로딩에는 검색·상세 경로의 `hydrateFallbackElement`로 지금의 `<Suspense>` 대체 문구("검색 결과를 불러오는 중입니다." 등)를 보여, 헤더·푸터는 먼저 그려지게 한다. 화면 이동 중에는 이전 화면을 두고 `useNavigation`으로 `<main aria-busy>`를 켠다.
7. **없는 혜택은 로더가 404 응답을 던져 상세 경로의 `errorElement`가 처리한다.** `isRouteErrorResponse`이고 상태가 404면 `BenefitNotFound`, 그 밖에는 `ErrorPage`다. 오류 화면이 헤더·푸터 안에 그려지도록 `errorElement`를 레이아웃 경로 아래의 경로 없는 자식 경로에 둔다. 다시 시도 버튼은 `useRevalidator().revalidate()`로 로더를 다시 실행한다.
8. **CI의 필수 체크와 검사 명령 이름은 그대로 둔다.** `npm run lint`·`typecheck`·`build`의 내용만 바꾸고, `ci.yml`은 빌드 단계의 환경 변수 이름만 고친다. 결정 0024와 ruleset을 건드리지 않는다.
9. **허용하지 않은 출처의 요청은 Spring CORS 처리의 기본 응답(403, 일반 텍스트)으로 거절한다.** [결정 0009](../decisions/0009-api-error-response.md)의 JSON 오류 형식을 따르지 않는 예외로 결정 0027에 적는다. 브라우저는 CORS로 거절된 응답의 본문을 스크립트에 넘기지 않아 형식이 쓰이지 않는다. 대안: CORS 거절 처리를 바꿔 새 오류 코드로 JSON을 보낸다. 변경이 늘고 읽을 클라이언트가 없어 제외했다(2026-10-26 사용자 결정). 다른 출처의 재발급은 `CsrfOriginInterceptor`보다 CORS가 먼저 거절하므로 `RefreshTokenApiTest`의 해당 확인을 `CSRF_REJECTED` 대신 403으로 바꾼다.

## 단계별 구현 계획

1. 빌드 도구와 의존성 (`frontend/`)
   - `npm uninstall next eslint-config-next`, `npm install react-router`, `npm install -D vite @vitejs/plugin-react @eslint/js typescript-eslint eslint-plugin-react-hooks eslint-plugin-react-refresh globals`. `eslint`는 지금의 9를 유지하고 `@eslint/js`도 9로 맞춘다(`@eslint/js@10`은 `eslint@10`이 필요하다). `package-lock.json`을 함께 커밋한다.
   - `package.json` 스크립트: `dev: vite`, `build: vite build`, `preview: vite preview`, `lint: eslint .`, `typecheck: tsc --noEmit`. `start`는 지운다.
   - `vite.config.ts`: React 플러그인, `@` 별칭, `server.port`·`preview.port`를 3000으로 고정(`strictPort`), 화면 설정 스크립트 주입 플러그인(설계 3), `build` 명령에서 `VITE_API_BASE_URL`이 없으면 실패(설계 1).
   - `tsconfig.json`: Next 플러그인과 `.next` 경로를 지우고 `src`와 `vite.config.ts`를 포함한다. `types`에 `vite/client`를 넣는다.
   - `eslint.config.mjs`: Vite React 템플릿 구성으로 바꾸고 `dist`를 무시한다.
   - `.gitignore`: Next 항목을 지우고 `/dist`를 넣는다. `next.config.ts`를 지운다.
   - `.env.development`, `.env.example`: 변수 이름을 `VITE_API_BASE_URL`로 바꾸고 서버 렌더링 언급을 지운다.
2. 진입점과 라우팅
   - `index.html`: `lang="ko"`, `charset`, `viewport`, 기본 제목과 설명(지금 `metadata`), `/icon.svg`, `#root`, `src/main.tsx`.
   - `src/main.tsx`: 글꼴 CSS와 `globals.css`를 불러오고 `createRoot`로 `RouterProvider`를 그린다.
   - `src/router.tsx`: `RootLayout` → 경로 없는 자식(`errorElement: ErrorPage`) 아래에 `/`(HomePage), `/benefits`(`benefitsLoader`, BenefitsPage), `/benefits/:serviceId`(`benefitDetailLoader`, BenefitDetailPage, `errorElement: BenefitDetailError`), `*`(NotFoundPage).
   - `src/components/layout/RootLayout.tsx`: `SkipLink` → `SiteHeader` → `<main id="main" tabIndex={-1} aria-busy={…}>` + `<Outlet/>` → `SiteFooter`, `<ScrollRestoration/>`.
   - `src/globals.css`: 헤더·본문·푸터가 `<body>` 바로 아래가 아니라 `#root` 안에 그려지므로, `#root`도 세로 flex로 두어 푸터가 화면 아래에 붙게 한다.
3. 화면 설정
   - `src/lib/preferences.ts`: 주석의 서버 언급을 고치고, 서버용 기본값에만 쓰던 `defaultPreference`를 지운다.
   - `src/components/preferences/usePreference.ts`: `useSyncExternalStore`의 세 번째 인자(서버용 기본값)를 지운다.
   - `src/components/InlineScript.tsx`를 지운다.
4. 공통 레이아웃과 링크
   - `next/link`를 `react-router`의 `Link`로 바꾼다(`href` → `to`).
   - `NavLinks.tsx`가 `useLocation()`으로 현재 메뉴를 표시하게 하고, `ActiveNavLinks.tsx`와 `SiteHeader`·`MobileMenu`의 `<Suspense>` 우회를 지운다.
   - `"use client"` 지시문을 모두 지운다.
   - 화면 제목은 `src/components/usePageTitle.ts`가 `document.title`을 바꿔 정한다. 형식은 지금 `metadata.title.template`과 같은 `"%s | 정부 혜택 도우미"`이고, 이름이 없으면 서비스 이름만 쓴다. React 19의 `<title>` 요소는 `index.html`의 기본 `<title>`과 함께 `<head>`에 남을 수 있어 쓰지 않는다.
5. API와 검색 조건
   - `src/lib/config.ts`: `import.meta.env.VITE_API_BASE_URL`을 읽는다.
   - `src/lib/api/client.ts`: `cache: "no-store"`와 서버 전용 주석을 지운다. 로더가 넘기는 `AbortSignal`을 받는 인자를 더해, 이동이 취소되면 요청도 끊는다.
   - `src/lib/api/benefits.ts`: React `cache`를 지운다.
   - `src/lib/benefits/searchParams.ts`: `parseSearchParams`가 `URLSearchParams`를 받게 바꾼다(`get`·`getAll`). 정리 규칙은 그대로다.
6. 전체 혜택 화면
   - `src/pages/benefits/benefitsLoader.ts`의 `benefitsLoader({ request })`: URL에서 조건을 읽고 `getRegions()`와 검색을 함께 부른다. 400은 지금처럼 `{ kind: "invalid" }`로 바꾸고, 그 밖의 실패는 던진다.
   - `BenefitsPage.tsx`: 브레드크럼·제목과 `BenefitSearch`. `BenefitSearch.tsx`는 `useLoaderData()`로 받아 그리는 동기 컴포넌트가 된다. 조건이 바뀌면 폼을 새로 그리는 `key`는 그대로 둔다.
   - `SearchBox.tsx`, `FilterPanel.tsx`: `<Form action="/benefits" method="get">`(설계 5). "JS 없이도 적용"이라는 주석을 고친다.
7. 혜택 상세 화면
   - `src/pages/benefits/detail/benefitDetailLoader.ts`의 `benefitDetailLoader({ params, request })`: `getBenefitDetail`이 `null`이면 `data(null, { status: 404 })`를 던진다.
   - `BenefitDetailPage.tsx`: `useLoaderData()`로 받아 `usePageTitle`(혜택 이름)을 부르고 `BenefitDetailView`를 그린다. 로더는 화면 파일과 나눠 둔다(`react-refresh` 린트 규칙이 컴포넌트 파일에서 컴포넌트가 아닌 내보내기를 막는다).
   - `BenefitDetailError.tsx`: 404면 `BenefitNotFound`(제목 "혜택을 찾을 수 없음"), 아니면 `ErrorPage`(설계 7).
8. 찾을 수 없음·오류 화면
   - `NotFoundPage.tsx`: 지금 `app/not-found.tsx`와 같다.
   - `ErrorPage.tsx`: `useRevalidator().revalidate()`로 다시 시도한다.
9. 백엔드 CORS
   - `backend/src/main/java/kr/govbenefit/common/security/CorsConfig.java`: `CorsConfigurationSource` 빈. `/api/**`에 대해 `CsrfProperties.allowedOrigins()`, 메서드 GET·POST·PUT·DELETE, 헤더 `Authorization`·`Content-Type`·`X-Requested-With`를 허용한다. 쿠키 전송은 허용하지 않는다.
   - `SecurityConfig`: `.cors(Customizer.withDefaults())`를 더한다.
   - `CsrfProperties`의 Javadoc에 CORS도 이 목록을 쓴다고 적는다.
   - `backend/src/test/java/kr/govbenefit/common/security/CorsTest.java`(Testcontainers MySQL, MockMvc):
     - 허용 출처의 `GET /api/benefits/regions`에 `Access-Control-Allow-Origin`이 붙는다.
     - 허용 출처의 사전 요청(`OPTIONS`, `Access-Control-Request-Method: POST`, `Access-Control-Request-Headers: Content-Type`)이 200이고 허용 헤더가 붙는다.
     - 허용하지 않은 출처의 요청과 사전 요청은 403이다.
     - `Origin`이 없는 요청은 지금처럼 처리된다.
   - `RefreshTokenApiTest`: 다른 출처의 재발급은 403만 확인한다(설계 9).
10. CI와 저장소 지침
    - `.github/workflows/ci.yml`: `frontend-check`의 빌드 환경 변수를 `VITE_API_BASE_URL`로 바꾼다.
    - `AGENTS.md`: 저장소 구성의 `frontend/` 설명(React + Vite SPA, React Router, 정적 빌드)과 로컬 실행의 환경 변수 이름을 고친다.
11. 문서
    - `docs/decisions/0027-frontend-static-spa.md`: 설계 결정 1. 근거와 한계(검색 엔진·링크 미리보기가 혜택별 내용을 읽기 어려움, 없는 경로도 200, JS를 받기 전에는 빈 화면)를 적는다.
    - `docs/decisions/0028-frontend-styling-and-preferences-spa.md`: 설계 결정 2, 0026을 대체한다. 0026은 `상태` 줄만 `대체됨 → 0028`로 바꾼다.
    - `docs/decisions/README.md` 목록: 0026 상태, 0027·0028 추가.
    - `docs/product/development.md`:
      - 41행: 모든 화면을 브라우저에서 렌더링하는 정적 SPA라고 고친다.
      - `DEC-APP`: 클라이언트를 React + Vite SPA(PWA)로, 공개 화면 서버 렌더링을 정적 SPA로 바꾸고, 근거에 정적 호스팅 이유와 한계를 적는다. Next.js 서버 언급은 지운다. 날짜 칸에 2026-10-26 변경(#76)을 더한다.
      - `DEC-OPS`: 프론트 호스팅은 모든 경로를 `index.html`로 돌려줘야 한다는 조건을 더한다.
    - `docs/product/overview.md` 24행: 클라이언트를 React + Vite 웹(PWA)으로 고친다.
12. 마무리: `docs/` 아래에서 끝난 플랜과 대체된 결정 문서를 뺀 곳에 서버 렌더링 설명이 남았는지 검색하고, 코드 주석의 `#72 설계 2`(요청마다 서버 렌더링) 같은 서버 전제 문구를 지운다.

## 완료 조건 매핑

| 완료 조건 | 구현 단계 | 확인 방법 |
| --- | --- | --- |
| 프론트 빌드 결과를 정적 파일 서버로만 띄웠을 때 홈 → 검색 → 상세 → 신청처 이동이 지금처럼 동작한다 | 1, 2, 4~7 | `dist/`를 `npx serve@14.2.6 -s dist -l 3000`으로 띄우고 로컬 백엔드로 홈 검색 → 결과 카드 → 상세의 신청 버튼 또는 공식 안내 링크까지 이동 |
| 필터·페이지가 들어간 검색 URL이나 상세 URL을 새 창에서 열거나 새로고침해도 같은 화면이 나온다 | 1, 5, 6 | 위 서버에서 `/benefits?keyword=출산&field=보건·의료&page=2`와 상세 URL을 새 창으로 열고 새로고침 |
| 없는 혜택 ID로 들어가면 찾을 수 없음 화면이, API가 실패하면 오류 화면이 나온다 | 7, 8 | `/benefits/000000000000`. 백엔드를 끈 뒤 `/benefits`와 상세를 열고, 다시 켠 뒤 다시 시도 |
| 보기 방식 설정이 저장되고, 새로고침할 때 화면이 깜빡이지 않는다 | 1, 3 | 목록형을 고르고 새로고침해 `<html data-view="list">`와 선택 표시가 첫 화면부터 유지되는지, 빌드 결과의 `index.html` `<head>`에 스크립트가 있는지 확인 |
| 허용한 프론트 출처에서 보낸 브라우저 요청은 API가 받고, 허용하지 않은 출처의 요청은 거부한다 | 9 | `CorsTest`. 로컬에서 `curl -i -H 'Origin: http://localhost:3000'`과 `-H 'Origin: http://evil.example'`로 `/api/benefits/regions` 요청 |
| 저장소 문서(끝난 이슈의 플랜과 대체된 결정 문서 제외)에 공개 화면을 서버에서 렌더링한다는 설명이 남아 있지 않다 | 10, 11, 12 | `grep -rn "서버 렌더링\|서버에서 렌더링\|Next" AGENTS.md docs --exclude-dir=plans --exclude-dir=handoff`의 결과가 0026과 0024의 당시 기록뿐인지 확인 |
| CI `frontend` 체크가 새 구조의 검사를 실행하고 통과한다 | 1, 10 | PR의 `frontend-check`에서 lint·typecheck·build가 실행되고 `frontend`가 통과 |

## 검증 명령

- `frontend/`에서 `npm ci`, `npm run lint`, `npm run typecheck`, `VITE_API_BASE_URL=http://localhost:8080 npm run build`.
- 값 없이 `npm run build`를 실행해 실패하는지 확인한다.
- 정적 서버 확인: `docker compose -f docker-compose.local.yml up -d`, `backend/`에서 `SPRING_PROFILES_ACTIVE=local ./gradlew bootRun`, `frontend/`에서 빌드 후 `npx serve@14.2.6 -s dist -l 3000`. 위 표의 확인 방법을 따른다. `npm run dev`에서도 같은 흐름을 한 번 확인한다.
- `backend/`에서 `./gradlew test`.

## 결정 필요

없음
