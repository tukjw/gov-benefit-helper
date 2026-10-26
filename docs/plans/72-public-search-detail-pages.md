status: done

# 구현 계획 — #72 로그인 없이 혜택을 검색하고 상세를 확인하는 웹 화면 제공

브랜치: `feat/72-public-search-detail-pages` (develop `9924448` 기준)

## 목표 및 범위

- 목표: 로그인하지 않은 사용자가 홈 → 검색 → 상세 → 신청처까지 이동하는 공개 화면을 서버 렌더링으로 만든다(F1, `DEC-APP`). F2~F4가 같이 쓸 디자인 토큰, 공통 레이아웃, 공통 컴포넌트와 화면 설정(보기 방식) 저장 방식을 여기서 정한다.
- 포함:
  - 공통: 디자인 토큰, 글꼴, 본문 바로가기, 헤더·모바일 메뉴, 푸터(민간 서비스 안내 포함), 브레드크럼, 안내 박스, 빈 상태, 찾을 수 없음·오류 화면.
  - 홈(`/`): 검색창, 추천 검색어, 분야 바로가기.
  - 전체 혜택(`/benefits`): 키워드 검색, 분야·운영 기관 유형·운영 기관 지역 필터, 페이지 이동, 카드형·목록형 전환, 빈 상태.
  - 혜택 상세(`/benefits/[serviceId]`): 원문 섹션, 신청 사이드 박스, 문의처, 출처 수정 시각·수집 시각.
  - 결정 문서 0026과 `docs/design/README.md`의 F1 관련 문구 갱신.
