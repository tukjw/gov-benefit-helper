# 0028. 웹 화면 스타일과 화면 설정 저장(정적 SPA)

- 상태: 채택
- 날짜: 2026-10-26
- 출처: #76, [플랜](../plans/76-vite-static-frontend.md)
- 대체: [0026](0026-frontend-styling-and-preferences.md)

## 배경

[결정 0026](0026-frontend-styling-and-preferences.md)은 F1(#72)에서 스타일 방식, 글꼴, 아이콘, 화면 설정 저장 방식을 정했다. 0026의 배경과 근거는 공개 화면을 Next 서버가 렌더링한다는 전제(`cacheComponents`, 하이드레이션 불일치)를 두고, 적용 위치는 Next의 `app/` 경로다.

#76에서 프론트를 React + Vite 정적 SPA로 바꿨다([결정 0027](0027-frontend-static-spa.md)). 서버 렌더링이 없어져 근거가 바뀌고, 화면 설정 스크립트를 넣는 방법과 파일 위치가 바뀌었다. 결정 내용은 그대로 이어받는다.

## 결정

KRDS 공식 패키지는 쓰지 않고 디자인 토큰과 수치만 따른다. 스타일은 CSS Modules와 `src/globals.css`의 CSS 변수로 쓰고 크기는 rem으로 둔다. 글꼴은 `pretendard-gov` 패키지의 동적 서브셋 CSS를, 아이콘은 `lucide-react`를 쓴다. 화면 설정은 `localStorage`에 저장하고, `index.html`의 `<head>`에 넣은 스크립트가 첫 페인트 전에 `<html>`의 `data-view` 속성으로 반영한다.

- 크기는 rem으로 써서 브라우저 기본 글자 크기를 키우면 화면 전체가 함께 커지게 한다. 컴포넌트 크기를 px로 고정하지 않는다. 시안의 글자 크기 버튼은 두지 않는다.
- 화면 설정에 따라 달라지는 모양(선택 표시 포함)은 `html[data-*]` 선택자를 쓰는 CSS로 칠한다. 같은 마크업을 CSS로 바꿔 보여준다.
- 첫 페인트 전 스크립트는 `vite.config.ts`의 플러그인이 `src/lib/preferences.ts`의 `PREFERENCES_SCRIPT`를 `index.html`에 넣는다. 저장 키와 허용 값은 `lib/preferences.ts` 한 곳에 둔다.
- 저장소 접근이 막혀도 기본값으로 동작한다.

## 근거

- KRDS 자료에는 행정안전부 저작권 표기가 있지만, 민간 서비스가 써도 되는지 밝힌 라이선스를 확인하지 못했다(2026-10-26 검색, 0026). 이 서비스는 공식 누리집으로 보이면 안 되므로([디자인 채택 범위](../design/README.md#공식-정부-사이트-요소)) 공식 컴포넌트를 쓸 이유도 약하다.
- CSS Modules는 Vite에 내장되어 도구가 늘지 않는다. Tailwind는 #64에서 미룬 결정을 유지한다.
- `pretendard-gov@1.3.9`는 OFL-1.1이다. 가변 글꼴 한 파일은 5.4MB라, 120개로 나눈 서브셋을 `unicode-range`로 묶은 CSS(`dist/web/variable/pretendardvariable-gov-dynamic-subset.css`)를 불러와 화면에 쓰인 글자의 파일만 받게 했다. Vite가 서브셋 파일을 빌드 결과에 함께 내보내므로 CDN 장애에 영향받지 않는다.
- `lucide-react`는 ISC 라이선스이고, 쓰는 아이콘만 번들에 들어간다.
- `<head>`의 동기 스크립트는 `<body>`를 그리기 전에 실행되어 깜빡임 없이 반영된다. 서버가 그린 HTML이 없으므로 0026의 하이드레이션 불일치 문제(`suppressHydrationWarning`, `aria-pressed` 지연)도 없다. 스크립트를 `index.html`에 직접 쓰면 저장 키가 두 곳에 생기므로 빌드 때 넣는다.

## 적용 위치

- `frontend/src/globals.css`: 디자인 토큰
- `frontend/src/main.tsx`: 글꼴 CSS와 `globals.css`
- `frontend/vite.config.ts`: 화면 설정 스크립트 주입 플러그인
- `frontend/src/lib/preferences.ts`: 저장 키, 허용 값, 첫 페인트 전 스크립트
- `frontend/src/components/preferences/`: 보기 방식 버튼과 `usePreference`
- `frontend/package.json`: `pretendard-gov`, `lucide-react`
- `frontend/public/icon.svg`: 브라우저 탭 아이콘(lucide `search-check`, 출처와 라이선스 주석 포함)

## 영향

- 새 화면은 CSS Modules와 토큰 변수로 스타일을 쓰고, 브라우저 글자 크기 설정이 적용되도록 rem을 쓴다.
- 브라우저에만 저장하는 화면 설정을 더할 때는 `lib/preferences.ts`에 키와 허용 값을 추가하고 `<html>` 속성으로 반영한다.
- KRDS 공식 패키지나 다른 스타일 도구를 도입하려면 이 결정을 대체한다.
