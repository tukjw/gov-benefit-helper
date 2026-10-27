status: done

# 구현 계획 — #74 프로필 입력·관심 혜택·조건 비교 결과를 보는 웹 화면 제공

브랜치: `feat/74-personal-pages` (develop `c5a5a8f` 기준)

## 목표 및 범위

- 목표: 프로필·관심 혜택·조건 비교 API(#49, #56, #58, #59, #67, #68)를 쓰는 화면(F3)을 만든다. 비회원은 프로필 값을 입력해 조건 비교를 보고, 로그인 사용자는 프로필과 관심 혜택을 저장한다.
- 포함:
  - 프론트: 프로필 입력·수정 화면, 조건 비교 목록, 상세의 조건 비교 결과 박스, 관심 저장 토글(검색·조건 비교·관심 목록 카드와 상세), 관심 목록 화면, 헤더 메뉴(조건 비교 / 전체 혜택 / 관심 혜택), 홈의 조건 비교 카드.
  - 백엔드: 화면에 보인 혜택들의 저장 여부를 한 번에 조회하는 API와 테스트(설계 1). 조건 비교 목록 응답의 결과별 건수(`matchCount`, `needsCheckCount`)와 테스트(설계 11).
  - 문서: 비회원 프로필 값 보관 결정 문서(0031)와 결정 목록, `docs/design/README.md` F3의 프로필 구성.
- 제외:
  - 로그인할 때 비회원으로 입력한 값을 프로필로 옮기기. 완료 조건에 없다. 로그인하면 비회원 입력값은 지우고 저장된 프로필을 쓴다(설계 2).
  - 비로그인으로 저장을 누른 뒤 로그인하면 그 혜택을 자동으로 저장하기. 완료 조건은 원래 화면으로 돌아오는 것까지다.
  - 검색 화면의 운영 기관 지역 필터를 거주지로 미리 채우기(`REQ-PROFILE-01`의 "웹 화면에서 다룬다"). 이 이슈의 작업 내용에 없다. 제안에 적는다.
  - 탈퇴 링크를 개인 화면으로 옮기기(#73 플랜 9). 헤더의 이메일 표시에 그대로 둔다.
  - 조건 비교·프로필·북마크 API의 판정·검증 동작 변경. 새 조회 API와 목록 응답의 건수 필드만 더한다.
  - 프론트 테스트 러너 도입(#73과 같다). 화면 흐름은 로컬 백엔드로 직접 확인한다.

## 확인된 사실과 제약

- 프로필 API(`kr.govbenefit.profile.controller`):
  - `GET /api/me/profile` → `ProfileResponse{birthDate, age, gender, sido{code,name}, sigungu, lifeAttributes, householdAttributes, updatedAt}`. 저장한 적이 없으면 모두 `null`이다.
  - `PUT /api/me/profile` 본문 `ProfileRequest{birthDate, gender, sidoCode, sigunguCode, lifeAttributes, householdAttributes}`. 빠진 필드와 `null`은 미입력이다. 잘못된 값은 400 `INVALID_INPUT`과 `errors[].field`(본문 필드 이름).
  - `GET /api/profile/options`(로그인 불필요) → `{sidos[{code,name,sigungus[]}], lifeAttributes[{value,label}], householdAttributes[{value,label}]}`. 속성은 `NONE`(해당 없음)이 마지막이다.
  - 속성 값은 `null`이면 미입력, `["NONE"]`이면 해당 없음이다. 빈 배열, 중복, `NONE`과 다른 값의 조합은 거절한다(결정 0022). 성별은 `MALE`·`FEMALE`이고 '해당 없음'이 없다.
- 조건 비교 API(`kr.govbenefit.match.controller`):
  - 회원: `GET /api/me/matches?regionScope&page&size`, `GET /api/me/matches/{serviceId}`. 저장된 프로필로 비교한다.
  - 비회원: `POST /api/matches?regionScope&page&size`, `POST /api/matches/{serviceId}`. 본문은 `ProfileRequest`이고 저장하지 않는다. 토큰을 보내도 본문으로만 비교한다. 본문을 먼저 검사해 잘못된 값이면 400 `INVALID_INPUT`(프로필 저장과 같은 형식)이다.
  - 목록 `MatchListResponse{totalCount, regionScope, page, size, judgedOn, notices, uncomparedConditions, items[{benefit: BenefitSummary, result, items{personalTarget, age, gender, lifeAttributes, householdAttributes, agencyRegion: {status, reason}}}]}`. `MATCH` → `NEEDS_CHECK`, 이름순이고 `MISMATCH`는 목록에 없다. `regionScope`를 보내지 않으면 거주지가 있을 때 `MY_REGION`, 없을 때 `ALL`이고, 응답의 `regionScope`가 실제로 적용한 값이다.
  - 상세 `MatchResponse{serviceId, name, result, judgedOn, notices, uncomparedConditions, items, source}`. 항목마다 비교한 값(나이 코드와 프로필 나이, 기관 지역과 프로필 지역 등)이 있고 `source`에 원문과 공식 링크가 있다. `MISMATCH`이면 `notices`가 `NOT_FINAL`뿐이다.
  - 목록 항목의 `BenefitSummary`에는 공식 안내 링크가 없다. 링크는 상세(`MatchResponse.source`, `BenefitDetailResponse.guideUrl`)에만 있다.
  - 값: `ItemStatus` `MET`·`NEEDS_CHECK`·`NOT_MET`, `MatchReason` 14종(`PROFILE_MISSING`, `CODE_EXCLUDES`, `AGE_ON_BOUNDARY`, `REGION_EXCLUDES`, `AGENCY_REGION_UNKNOWN` 등), `MatchNotice` `CODE_BASED`·`GROUP_RULE_INTERPRETED`·`AGENCY_REGION_BASED`·`NOT_FINAL`, `UncomparedCondition`(소득, 거주 요건 등).
- 북마크 API(`kr.govbenefit.bookmark`):
  - `PUT /api/me/bookmarks/{serviceId}` 204(이미 있으면 저장 시각만 바뀌어 목록 맨 위로 온다, 없는 ID는 404), `DELETE` 204(없어도 204), `GET /api/me/bookmarks?page&size` 마지막 저장 순. 저장·해제 모두 멱등이다.
  - 검색·상세·조건 비교 응답에는 저장 여부가 없다. 공개 API는 토큰을 읽지 않는다(결정 0018). 북마크 목록 쪽 크기는 최대 100이다.
- 프론트:
  - 화면 데이터는 `loader`, 값을 바꾸는 요청은 `<Form>`·`action`(결정 0029). 로그인이 필요한 API는 `authFetch`, 경로는 `memberOnly`(결정 0030). 공개 화면의 `loader`는 `ensureSession()`을 기다리지 않는다.
  - `memberOnly`는 `request.url`을 `returnTo`로 쓴다. `useFetcher`로 보낸 `action`의 `request.url`은 화면 주소가 아니라 액션 경로다.
  - fetcher의 `action`이 끝나면 React Router가 화면의 `loader`를 모두 다시 부른다(`shouldRevalidate`로 끌 수 있다). 앞 제출이 끝나기 전에 같은 fetcher로 다시 제출하면 앞 요청은 브라우저에서 취소되지만 서버에는 이미 닿았을 수 있다.
  - `BenefitCard.tsx`에 "관심 저장 버튼(#74)은 링크 안이 아니라 형제 요소로 붙인다", `NavLinks.tsx`·`HomePage.tsx`에 "#74에서 붙인다" 주석이 있다.
  - `pages/account/formErrors.ts`에 `fieldErrorsOf`(INVALID_INPUT → 항목별 오류)와 `formText`가 있다. `components/form/TextField`·`FormError`, `components/ui/Badge`(tone)·`InfoBox`·`EmptyState`, `lib/toast.ts`가 있다.
  - 브라우저 저장소를 쓰는 곳은 `lib/preferences.ts`(localStorage)뿐이다. 결정 0030은 액세스 토큰을 `sessionStorage`에 쓰지 않는다고만 정했다.
  - 현재 `frontend/src`에 "신청 가능" 문구는 없다.
- 디자인(`docs/design/README.md` F3): 프로필은 시안 2의 폼 카드·칩 버튼·라디오 카드를 따르고, 단계로 나눌지 한 화면에 둘지는 F3에서 정한다. "입력한 정보는 저장되지 않습니다"는 비회원에게만 보인다. 결과 박스와 안내 문구 예시, 관심 혜택(별 버튼으로 바로 해제, 비로그인은 목록 대신 로그인 안내)이 정해져 있다.
- 결정 목록의 `채택` 결정 중 따를 것: 0009, 0018, 0021, 0022, 0023, 0025, 0028(CSS Modules, `lucide-react`), 0029, 0030.

## 설계 결정

1. **목록 카드의 저장 여부는 새 조회 API로 받는다(2026-10-27 사용자 결정).** `GET /api/me/bookmarks/saved?serviceId=a&serviceId=b` → `{ "serviceIds": [...] }`. 받은 ID 중 저장된 것만 요청 순서로 돌려준다. 없는 서비스 ID는 무시한다. `serviceId`는 1~100개(쪽 크기 상한과 같다)이고 벗어나면 400 `INVALID_INPUT`이다.
   - 대안: 공개 검색·상세 응답에 `bookmarked`를 넣는다. 공개 API가 토큰을 읽게 되어 인증 경계(결정 0018)가 바뀐다. 북마크 목록 전체를 받아 둔다. 저장이 100건을 넘으면 여러 번 받아야 한다.
2. **비회원 입력값은 `sessionStorage`에 둔다(2026-10-27 사용자 결정).** 키 `gbh:guest-profile`에 `ProfileRequest` 모양의 JSON으로 둔다. 같은 탭에서는 새로고침·이동해도 남고 탭을 닫으면 지워진다. 서버에는 조건 비교 요청 본문으로만 보낸다. 로그인에 성공하면 지운다. 로그인 사용자는 저장된 프로필만 쓰고 이 값을 읽지 않는다. 읽기 실패(저장소 차단, JSON 손상, 모양 불일치)는 입력하지 않은 것으로 본다.
   - 이 이슈 뒤의 화면도 따라야 하므로 결정 문서 [0031](../decisions/0031-guest-profile-storage.md)로 올린다. 개인정보(생년월일)를 브라우저에 두는 유일한 곳이라는 점과 비회원 안내 문구를 함께 적는다.
   - 대안: 모듈 메모리. 새로고침하면 사라져 상세에서 새로고침한 비회원이 다시 입력해야 한다. `localStorage`. 공용 컴퓨터에 생년월일이 남는다.
3. **프로필은 한 화면의 폼 카드 묶음으로 둔다(2026-10-27 사용자 결정).** 카드는 기본 정보(생년월일·성별), 거주지(시도·시군구), 생애·직업·건강, 가구 네 개이고 맨 아래에 제출 버튼 하나를 둔다. 시안의 단계 표시와 이전·다음 버튼은 따르지 않는다.
   - 근거: 모든 항목이 선택이라 단계를 나눠도 막을 단계가 없다. 로그인 사용자의 수정은 대개 한 항목이라 단계를 다 넘기는 것이 번거롭다. 서버의 항목별 오류(`errors[].field`)를 한 화면에서 해당 칸에 바로 붙일 수 있다. 단계로 나누면 마지막 단계에서 받은 앞 단계의 오류로 되돌아가는 처리와 단계 상태가 더 필요하다.
   - 대안: 시안처럼 단계로 나눈다. 처음 입력하는 비회원에게는 덜 부담스럽지만 위 처리가 늘어난다.
   - `docs/design/README.md` F3 프로필의 "따름"에서 단계 표시·이전·다음 버튼을 빼고 "한 화면에 둔다(#74)"로 고친다.
4. **경로는 `/match`, `/profile`, `/bookmarks`다(2026-10-27 사용자 결정).** 계정 화면(`/account/*`, #73)과 겹치지 않는다.
   - `/profile`: 회원과 비회원이 같은 폼을 쓴다. 회원은 저장된 값을 채워 보여주고 제출하면 저장한다. 비회원은 탭에 둔 값을 채우고 제출하면 탭에 둔다. 둘 다 성공하면 `/match`로 간다.
   - `/match`: 조건 비교 목록. 지역 범위와 쪽은 쿼리(`?scope=MY_REGION|ALL&page=`)다. 비회원이 입력한 값이 없으면 `/profile`로 보낸다. 회원은 저장한 적이 없어도(`updatedAt == null`) `/profile`로 보낸다.
   - `/bookmarks`: 관심 목록. 비회원이면 `memberOnly`로 보내지 않고 목록 자리에 로그인 안내를 그린다(디자인 F3 "비로그인은 목록 대신 로그인 안내").
5. **비회원 입력의 항목별 오류는 조건 비교 API로 검사한다.** `/profile`의 비회원 `action`은 `POST /api/matches?size=1`에 값을 보내 400이면 항목별 오류를 돌려주고, 200이면 탭에 두고 `/match`로 보낸다. 검증 기준이 저장과 같으므로(`REQ-PROFILE-01`) 프론트에 규칙을 다시 만들지 않는다. 저장해 둔 값이 나중에 400이 되면(지역 코드 변경 등) `/match`는 `/profile`로 보내 다시 제출하게 한다.
6. **관심 저장은 fetcher `action`으로 보내고, 실제 요청은 혜택마다 차례로 보내는 모듈 상태가 맡는다.** 결정 0029(값을 바꾸는 요청은 `action`)를 따르면서 연타에도 최종 상태가 맞게 하려는 것이다.
   - `src/lib/bookmarks/bookmarkStore.ts`가 혜택별 `{ saved, pending }`을 들고 화면은 `useSyncExternalStore`로 구독한다. 같은 혜택이 여러 곳에 보여도 같은 값이다.
   - 버튼을 누르면 화면 값을 바로 바꾸고(낙관적 표시) 원하는 상태만 기록한다. 혜택마다 보내는 중인 요청은 하나뿐이고, 응답이 오면 원하는 상태와 서버에 보낸 상태를 비교해 다르면 한 번 더 보낸다. 연타해도 마지막 의도가 마지막 요청이 된다. PUT·DELETE가 멱등이라 같은 값을 다시 보내도 된다.
   - 실패하면 서버에 반영된 마지막 상태로 되돌리고 토스트를 띄운다. 재발급까지 실패하면(`AuthRequiredError`) 로그인 화면으로 보낸다.
   - 저장 토글이 검색·조건 비교 목록을 다시 받지 않도록 그 경로들의 `shouldRevalidate`에서 북마크 액션을 거른다(`skipBookmarkRevalidation`).
   - 비회원이 누르면 액션을 보내지 않고 `loginPath(현재 경로+쿼리)`로 보낸다. `memberOnly`가 쓰는 `request.url`이 액션 경로이기 때문이다.
   - 대안: 컴포넌트 이벤트 처리기에서 `authFetch`를 바로 부른다. 코드는 줄지만 결정 0029의 "값을 바꾸는 요청은 action"과 어긋나 결정 문서를 대체해야 한다.
7. **저장 여부는 화면을 먼저 그린 뒤 채운다.** 검색·조건 비교 목록과 상세 `loader`는 저장 여부를 기다리지 않고 `loadBookmarks(ids)`로 조회를 시작만 한다. 결과는 저장 상태 모듈에 채워지고 별 버튼이 구독한다. 받기 전에는 회원의 별 버튼을 누를 수 없다. 공개 화면 `loader`가 `ensureSession()`을 기다리지 않는다는 결정 0030을 지킨다. 비회원이면 조회하지 않고 빈 별을 그린다. 상세의 조건 비교 결과는 `Promise`로 넘기고 화면이 `<Suspense>`·`<Await>`로 받는다(구현 중 바꿨다. 저장 여부는 여러 화면이 같은 값을 써야 해서 `Await` 대신 모듈 상태로 받는다).
8. **관심 목록에서 해제한 카드는 그 자리에 빈 별로 남긴다.** 바로 다시 저장할 수 있게 하고 카드가 사라져 목록이 밀리지 않게 한다. 다른 화면에 갔다 오면 목록에서 빠진다.
9. **상세의 조건 비교 결과 박스는 항상 보인다.** 회원은 `GET /api/me/matches/{id}`, 비회원은 탭의 값(없으면 빈 본문)으로 `POST /api/matches/{id}`를 부른다. 빈 본문이어도 개인 대상이 아닌 혜택은 `MISMATCH`라 완료 조건 7을 입력 없이 확인할 수 있다. 입력이 없는 비회원에게는 결과 위에 "조건을 입력하면 더 정확히 비교해요"와 `/profile` 링크를 둔다. 확인 필요 결과의 공식 안내 링크는 이 박스에 둔다(목록 항목에는 링크가 없다). 목록 카드는 확인할 조건의 이름만 보여주고 상세로 연결한다(설계 11).
10. **화면 문구는 응답 코드로 고르는 표 하나에 모은다.** `src/lib/match/labels.ts`에 결과·항목 상태·이유·안내·비교하지 않은 조건의 문구를 둔다. '지원조건 코드' 같은 내부 용어와 '신청 가능'을 쓰지 않는다. 해당에는 `NOT_FINAL` 안내("공개된 대상 조건과 입력한 정보를 비교한 결과예요. 실제 지원 여부는 담당 기관 심사로 정해져요.")를 붙인다. 조건 비교 목록에서는 이 안내를 목록 위 "결과를 보는 방법"과 해당 묶음 제목 아래에 두고 카드에는 두지 않는다. 카드마다 같은 문구가 반복돼 목록이 무거웠다(2026-10-27 사용자 결정). 상세의 결과 박스에는 배지와 함께 둔다. 지역 항목에는 `AGENCY_REGION_BASED` 안내("거주 요건이 아니라 운영 기관이 있는 지역으로 비교했어요.")를 붙인다.

11. **조건 비교 목록은 결과별 건수를 보여주고 결과별로 묶는다(2026-10-27 사용자 요청, 구현 중 추가).** 카드마다 결과 배지와 안내를 붙이니 목록이 무거웠다. 목록 응답에 `matchCount`·`needsCheckCount`를 더하고(`totalCount`는 둘의 합), 목록 위에 결과별 건수를 두어 누르면 그 결과가 시작하는 쪽으로 간다. 한 쪽 안에서는 해당·확인 필요 묶음마다 제목(배지와 건수)과 설명을 한 번만 두고, 카드에는 결과 배지를 두지 않는다. 확인 필요 카드에는 요약 아래·운영 기관 정보 위에 "⚠ 확인할 조건 나이, 가구"처럼 조건 이름만 한 줄로 붙인다. 이유는 대부분 같은 문구("입력한 값이 공개된 대상 조건에 없어요")라 카드마다 반복하지 않고 상세에서 본다. 시안 다섯 개(이유 목록 박스, 칩, 한 줄 경고, 항목별 행, 하단 태그)를 실제 데이터로 비교해 하단 태그로 정했다(2026-10-27 사용자 결정). 서버는 이미 결과별로 나눠 모으므로 추가 판정이 없다.
    - 대안: 결과 필터(해당만 보기) 파라미터. API에 조회 조건이 늘어 이번에는 건수와 쪽 이동만 둔다.

## 단계별 구현 계획

1. 백엔드 저장 여부 조회 (설계 1)
   - `bookmark/dto/BookmarkSavedRequest.java`: `@BindParam("serviceId") @NotEmpty @Size(max = BenefitSearchRequest.MAX_SIZE) List<String> serviceIds`. 메시지는 "1개 이상 100개 이하여야 합니다.".
   - `bookmark/dto/BookmarkSavedResponse.java`: `List<String> serviceIds`.
   - `BookmarkRepository.findSavedServiceIds(userId, serviceIds)`: `select b.serviceId from Bookmark k join Benefit b on b.id = k.benefitId where k.userId = :userId and b.serviceId in :serviceIds`.
   - `BookmarkService.savedServiceIds(userId, serviceIds)`: `requireAccount` 뒤 조회하고, 중복을 뺀 요청 순서로 거른다.
   - `BookmarkController.saved`: `@GetMapping("/saved")`. API 문서에 200·400·401과 "없는 서비스 ID는 무시한다"를 적는다. `PUT`·`DELETE /{serviceId}`와 메서드가 달라 경로가 겹치지 않는다.
   - `BookmarkApiTest`: `저장_여부는_저장한_혜택만_요청_순서로_돌려준다`, `저장_여부는_다른_사용자의_북마크를_보지_않는다`, `저장_여부는_없는_서비스_ID를_무시한다`, `저장_여부의_서비스_ID가_없거나_100개를_넘으면_400이다`, `저장_여부는_토큰이_없으면_401이다`.
   - `match/dto/MatchListResponse.java`에 `matchCount`·`needsCheckCount`, `MatchService.list`에서 결과별 수를 넘긴다. `MatchListApiTest`에 건수 확인을 더한다(설계 11).
2. API 클라이언트 (`frontend/src/lib/api/`)
   - `types.ts`: `ProfileRequest`, `ProfileResponse`, `ProfileOptionsResponse`, `MatchListResponse`, `MatchResponse`(항목·원문 포함), `BookmarkListResponse`, `BookmarkSavedResponse`와 enum 문자열 유니온.
   - `profile.ts`: `getProfileOptions()`(앱 안에서 한 번 받아 같은 `Promise`를 쓴다), `getMyProfile()`, `saveMyProfile(body)`.
   - `match.ts`: `listMyMatches({scope, page})`, `getMyMatch(id)`, `listGuestMatches(body, {scope, page, size})`, `getGuestMatch(id, body)`.
   - `bookmarks.ts`: `listBookmarks(page)`, `savedServiceIds(ids)`, `saveBookmark(id)`, `removeBookmark(id)`. 회원 API는 모두 `authFetch`다.
3. 비회원 입력값 (`frontend/src/lib/profile/guestProfile.ts`, 설계 2)
   - `readGuestProfile(): ProfileRequest | null`, `writeGuestProfile(value)`, `clearGuestProfile()`. 모든 접근을 `try/catch`로 감싸고 읽은 값의 모양을 확인한다.
   - `pages/account/login/loginRoute.ts`의 `loginAction`: `signIn` 뒤 `clearGuestProfile()`.
   - `src/lib/profile/profileForm.ts`: `FormData` → `ProfileRequest`(빈 칸은 `null`, 속성은 아무것도 고르지 않으면 `null`)와 `ProfileResponse` → 폼 기본값 변환.
4. 프로필 화면 (`src/pages/profile/`, 설계 3·4·5)
   - `profileRoute.ts`: `profileLoader`는 `ensureSession()` 뒤 옵션과 회원이면 `getMyProfile()`, 비회원이면 `readGuestProfile()`을 돌려준다. `profileAction`은 회원이면 `memberOnly` 안에서 `saveMyProfile`, 비회원이면 설계 5. 400은 `fieldErrorsOf`로 돌려주고 성공하면 `redirect("/match")`와 토스트("프로필을 저장했어요." / 회원만).
   - `ProfilePage.tsx`: 카드 네 개와 제출 버튼(회원 "저장하고 비교하기", 비회원 "조건 비교하기"). 비회원에게만 제출 버튼 아래 한 줄 "입력한 정보는 서버에 저장되지 않아요." 처음에는 제목 있는 안내 박스에 "이 탭을 닫으면 지워져요"까지 두었으나 과하다는 의견으로 줄였다(2026-10-27 사용자 결정, 결정 0031).
   - `components/AttributeChips.tsx`: 체크박스 칩 묶음. '해당 없음'을 고르면 다른 칩을 풀고, 다른 칩을 고르면 '해당 없음'을 푼다. 아무것도 고르지 않으면 칩 아래에 "선택하지 않음(입력 안 함)"을 보여 '해당 없음'과 구분한다.
   - 성별은 라디오 카드(남성·여성·선택 안 함), 시도·시군구는 `select`(선택 안 함 포함, 시도를 바꾸면 시군구를 비운다. 세종·제주는 시군구 칸을 숨긴다), 생년월일은 `type="date"`(`max`는 오늘).
   - 항목별 오류는 `TextField`와 같은 방식(`aria-invalid`, `aria-describedby`, `role="alert"`)으로 각 카드에 붙인다.
5. 조건 비교 목록 (`src/pages/match/`)
   - `matchRoute.ts`의 `matchLoader`: `ensureSession()` 뒤 회원이면 `getMyProfile()`·`listMyMatches`, 비회원이면 `readGuestProfile()`·`listGuestMatches`. 입력값이 없으면 `redirect("/profile")`(설계 4). 비회원 400이면 `redirect("/profile")`(설계 5). 저장 여부는 설계 7.
   - `MatchPage.tsx`: 입력 조건 요약 박스(값 요약, [조건 수정] → `/profile`, 비회원만 [처음부터 다시] → `matchAction`이 값을 지우고 `/profile`), 비회원 안내(요약 박스 안 한 줄), "조건 비교 결과 N건", 지역 범위 선택, 해석 규칙·비교하지 않은 조건 안내, 카드 목록, `Pagination` 재사용.
   - `components/RegionScopeToggle.tsx`: 내 지역·전체 지역. 응답의 `regionScope`로 현재 값을 표시한다. 거주지가 없으면 전체 지역만 보이고 "거주지를 입력하면 내 지역으로 볼 수 있어요"를 둔다. 곁에 "운영 기관이 있는 지역 기준이며 거주 요건이 아니에요"를 둔다.
   - `components/MatchCard.tsx`: 확인 필요면 `BenefitCard`의 요약 아래에 "확인할 조건"과 `NEEDS_CHECK` 항목 이름을 한 줄로 더한다. 결과 배지는 묶음 제목에 둔다(설계 11). 관심 버튼을 형제 요소로 둔다.
   - `MatchPage.tsx`의 `ResultCounts`와 결과별 묶음(설계 11).
   - `src/lib/match/labels.ts`(설계 10)와 `summarizeProfile(profile, options)`(요약 박스 문구).
6. 상세의 조건 비교 결과 박스 (설계 9)
   - `benefitDetailLoader.ts`: `{ benefit, match: Promise<DetailMatch> }`을 돌려주고 `loadBookmarks([serviceId])`를 시작한다. `match`는 `ensureSession()`을 기다린 뒤 부르고, 비교한 값의 이름을 쓰려고 입력 선택지도 함께 담는다.
   - `pages/benefits/detail/components/MatchResultBox.tsx`: 결과 배지와 안내, 항목별 표(항목, 결과, 이유, 비교한 값), 운영 기관 지역 항목의 거주 요건 아님 안내, 비교하지 않은 조건, 해석 규칙(`GROUP_RULE_INTERPRETED`), 원문·공식 안내 링크(`source`). `MISMATCH`면 "개인이 아닌 기관·사업자 대상 혜택이에요."와 개인 대상 항목의 이유만 보인다. 받지 못하면 박스 안에 "조건 비교 결과를 불러오지 못했어요"만 보이고 상세 본문은 그대로다.
   - `BenefitDetailView.tsx`: 본문 맨 위(지원 내용 앞)에 결과 박스를, 제목 옆에 관심 버튼을 둔다. 처음에는 신청 사이드 박스 위에 두려 했으나, 사이드 박스가 좁고 화면에 고정돼 항목별 이유를 담기 어려워 구현 중 바꿨다.
7. 관심 저장 (설계 6·7·8)
   - `src/lib/bookmarks/bookmarkStore.ts`: `seedBookmarks(ids, saved)`, `loadBookmarks(ids)`, `useBookmark(serviceId)`, `markBookmark(serviceId, saved)`(누른 즉시 화면 반영), `flushBookmark(serviceId)`(혜택별 차례 전송). 비회원이면 별 버튼이 저장 상태를 읽지 않는다.
   - `src/pages/bookmarks/bookmarkRoute.ts`: `/bookmarks/:serviceId` 액션 전용 경로. `saved`(원하는 상태)와 `returnTo`(누른 화면)를 받아 `flushBookmark`가 끝날 때까지 기다린다. 비회원이거나 `AuthRequiredError`면 `redirect(loginPath(returnTo))`로 보낸다. 누를 때 로그인 상태를 몰라 화면에 반영하지 못했으면(`marked`) 여기서 반영한다.
   - `src/components/BookmarkButton.tsx`: 별 아이콘(`lucide-react` `Star`), `aria-pressed`, 접근 가능한 이름("관심 혜택에 저장" / "관심 혜택에서 빼기"). `useFetcher`로 제출한다. 비회원도 같은 액션으로 보내 액션이 로그인 화면으로 보낸다. 회원의 저장 여부를 받기 전에는 비활성이다.
   - `src/lib/bookmarks/revalidation.ts`: `skipBookmarkRevalidation`. `/benefits`, `/benefits/:serviceId`, `/match`, `/bookmarks` 경로에 붙인다.
   - `BenefitCard.tsx`: 관심 버튼을 링크 밖 형제 요소로 붙이고(오른쪽 위에 겹친다) 조건 비교 결과를 넣을 `children`을 받는다. 검색 목록(`benefitsLoader`)도 `loadBookmarks`를 시작한다.
   - `Pagination.tsx`: 검색·조건 비교·관심 목록이 함께 쓰도록 쪽 주소를 `hrefFor(page)`로 받는다.
8. 관심 목록 (`src/pages/bookmarks/`)
   - `bookmarksRoute.ts`의 `bookmarksLoader`: `ensureSession()` 뒤 비회원이면 `{ kind: "guest" }`, 회원이면 `listBookmarks(page)`와 `seedBookmarks`(모두 저장됨).
   - `BookmarksPage.tsx`: 카드 그리드, 별로 바로 해제(설계 8), 빈 상태(`EmptyState`, [혜택 찾아보기] → `/benefits`, [조건 비교하기] → `/match`), 비회원 로그인 안내(`loginPath("/bookmarks")`).
9. 메뉴와 홈
   - `NavLinks.tsx`: 조건 비교(`/match`, `/profile`에서도 현재 메뉴) / 전체 혜택 / 관심 혜택. 주석을 지운다. 알림 메뉴는 두지 않는다.
   - `HomePage.tsx`: 조건 비교 카드("로그인 없이 내 조건과 비교해 보기" → `/match`). 주석을 지운다.
10. 라우팅 (`src/router.tsx`): `/profile`(loader·action), `/match`, `/bookmarks`, `/bookmarks/:serviceId`(action만)를 더하고 각 화면에 `usePageTitle`·`Breadcrumb`·`hydrateFallbackElement`를 둔다.
11. 문서
    - `docs/decisions/0031-guest-profile-storage.md`(설계 2)와 `docs/decisions/README.md` 목록.
    - `docs/design/README.md` F3 프로필(설계 3).

## 완료 조건 매핑

| 완료 조건 | 구현 단계 | 확인 방법 |
| --- | --- | --- |
| 비회원이 프로필을 입력하면 조건 비교 목록이 보이고, 입력한 값은 서버에 저장되지 않는다는 안내가 보인다. | 3, 4, 5 | 로그아웃 상태로 `/profile`에 입력·제출해 `/match` 목록과 안내 확인. 네트워크 탭에 `PUT /api/me/profile`이 없고 `POST /api/matches` 본문에만 값이 있는지 확인. 새로고침해도 목록이 남고, 탭을 닫고 새로 열면 `/profile`로 가는지 확인 |
| 로그인 사용자가 프로필을 저장한 뒤 다시 들어오면 저장된 값이 채워져 있고, 수정하면 다음 조건 비교에 바뀐 값이 반영된다. | 4, 5 | 로그인해 저장 → 다른 화면에 갔다가 `/profile`에 값이 채워졌는지 확인. 생년월일·거주지를 바꿔 저장한 뒤 `/match`의 요약·건수·결과가 바뀌는지 확인 |
| 항목을 비운 상태와 '해당 없음'을 고른 상태가 화면에서 구분되고, 잘못된 입력에는 항목별 오류가 보인다. | 4 | 속성을 비운 채 저장하면 다시 열었을 때 "선택하지 않음", '해당 없음'을 고르면 '해당 없음' 칩이 선택되어 있는지 확인. 미래 생년월일을 개발자 도구로 넣어 제출해 생년월일 칸에 오류가 붙는지 회원·비회원 모두 확인 |
| 결과는 '해당'·'확인 필요'·'미해당'으로 보인다. '해당'에는 실제 지원 여부는 기관 심사로 정해진다는 안내가 함께 보이고, 화면 어디에도 '신청 가능'으로 확정하는 문구가 없다. | 5, 6 | 목록의 결과별 건수와 묶음 제목(배지·안내), 상세의 배지와 해당 안내 확인. `grep -rn "신청 가능\|신청할 수 있" frontend/src` 결과가 없는지 확인 |
| 확인 필요 결과에는 항목별 이유와 공식 안내 링크가 보이고, 운영 기관 지역 항목에는 거주 요건이 아니라는 안내가 보인다. | 5, 6 | 목록 카드의 확인할 조건 이름, 상세 박스의 항목별 이유·공식 안내 링크·지역 안내 확인. 다른 시도 기관 혜택에서 지역 항목이 확인 필요로 보이는지 확인 |
| 거주지를 입력했으면 내 지역이 기본으로 보이고 전체 지역으로 바꿀 수 있다. 거주지가 없으면 전체 지역이다. | 5 | 거주지를 넣고 `/match`에서 내 지역이 선택되고 전체 지역으로 바꾸면 건수가 늘어나는지 확인. 거주지를 비우면 전체 지역만 보이는지 확인 |
| 상세에서 개인 대상이 아닌 혜택은 '미해당'과 그 이유가 보인다. | 6 | 검색에서 운영 기관 유형이나 사용자구분이 법인·시설인 혜택의 상세를 열어 미해당과 이유 확인(입력 없는 비회원과 회원 모두) |
| 로그인 사용자는 상세와 목록에서 관심 혜택을 저장·해제할 수 있고, 관심 목록에서 바로 해제할 수 있다. 빠르게 여러 번 눌러도 최종 상태가 화면과 서버에서 같다. | 1, 7, 8 | 검색·조건 비교 카드와 상세에서 저장 → `/bookmarks`에 보이는지, 해제하면 빠지는지 확인. 개발자 도구에서 네트워크를 Slow 3G로 두고 별을 홀수·짝수 번 연타한 뒤 새로고침해 화면 상태가 마지막 클릭과 같은지, 요청이 겹치지 않고 차례로 나가는지 확인. `BookmarkApiTest` |
| 비로그인으로 저장을 누르면 로그인 안내가 나오고, 로그인하면 원래 화면으로 돌아온다. | 7, 8 | 로그아웃 상태로 검색 결과(쿼리 포함)와 상세에서 별을 눌러 `/login?returnTo=...`로 가고, 로그인하면 같은 주소로 돌아오는지 확인. `/bookmarks`의 로그인 안내도 확인 |
| 메뉴는 조건 비교 / 전체 혜택 / 관심 혜택이며, 알림 관련 메뉴와 안내는 없다. | 9 | 넓은 화면과 모바일 메뉴에서 메뉴 세 개 확인. `grep -rn "알림" frontend/src`의 결과가 주석(`InfoBox.tsx`, `Toast.tsx`, `lib/toast.ts`)뿐이고 화면 문구에는 없는지 확인 |

## 검증 명령

- `backend/`에서 `./gradlew test`.
- `frontend/`에서 `npm run lint`, `npm run typecheck`, `VITE_API_BASE_URL=http://localhost:8080 npm run build`.
- 화면 확인: `docker compose -f docker-compose.local.yml up -d`, `backend/`에서 `SPRING_PROFILES_ACTIVE=local ./gradlew bootRun`, `frontend/`에서 `npm run dev`. 위 표의 확인 방법을 Chrome에서 `http://localhost:3000`으로 따른다.
- 저장소 확인: `grep -rn "localStorage\|sessionStorage" frontend/src` 결과가 `lib/preferences.ts`와 `lib/profile/guestProfile.ts`뿐인지, 로그인 뒤 Session Storage에 `gbh:guest-profile`이 없는지 확인.

## 제안 (이번 범위 밖)

- 검색 화면의 운영 기관 지역 필터를 프로필 거주지로 미리 채우는 것(`REQ-PROFILE-01`)을 별도 이슈로 제안한다.
- 비회원으로 입력한 값을 로그인 뒤 프로필로 저장할지 묻는 기능을 별도 이슈로 제안한다.

## 결정 필요

없음. 저장 여부 조회(설계 1), 비회원 입력값 보관(설계 2), 프로필 화면 구성(설계 3), 경로(설계 4)는 2026-10-27 사용자 결정으로 정했다.