- 제외:
  - 관심 저장 버튼, 상세의 조건 비교 결과 박스, 헤더의 조건 비교·관심 혜택 메뉴, 홈의 조건 비교 카드: #74. 갈 화면이 아직 없어 지금 넣으면 깨진 링크가 된다.
  - 헤더의 로그인 버튼과 로그인 상태 표시: #73.
  - 캐시: Next 서버 캐시(`use cache`)를 쓰지 않고 요청마다 렌더링한다. 백엔드 캐시는 별도 이슈로 다룬다(2026-10-26 사용자 결정).
  - KRDS 공식 패키지, 테스트 러너, 토스트(이 이슈에는 띄울 일이 없다).
  - 백엔드 변경. 이 이슈의 화면은 기존 API(#29, #30, #68)만 쓴다.

## 확인된 사실과 제약

- 프론트는 `app/layout.tsx`, `app/page.tsx`, `lib/config.ts`(`apiBaseUrl()`)만 있다. `next@16.4.2`, `cacheComponents: true`, Tailwind와 테스트 러너는 없다(#64).
- API(`backend/.../benefit/controller/BenefitController.java`):
  - `GET /api/benefits`: `keyword`, `field`(반복), `agencyType`(반복), `agencySidoCode`, `agencySigunguCode`, `page`(1부터), `size`(기본 20, 최대 100). 응답은 `totalCount`, `page`, `size`, `items`(`BenefitSummary`).
  - 400(`INVALID_INPUT`)이 나는 입력: `page < 1`이나 숫자가 아닌 값, 키워드 100자 초과, 단어 10개 초과, 없는 지역 코드나 시도 없는 시군구. 마지막 쪽을 넘은 `page`는 빈 `items`인 200이다.
  - `field`·`agencyType`은 검증하지 않는 원문 값이라, 없는 값이면 400이 아니라 0건이다.
  - `GET /api/benefits/regions`: `sidos[]`(`code`, `name`, `sigungus[]`). 세종·제주는 시군구가 빈 배열이다.
  - `GET /api/benefits/{serviceId}`: 없는 ID는 404(`NOT_FOUND`), 상세를 아직 못 받은 혜택은 `detail: null`인 200이다. 값이 없는 필드는 `null`이며 서버는 기본 문구로 채우지 않는다. '정보 없음'은 화면이 표시한다.
  - 오래된 정보는 서버가 판정해 `freshness.stale`과 `freshness.referenceAt`으로 준다(`DEC-FRESHNESS`). 화면은 이 값으로 경고를 띄우지 않는다(2026-10-26 사용자 결정, `REQ-DETAIL-01`·`DEC-FRESHNESS`와 이슈 완료 조건도 함께 고친다).
  - 온라인 신청 주소는 링크로 쓸 수 있게 정리한 `detail.onlineApplyUrl`과 원문 `detail.onlineApplyUrlText`가 따로 온다([결정 0011](../decisions/0011-online-apply-url-link.md)). `guideUrl`은 gov.kr 공식 안내 페이지이며 링크로 쓸 수 없으면 `null`이다.
  - 시각은 `+09:00` 오프셋을 붙인 ISO-8601 문자열이다([결정 0010](../decisions/0010-api-time-format.md)).
- 로컬 DB(2026-10-26, 10,950건)의 원문 값. 모든 혜택에 값이 있다.
  - 분야 10종: 생활안정, 농림축산어업, 보육·교육, 보건·의료, 임신·출산, 고용·창업, 문화·환경, 보호·돌봄, 행정·안전, 주거·자립.
  - 운영 기관 유형 7종: 시군구, 광역시도, 중앙행정기관, 공공기관, 지방출자_출연기관, 지방공기업, 교육청.
- 추천 검색어 후보의 이름 일치 건수(로컬 DB, `LIKE` 기준): 청년 월세 19, 출산 307, 장학금 325, 난임 118, 전세 79, 취업 76, 기초연금 3, 아동수당 4.
- Next 16 `cacheComponents`(`node_modules/next/dist/docs`):
  - `searchParams`·`params`나 캐시하지 않은 `fetch`를 읽는 컴포넌트는 `<Suspense>` 안에 둬야 한다. 아니면 개발 화면에 blocking-route 경고가 뜨고 정적 셸을 만들 수 없다.
  - 스트리밍이 시작된 뒤 `notFound()`를 부르면 찾을 수 없음 화면은 그려지지만 상태 코드는 200으로 남고, `noindex` 메타 태그가 들어간다. 완료 조건은 화면 표시만 요구하므로 404 상태 코드는 이 이슈에서 보장하지 않는다.
  - 서버 렌더링 결과를 첫 페인트 전에 고치는 방법으로 `<head>`의 인라인 스크립트와 `suppressHydrationWarning`을 안내한다(`02-guides/preventing-flash-before-hydration.md`).
- CI(`frontend-check`)의 `npm run build`에는 백엔드가 없다. 빌드할 때 API를 부르는 화면이 있으면 실패하므로, API 호출은 모두 요청 시점(`<Suspense>` 안)에 둔다.
- 글꼴 `pretendard-gov@1.3.9`(OFL-1.1)는 `dist/LICENSE.txt`를 포함한다. 가변 글꼴 한 파일은 5.4MB라 쓰지 않고, 120개로 나눈 동적 서브셋과 이를 `unicode-range`로 묶은 CSS(`dist/web/variable/pretendardvariable-gov-dynamic-subset.css`)를 쓴다.
- 아이콘 `lucide-react@1.61.0`은 ISC 라이선스다.
- 화면 수치와 토큰은 [`docs/design/handoff/README.md`](../design/handoff/README.md), 따를 것과 대체할 것은 [`docs/design/README.md`](../design/README.md)를 따른다.
- 백엔드 CORS·Origin 허용 목록은 바꾸지 않는다. 공개 화면은 Next 서버가 API를 부르므로 브라우저 교차 출처 요청이 없다.
- 저장소가 비공개라 GitHub 저장소 링크는 푸터 연락처로 쓸 수 없다.

## 설계 결정

1. **스타일, 글꼴, 아이콘, 화면 설정 저장 방식은 [결정 0026](../decisions/0026-frontend-styling-and-preferences.md)으로 올린다.** F2~F4도 따라야 하기 때문이다(2026-10-26 사용자 결정).
   - KRDS 공식 패키지는 쓰지 않고 토큰과 수치만 따른다. 민간 서비스가 써도 되는지 라이선스를 확인하지 못했고, 공식 사이트처럼 보이지 않게 한다는 방침과도 맞다.
   - 스타일은 CSS Modules와 `app/globals.css`의 CSS 변수로 한다. 크기는 rem으로 써서 브라우저 기본 글자 크기를 따른다. Tailwind는 #64의 보류 결정을 유지한다.
   - 시안의 글자 크기 버튼(보통·크게·아주 크게)과 최상단 민간 서비스 안내 띠는 두지 않고, 안내는 푸터에 둔다. 헤더 메뉴는 양 끝 정렬 대신 로고 바로 오른쪽에 붙인다. 1차 공개 메뉴가 '전체 혜택' 하나뿐이라서다(2026-10-26 사용자 결정, 이슈 완료 조건도 함께 고쳤다).
   - 글꼴은 `pretendard-gov` 패키지의 동적 서브셋 CSS를 루트 레이아웃에서 불러와 빌드 결과에 함께 내보낸다. CDN에 의존하지 않고, 브라우저는 화면에 쓰인 글자의 서브셋만 받는다. `next/font/local`은 서브셋별 `unicode-range`를 지정할 수 없어 쓰지 않는다.
   - 아이콘은 `lucide-react`를 쓴다. 쓰는 아이콘만 번들에 들어간다.
   - 카드형·목록형은 `localStorage`에 저장하고, `<head>`의 인라인 스크립트가 첫 페인트 전에 `<html>`의 `data-view` 속성으로 반영한다. 서버는 이 값을 읽지 않는다. 쿠키를 서버에서 읽으면 모든 화면이 요청마다 렌더링되고, `<html>` 속성은 `<Suspense>`로 감쌀 수 없어 `cacheComponents`와 맞지 않는다. 이후 캐시를 붙일 때도 막히지 않는다.
2. **화면은 캐시하지 않고 요청마다 서버 렌더링한다.** 데이터를 읽는 부분만 `<Suspense>`로 감싸 헤더·푸터·검색창은 정적 셸로 먼저 보낸다.
3. **URL 파라미터는 API 이름을 그대로 쓴다.** `keyword`, `field`(반복), `agencyType`(반복), `agencySidoCode`, `agencySigunguCode`, `page`. 쪽 크기는 20으로 고정하고 URL에 두지 않는다. 화면 파라미터와 API 파라미터가 같아 변환이 하나로 줄고, 링크를 공유해도 같은 결과가 나온다.
4. **잘못된 입력은 오류 화면 대신 화면에서 처리한다.**
   - `page`가 정수가 아니거나 1보다 작으면 API를 부르기 전에 1로 바꾼다. 리다이렉트하지 않는다(스트리밍 중 리다이렉트는 상태 코드를 바꾸지 못한다).
   - 그 밖의 400(키워드 길이·단어 수, 지역 코드)은 결과 자리에 "검색 조건을 확인해 주세요" 안내 박스와 필터 초기화 링크를 보여준다. 검색창은 `maxLength=100`으로 막는다.
   - 결과가 0건이면 "검색 결과가 없습니다", 마지막 쪽을 넘었으면(`items`가 비었고 `totalCount > 0`) "이 쪽에는 결과가 없습니다"와 첫 쪽으로 가기 링크를 보여준다. 두 경우 모두 필터 초기화를 함께 둔다.
   - 5xx나 연결 실패는 `app/error.tsx`의 다시 시도 화면으로 보낸다.
5. **검색과 필터는 GET 폼으로 만든다.** 홈 검색창은 `/benefits`로, 전체 혜택의 검색창과 필터는 현재 화면으로 제출한다. JS 없이도 동작하고 키보드로 쓸 수 있다. 결과 화면에서 키워드를 바꾸면 필터는 유지하고 `page`만 1로 돌린다. 필터는 칩 버튼 대신 체크박스로 만든다. 폼 값으로 그대로 제출되기 때문이다. 시도를 고르면 시군구 선택지를 바꾸는 부분만 클라이언트 컴포넌트로 만든다.
6. **분야·운영 기관 유형 선택지는 프론트 상수로 둔다.** 분야 10종, 기관 유형 7종으로 거의 바뀌지 않고, 선택지 API를 만들면 이 이슈가 백엔드로 번진다. 값은 원문 그대로 보내고, 화면 라벨만 따로 둔다(예: `지방출자_출연기관` → "지방 출자·출연기관"). 한계: 공공 데이터에 새 값이 생기면 상수를 고쳐야 한다. 그 전까지 새 값의 혜택은 필터로 고를 수 없을 뿐 검색에는 나온다(2026-10-26 사용자 결정).
7. **"많이 찾는 검색어"는 "추천 검색어"라는 이름의 고정 목록으로 바꾼다.** 검색 기록을 모으지 않으므로 "많이 찾는"은 사실이 아니다. 실제 혜택 이름에서 골라 결과가 0건이 아닌지 확인한 8개를 둔다(2026-10-26 사용자 결정).
8. **카드형과 목록형은 하나의 마크업을 CSS로 바꿔 보여준다.** 시안에서 두 보기의 내용이 같고 배치만 다르기 때문이다. 전환 버튼의 선택 표시도 `html[data-view]`를 보는 CSS로 칠해, 하이드레이션 전에도 표시가 어긋나 보이지 않게 한다.
9. **운영 기관 지역이 거주 요건이 아님을 필터 이름으로 드러낸다.** 필터 제목을 "운영 기관 지역"으로 두고, 대상 지역·거주지처럼 읽히는 이름을 쓰지 않는다(`REQ-SEARCH-01`, [결정 0025](../decisions/0025-agency-region.md)). 설명 문구는 두지 않는다(2026-10-26 사용자 결정).
10. **시각은 서버 컴포넌트에서 `Asia/Seoul`로 고정해 표시한다.** 서버 컴포넌트는 브라우저에서 다시 렌더링하지 않으므로 서버와 브라우저의 시간대가 달라도 하이드레이션 불일치가 생기지 않는다.
11. **푸터의 운영자 연락처는 `contact@govbenefit.kr`이다.** 서비스 도메인의 운영용 주소라 개인 메일이 화면에 드러나지 않는다. 저장소가 비공개라 GitHub 링크는 쓸 수 없다(2026-10-26 사용자 결정).

## 단계별 구현 계획

1. 의존성과 전역 스타일
   - `npm install pretendard-gov@1.3.9 lucide-react`로 설치하고 `package-lock.json`을 함께 커밋한다.
   - `frontend/app/globals.css`: 시안 README '디자인 토큰'의 색·타이포그래피·간격·모서리·컨트롤 높이·그림자를 `:root`의 CSS 변수로 정의한다. 기본 리셋, 본문 글꼴(`Pretendard GOV Variable`과 대체 글꼴), 포커스 표시, `.visually-hidden`도 여기에 둔다.
   - `frontend/app/layout.tsx`: 글꼴 CSS와 `globals.css`를 불러오고, `<html lang="ko" suppressHydrationWarning>`로 바꾼다.
2. 화면 설정
   - `frontend/lib/preferences.ts`: 저장 키(`gbh:view`), 허용 값, `readPreference`·`writePreference`(접근이 막혀도 실패하지 않게 try/catch), 인라인 스크립트 문자열 `PREFERENCES_SCRIPT`.
   - `frontend/components/InlineScript.tsx`: Next 가이드의 방식대로 서버에서만 실행되는 `<script>`를 렌더링한다. 루트 레이아웃 `<head>`에 둔다.
   - `frontend/components/preferences/ViewToggle.tsx`(클라이언트): 카드형·목록형 버튼. 누르면 `data-view`와 저장값을 바꾼다.
3. 공통 레이아웃: `frontend/components/layout/`
   - `SkipLink.tsx`: 본문 바로가기(`#main`).
   - `SiteHeader.tsx`: 글자 로고(홈 링크)와 바로 오른쪽의 메뉴 "전체 혜택". 900px 미만에서는 `MobileMenu.tsx`(클라이언트, `aria-expanded`·`aria-controls`, Esc로 닫기)로 바꾼다.
   - `SiteFooter.tsx`: 서비스 이름과 민간 서비스 안내, 데이터 출처 링크, 문의 메일(`mailto:contact@govbenefit.kr`, 설계 11).
   - `Breadcrumb.tsx`: `nav aria-label="현재 위치"`와 `aria-current="page"`.
   - `frontend/app/layout.tsx`의 본문 순서: `SkipLink` → `SiteHeader` → `<main id="main">` → `SiteFooter`.
4. 공통 컴포넌트: `frontend/components/ui/`
   - `InfoBox.tsx`(정보·성공·경고·위험 색, 경고·위험은 `role="alert"`가 아니라 일반 안내로 둔다), `EmptyState.tsx`, `Badge.tsx`.
   - 찾을 수 없음·오류 화면의 틀은 `frontend/components/StatusPage.tsx`에 둔다. 사이드 박스는 상세의 신청 박스 한 곳에서만 쓰므로 따로 컴포넌트로 두지 않는다.
5. API 클라이언트: `frontend/lib/api/`
   - `types.ts`: `BenefitSummary`, `BenefitSearchResponse`, `BenefitDetailResponse`, `Freshness`, `ApplyPeriodType`, `RegionListResponse`, `ApiErrorResponse`. 백엔드 DTO의 필드를 그대로 옮긴다.
   - `client.ts`: `apiGet<T>(path, query)`. `apiBaseUrl()`로 주소를 만들고 `cache: 'no-store'`로 요청한다. 2xx가 아니면 본문의 `code`를 담은 `ApiError`를 던진다.
   - `benefits.ts`: `searchBenefits(params)`, `getBenefitDetail(serviceId)`(404면 `null`), `getRegions()`.
6. 검색 조건과 표시 규칙: `frontend/lib/benefits/`
   - `options.ts`: `FIELDS`(값, 라벨, lucide 아이콘), `AGENCY_TYPES`(값, 라벨), `RECOMMENDED_KEYWORDS`(설계 7).
   - `searchParams.ts`: `parseSearchParams(raw)`가 Next `searchParams`를 `{ keyword, fields, agencyTypes, agencySidoCode, agencySigunguCode, page }`로 정리한다(빈 값 제거, 잘못된 `page`는 1). `toSearchQuery(params, overrides)`는 페이지·초기화 링크용 쿼리 문자열을 만든다.
   - `display.ts`: `applyPeriodLabel(type, needsReview)`(상시, 조건부 마감, 신청 절차 없음, 기한 확인 필요, 원문 확인), `formatDateTime(iso)`(`Asia/Seoul`, `ko-KR`), `orNone(value)`('정보 없음').
7. 홈: `frontend/app/page.tsx`
   - 히어로와 검색 폼(`action="/benefits"`, `name="keyword"`, `maxLength=100`, 라벨 연결).
   - 추천 검색어 링크(`/benefits?keyword=…`), 분야 바로가기 링크(`/benefits?field=…`, 아이콘과 이름, 건수 없음).
   - API를 부르지 않으므로 정적 화면으로 빌드된다.
8. 전체 혜택: `frontend/app/benefits/page.tsx`
   - 페이지는 브레드크럼과 제목만 바로 그리고, `searchParams`를 읽는 `BenefitSearch`를 `<Suspense>`로 감싼다.
   - `frontend/app/benefits/_components/`
     - `SearchForm.tsx`: 현재 필터를 hidden input으로 유지하는 키워드 폼.
     - `FilterPanel.tsx`: 위에서부터 운영 기관 지역(설계 9), 분야·기관 유형 체크박스, 적용 버튼, 필터 초기화 링크(`/benefits`). 900px 미만에서는 `FilterToggle.tsx`(클라이언트, `aria-expanded`)로 접는다.
     - `RegionSelect.tsx`(클라이언트): 시도 `<select>`에 따라 시군구 `<select>`의 선택지를 바꾼다. 시군구가 없는 시도는 시군구 선택을 감춘다.
     - `ResultList.tsx`: "총 N건", `ViewToggle`, 결과 목록, 빈 상태와 400 안내(설계 4).
     - `BenefitCard.tsx`: 카드 전체가 상세 링크인 `<a>`. 분야 배지, 신청기한 배지, 이름, 요약, 기관·기관 유형, 신청기한 원문, 마지막 확인 시각. 이후 저장 버튼을 형제 요소로 붙일 수 있게 링크 밖에 자리를 둔다.
     - `Pagination.tsx`: `nav aria-label="페이지"`, 이전·다음과 현재 앞뒤 번호, `aria-current="page"`. 쪽 수는 `totalCount`로 계산한다.
9. 혜택 상세: `frontend/app/benefits/[serviceId]/page.tsx`
   - `params`를 읽는 `BenefitDetailView`를 `<Suspense>`로 감싼다. `getBenefitDetail`이 `null`이면 `notFound()`를 부른다.
   - 헤더: 브레드크럼(홈 › 전체 혜택 › 분야), 분야 배지, 신청기한 배지, 이름(h1), 기관·부서, 요약.
   - 본문 섹션: 지원 내용, 지원 대상, 선정 기준, 신청 방법(`detail.applyMethod`), 구비서류(구비서류, 공무원 확인 서류, 본인 확인 서류), 문의처 표(`detail.contact`, `phoneInquiry`, 접수기관). 원문은 `white-space: pre-line`으로 줄바꿈을 살리고, 값이 없으면 '정보 없음'이다.
   - `detail`이 `null`이면 상세 섹션 대신 "상세 정보를 아직 받지 못했어요" 안내와 공식 안내 페이지 링크를 보여준다.
   - 신청 사이드 박스: 신청기한(원문과 배지), 신청 방법(`applyMethodType`), 접수기관. `detail.onlineApplyUrl`이 있으면 "온라인 신청하기" 버튼(새 창, `rel="noopener noreferrer"`, 새 창 안내 문구). 없으면 버튼 대신 접수 방법과 "공식 안내 페이지" 링크(`guideUrl`). 둘 다 없으면 '정보 없음'이다.
   - 출처: 출처 수정 시각(`sourceModifiedAt`, 없으면 '정보 없음')과 수집 시각(`lastSeenAt`, "목록에서 마지막으로 확인한 시각")을 따로 표시하고, 공식 안내 페이지 링크를 둔다.
   - `frontend/app/benefits/[serviceId]/not-found.tsx`: "혜택을 찾을 수 없어요"와 전체 혜택 링크.
10. 앱 수준 화면
    - `frontend/app/not-found.tsx`: 없는 경로.
    - `frontend/app/error.tsx`(클라이언트): API 장애나 연결 실패. 다시 시도 버튼과 홈 링크.
    - `frontend/app/icon.svg`: 브라우저 탭 아이콘. 주 색 바탕에 lucide `search-check`를 흰 선으로 둔다(2026-10-26 사용자 결정). 정부 상징이나 '인증' 배지처럼 보이는 모양은 쓰지 않는다.
11. 문서
    - `docs/decisions/0026-frontend-styling-and-preferences.md`: 설계 결정 1. `_template.md`를 따르고 `docs/decisions/README.md` 목록에 추가한다.
    - `docs/design/README.md`: '채택'의 "KRDS 공식 컴포넌트를 쓸지는 F1에서 정한다"를 결정 0026 링크로 바꾸고, F1 홈의 "많이 찾는 검색어 칩"을 "추천 검색어 칩(고정 목록)"으로 바꾼다. 글자 크기 버튼을 '따르지 않음'으로 옮기고, 민간 서비스 안내 위치를 푸터로, 메뉴 위치를 로고 오른쪽으로 고친다(설계 1).

## 완료 조건 매핑

| 완료 조건 | 구현 단계 | 확인 방법 |
| --- | --- | --- |
| 로그인하지 않고 홈 → 검색 → 상세 → 신청처(온라인 신청 주소 또는 공식 안내 페이지)까지 이동할 수 있다. | 7, 8, 9 | 로그인 없이 홈 검색 → 결과 카드 → 상세의 신청 버튼 또는 공식 안내 링크까지 이동 |
| 키워드·필터·페이지가 URL에 반영되어, 새로고침하거나 링크를 공유해도 같은 결과가 나온다. | 5, 6, 8 (설계 3, 5) | 필터와 쪽을 바꾼 URL을 새 창에서 열어 같은 결과인지 확인. `curl`로 받은 HTML에 결과 이름이 들어 있는지 확인(서버 렌더링) |
| 운영 기관 지역 필터가 거주 요건이 아니라는 점이 화면에 드러난다. | 8 (설계 9) | 필터 이름 확인 |
| 결과가 없을 때와 마지막 쪽을 넘은 페이지에서는 빈 상태와 필터 초기화가 보인다. 잘못된 페이지 값은 오류 화면 대신 첫 페이지나 안내로 처리된다. | 6, 8 (설계 4) | `?keyword=없는검색어zz`, `?page=9999`, `?page=0`, `?page=abc`, `?agencySidoCode=0000000` |
| 상세에서 값이 없는 항목은 '정보 없음'으로 보이고, 온라인 신청 주소가 없으면 신청 버튼 대신 접수 방법과 안내 페이지 링크가 보인다. | 6, 9 | 로컬 DB에서 `null` 항목이 있는 혜택과 온라인 신청 주소가 없는 혜택을 골라 확인 |
| 존재하지 않는 혜택 ID는 찾을 수 없음 화면을 보여준다. | 9 | `/benefits/000000000000` |
| 공식 배너·정부 상징·태극기·품질인증 링크가 없고, 모든 화면 푸터에 민간 서비스 안내가 있다. | 3 (설계 1) | 홈·목록·상세·찾을 수 없음·오류 화면에서 확인 |
| 900px 미만에서는 모바일 헤더와 필터 토글로 바뀌고, 가로 스크롤이 생기지 않는다. | 3, 8 | 브라우저 너비 899px·360px·320px에서 확인 |
| 키보드만으로 검색·필터·상세 이동을 할 수 있다. | 3, 8 | Tab·Enter·Space만으로 홈 검색 → 필터 적용 → 상세 이동 |

## 검증 명령

- `frontend/`에서 `npm run lint`, `npm run typecheck`, `npm run build`.
- 로컬 확인: `docker compose -f docker-compose.local.yml up -d`, `backend/`에서 `SPRING_PROFILES_ACTIVE=local ./gradlew bootRun`, `frontend/`에서 `npm run dev` 후 위 표의 확인 방법을 따른다.
- 서버 렌더링 확인: `curl -s 'http://localhost:3000/benefits?keyword=출산' | grep -c '<결과 이름>'`.
- 추천 검색어 확인: 8개 각각 `GET /api/benefits?keyword=…`의 `totalCount`가 0보다 큰지 확인한다.
- 백엔드를 바꾸지 않으므로 `./gradlew test`는 CI에 맡긴다.

## 결정 필요

없음
