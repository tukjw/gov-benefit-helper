# 0026. 웹 화면 스타일과 화면 설정 저장

- 상태: 채택
- 날짜: 2026-10-26
- 출처: #72, [플랜](../plans/72-public-search-detail-pages.md)

## 배경

F1(#72)에서 첫 화면을 만들면서 F2~F4가 함께 쓸 스타일 방식, 글꼴, 아이콘을 정해야 했다. [디자인 채택 범위](../design/README.md)는 KRDS 공식 컴포넌트를 쓸지를 F1에서 정하도록 남겨 두었다. 결과 보기 방식(카드형·목록형)은 화면을 다시 열어도 유지되어야 한다.

공개 화면은 서버 렌더링이고(`DEC-APP`), 프론트는 `cacheComponents`를 켠 Next.js 16이다(#64). 이 설정에서는 쿠키처럼 요청마다 달라지는 값을 `<Suspense>` 밖에서 읽을 수 없고, `<html>` 속성은 `<Suspense>`로 감쌀 수 없다.

## 결정

KRDS 공식 패키지는 쓰지 않고 디자인 토큰과 수치만 따른다. 스타일은 CSS Modules와 `app/globals.css`의 CSS 변수로 쓰고 크기는 rem으로 둔다. 글꼴은 `pretendard-gov` 패키지의 동적 서브셋 CSS를, 아이콘은 `lucide-react`를 쓴다. 화면 설정은 `localStorage`에 저장하고 `<head>`의 인라인 스크립트가 첫 페인트 전에 `<html>`의 `data-view` 속성으로 반영한다. 서버는 화면 설정을 읽지 않는다.

- 크기는 rem으로 써서 브라우저 기본 글자 크기를 키우면 화면 전체가 함께 커지게 한다. 컴포넌트 크기를 px로 고정하지 않는다. 시안의 글자 크기 버튼은 두지 않는다.
- 화면 설정에 따라 달라지는 모양(선택 표시 포함)은 `html[data-*]` 선택자를 쓰는 CSS로 칠한다. 같은 마크업을 CSS로 바꿔 보여주고, 설정마다 다른 마크업을 서버에서 그리지 않는다.
- 저장소 접근이 막혀도 기본값으로 동작한다.

## 근거

- KRDS 자료에는 행정안전부 저작권 표기가 있지만, 민간 서비스가 써도 되는지 밝힌 라이선스를 확인하지 못했다(2026-10-26 검색). 이 서비스는 공식 누리집으로 보이면 안 되므로([디자인 채택 범위](../design/README.md#공식-정부-사이트-요소)) 공식 컴포넌트를 쓸 이유도 약하다.
- CSS Modules는 Next에 내장되어 도구가 늘지 않는다. Tailwind는 #64에서 미룬 결정을 유지했다.
- `pretendard-gov@1.3.9`는 OFL-1.1이다. 가변 글꼴 한 파일은 5.4MB라, 120개로 나눈 서브셋을 `unicode-range`로 묶은 CSS(`dist/web/variable/pretendardvariable-gov-dynamic-subset.css`)를 불러와 화면에 쓰인 글자의 파일만 받게 했다. `next/font/local`은 서브셋별 `unicode-range`를 지정할 수 없어 제외했다. CDN 대신 빌드 결과에 함께 내보내 외부 장애에 영향받지 않는다.
- `lucide-react`는 ISC 라이선스이고, 쓰는 아이콘만 번들에 들어간다. KRDS 아이콘 세트는 위와 같은 라이선스 문제로 제외했다.
- 화면 설정을 쿠키로 서버에서 읽으면 모든 화면이 요청마다 렌더링되고, 이후 공개 화면을 캐시할 수 없다. 인라인 스크립트는 HTML을 파싱하는 중에 실행되어 깜빡임 없이 반영된다(Next 16 문서 `02-guides/preventing-flash-before-hydration.md`). 2026-10-26에 목록형을 저장한 뒤 다시 열어 속성과 선택 표시가 유지되고 하이드레이션 오류가 없는 것을 브라우저로 확인했다.
- 대신 버튼의 `aria-pressed`는 하이드레이션 직후에 실제 값으로 바뀐다. 보이는 선택 표시는 CSS가 먼저 맞춘다.

## 적용 위치

- `frontend/app/globals.css`: 디자인 토큰
- `frontend/app/layout.tsx`: 글꼴 CSS, 인라인 스크립트, `<html suppressHydrationWarning>`
- `frontend/lib/preferences.ts`: 저장 키, 허용 값, 인라인 스크립트
- `frontend/components/InlineScript.tsx`, `frontend/components/preferences/`: 보기 방식 버튼과 `usePreference`
- `frontend/package.json`: `pretendard-gov`, `lucide-react`
- `frontend/app/icon.svg`: 브라우저 탭 아이콘(lucide `search-check`, 출처와 라이선스 주석 포함)

## 영향

- 새 화면은 CSS Modules와 토큰 변수로 스타일을 쓰고, 브라우저 글자 크기 설정이 적용되도록 rem을 쓴다.
- 브라우저에만 저장하는 화면 설정을 더할 때는 `lib/preferences.ts`에 키와 허용 값을 추가하고 `<html>` 속성으로 반영한다. 서버 렌더링 결과를 바꾸는 값이 필요하면 이 결정을 다시 검토한다.
- KRDS 공식 패키지나 다른 스타일 도구를 도입하려면 이 결정을 대체한다.
