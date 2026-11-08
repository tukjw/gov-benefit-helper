status: done

# 구현 계획 — #134 계정 화면에서 새 혜택 알림을 켜고 끄기

브랜치: `feat/134-account-notify-settings-page` (develop `8186685` 기준)

## 목표 및 범위

- 목표: 로그인한 사용자가 웹 화면에서 새 혜택 알림을 켜고 끈다(`REQ-NOTIFY-03` P0의 동의, `DEC-NOTIFY(새 혜택 알림)`). 계정 화면을 두어 알림 설정과 회원 탈퇴를 모은다.
- 포함:
  - 프론트: 계정 화면 `/account`, 알림 설정 화면 `/account/notifications`, 알림 설정 API 클라이언트, 헤더 이메일 링크, 개인정보 처리방침의 알림 끄기 안내 링크, 계정 아래 화면의 위치 표시(설계 5).
  - 문서: `docs/design/README.md`의 채택 범위(새 혜택 알림 설정과 계속 만들지 않는 신청 마감 알림 설정 구분, 계정·알림 설정 화면 구성).
- 제외:
  - 메일에서 로그인 없이 바로 알림을 끄는 링크(이슈 본문).
  - 시안의 신청 마감 알림 화면과 알림 시점·받는 방법 설정. 기한 알림(`REQ-NOTIFY-01`)은 보류다(`DEC-NOTIFY`).
  - 백엔드 변경. 조회·변경 API(#106)와 PATCH CORS 허용(결정 0040)이 이미 있다.
  - 알림 메일 문구 변경. 메일은 "사이트에 로그인한 뒤 알림 설정에서 끄세요"라고 안내하며(`SesAccountMailSender.java`), 이 이슈로 그 경로가 생긴다. 메일에 알림 설정 화면 주소를 바로 넣는 것은 `제안`에 적는다.
  - 프론트 테스트 러너 도입(#73, #74와 같다). 화면 흐름은 로컬 백엔드로 직접 확인한다.

## 확인된 사실과 제약

- 알림 설정 API(`NotificationSettingController`, #106):
  - `GET /api/me/notification-settings` → `{ newBenefit: boolean }`. 설정한 적이 없으면 `false`다.
  - `PATCH /api/me/notification-settings` 본문 `{ newBenefit: boolean }` → 204. `newBenefit`이 없거나 `null`이면 400 `INVALID_INPUT`. 토큰이 없거나 계정이 없으면 401 `UNAUTHENTICATED`. 같은 값을 다시 보내도 결과가 같다.
- CORS는 PATCH를 허용한다(결정 0040). 하지만 프론트 `HttpMethod`(`frontend/src/lib/api/client.ts`)는 `"GET" | "POST" | "PUT" | "DELETE"`라 PATCH를 보낼 수 없다. 204 응답은 `apiRequest`가 `undefined`로 돌려준다.
- 로그인이 필요한 경로는 loader·action을 `memberOnly(request, run)`(`lib/auth/guard.ts`, 결정 0030)로 감싼다. 비회원이거나 실행 중에 `AuthRequiredError`가 나면 `request.url`의 경로를 `returnTo`로 붙여 `/login`으로 보낸다. 로그인 action은 `safeReturnTo`로 그 경로로 돌아온다.
- 화면 데이터는 경로의 loader가 받고 폼 제출은 action이 처리한다(결정 0040, `router.tsx`). action이 데이터를 돌려주면 react-router는 기본으로 그 화면의 loader를 다시 부른다. 경로별 `shouldRevalidate`로 막을 수 있다(`lib/bookmarks/revalidation.ts`).
- 탈퇴 화면(`pages/account/withdrawal/`)은 `withdrawalLoader`가 `memberOnly` 안에서 `getMe`를 부르고, `AccountFrame`(단일 열, 위치 표시는 `홈 › {title}`)과 `AccountPage.module.css`(`summary`, `list`, `form`, `submit`, `links`)를 쓴다. 실패는 `FormError`(`role="alert"`)와 `GENERIC_ERROR`로 보여 준다.
- 헤더 `AccountLinks.tsx`는 로그인 상태에서 이메일을 `/account/withdrawal`로 연결하고, 주석에 "계정 화면은 아직 탈퇴뿐이라 이메일에서 바로 연결한다"고 적혀 있다. 모바일 메뉴는 `AccountLinks`를 쓰지 않는다.
- 개인정보 처리방침(`PrivacyPolicyPage.tsx:276`)은 "관심 혜택은 해제하고, 새 혜택 알림은 끌 수 있습니다."에 링크가 없다. 처리 항목·목적은 바뀌지 않으므로 개정 이력은 더하지 않는다.
- 시안 문서(`docs/design/README.md`): '따르지 않음과 대체 방향' 표의 "신청 마감 알림 화면, 알림 설정, 마감 알림 안내 박스" 행이 `DEC-SCOPE`를 근거로 "만들지 않는다"이고, '범위 밖'에 "시안 6. 신청 마감 알림은 3차 알림(`DEC-NOTIFY`) 전까지 만들지 않는다."가 있다. 시안의 알림 설정은 신청 마감 알림의 사이드 박스(알림 시점 칩, 받는 방법 칩, 설정 저장)다(`docs/design/handoff/README.md` 6).
- 알림 대상(`DEC-NOTIFY`, `REQ-NOTIFY-03`): 새로 저장된 혜택 중 조건 비교 결과가 '해당'인 사용자에게만, 하루치를 한 통으로 묶어 매일 08:00(KST)에 이메일로 보낸다. 해당하는 새 혜택이 없는 날은 보내지 않는다. 프로필에서 비운 항목은 '확인 필요'가 되어 대상에서 빠진다.
- 계정 화면 문구는 "~습니다"체(탈퇴·비밀번호 재설정·가입), 프로필·관심 혜택은 "~해요"체다.

## 설계 결정

1. 경로: 계정 화면 `/account`와 알림 설정 화면 `/account/notifications`를 따로 둔다(2026-11-08 사용자 결정).
   - 대안: `/account` 한 화면에 알림 설정 절을 두기. 이슈가 계정 화면에 "'알림 설정'으로 가는 링크"를 두라고 하고, 개인정보 처리방침과 메일 안내가 가리킬 곳이 알림 설정 하나로 정해지는 편이 낫다.
   - 알림 설정 화면에서 로그인이 풀리면 로그인 뒤 `/account/notifications`로 돌아온다. 계정 화면 아래 화면이므로 완료 조건의 "계정 화면으로 돌아온다"에 해당한다고 본다.
2. 켜기·끄기 방식: 지금 저장된 상태를 글자로 보여 주고, 반대로 바꾸는 버튼 하나를 둔다(2026-11-08 사용자 결정).
   - 꺼져 있으면 "새 혜택 알림: 꺼짐"과 [알림 켜기], 켜져 있으면 "켜짐"과 [알림 끄기]. 버튼은 `name="newBenefit"`, `value`가 바꿀 값(`"true"`/`"false"`)인 제출 버튼이다.
   - 대안: 스위치·체크박스를 누르는 즉시 바뀌어 보이게 하기. 저장 전에 화면 값이 먼저 바뀌어 실패하면 되돌려야 하고, 완료 조건의 "저장되지 않은 값을 저장된 것처럼 보여 주지 않는다"와 어긋날 틈이 생긴다.
   - 저장 중(`navigation.state !== "idle"`)에는 버튼을 막고 "저장하는 중"으로 바꿔 연타로 요청이 겹치지 않게 한다.
3. 화면에 보이는 값은 loader가 받은 서버 값뿐이다.
   - 성공: action이 토스트("새 혜택 알림을 켰습니다." / "새 혜택 알림을 껐습니다.")를 띄우고 데이터를 돌려준다. 기본 재검증으로 loader가 다시 `GET`해 바뀐 값을 보여 준다.
   - 실패: action이 `{ formError }`를 돌려주고 `shouldRevalidate`가 이때는 loader를 다시 부르지 않는다. 화면은 직전에 받은 값(저장 전 값)과 `FormError`를 보여 준다. 다시 부르면 서버가 내려간 상태에서 loader도 실패해 오류 화면이 실패 안내를 가린다.
   - 응답을 받지 못했는데 실제로는 저장된 경우 화면은 저장 전 값을 보여 준다. 저장된 것처럼 보이지는 않으며, 다시 열면 서버 값이 보인다.
   - 로그인이 풀린 경우(`AuthRequiredError`)는 다시 던져 `memberOnly`가 로그인 화면으로 보낸다.
4. 계정 화면 구성: `AccountFrame`의 단일 열에 계정 이메일(`summary`)과 링크 두 개(알림 설정: "새 혜택 알림 켜기·끄기", 회원 탈퇴)를 둔다. 알림이 켜졌는지는 계정 화면에 두지 않는다. 알림 설정 화면 하나에서만 보여 줘 두 곳의 값을 맞출 일을 만들지 않는다.
5. 위치 표시: `AccountFrame`에 선택 속성 `underAccount?: boolean`을 더해 알림 설정과 회원 탈퇴를 `홈 › 계정 관리 › {title}`로 보인다. 처음에는 `parent?: BreadcrumbItem`을 받으려 했으나, 부모가 계정 관리 하나뿐이라 부르는 쪽마다 같은 값을 넘기지 않게 참·거짓으로 바꿨다(구현 중 조정). 계정 화면이 생기면 탈퇴도 그 아래 화면이 되므로 함께 바꾼다. 가입·로그인·비밀번호 재설정은 그대로 둔다.
6. 안내 문구(알림 설정 화면, `InfoBox`):
   - 제목 "새 혜택 알림은 이렇게 보냅니다"
   - "새로 등록된 혜택 중 조건 비교 결과가 '해당'인 혜택을 모아 매일 오전 8시(한국 시간)에 메일 한 통으로 보냅니다. 해당하는 새 혜택이 없는 날은 보내지 않습니다."
   - "프로필에서 비워 둔 항목은 '확인 필요'가 되어 그 항목이 조건인 혜택은 알림에서 빠집니다. [프로필]에서 확인해 주세요." (`<Link to="/profile">`)
   - "받는 주소: {이메일}"은 상태 줄의 '새 혜택 알림' 아래에 둔다.

## 단계별 구현 계획

1. 착수: 이 파일의 `status`를 `in-progress`로 바꾼다.
2. API 클라이언트 (`frontend/src/lib/api/`)
   - `client.ts`: `HttpMethod`에 `"PATCH"`를 더한다.
   - `types.ts`: `NotificationSettingResponse = { newBenefit: boolean }`, `NotificationSettingRequest = { newBenefit: boolean }`.
   - `notification.ts`(새 파일): `getNotificationSettings(signal?)` → `authFetch("GET", "/api/me/notification-settings", { signal })`, `updateNotificationSettings(body)` → `authFetch("PATCH", "/api/me/notification-settings", { body })`.
3. `AccountFrame` 위치 표시 (`pages/account/AccountFrame.tsx`, 설계 5)
   - `AccountFrame`과 `AccountFallback`에 `underAccount?: boolean`. 참이면 `Breadcrumb items`를 `[{ label: ACCOUNT_TITLE, href: ACCOUNT_PATH }, { label: title }]`로 만든다.
   - `lib/account/paths.ts`(새 파일): `ACCOUNT_TITLE = "계정 관리"`, `ACCOUNT_PATH = "/account"`, `NOTIFICATION_SETTINGS_PATH = "/account/notifications"`, `WITHDRAWAL_PATH = "/account/withdrawal"`. 헤더(`components`)도 쓰는데 `components`·`lib`는 `pages`를 가져오지 않으므로 `lib`에 둔다. 헤더·처리방침·라우터·계정 화면이 같은 경로를 쓴다.
   - `WithdrawalPage.tsx`: `<AccountFrame title="회원 탈퇴" underAccount>`.
4. 계정 화면 (`pages/account/home/`, 설계 1·4)
   - `accountRoute.ts`: `accountLoader({ request })` → `memberOnly(request, async () => ({ account: await getMe(request.signal) }))`.
   - `AccountHomePage.tsx`: `usePageTitle(ACCOUNT_TITLE)`, `<AccountFrame title={ACCOUNT_TITLE}>` 안에 `summary`(계정: 이메일)와 링크 목록. 링크마다 이름과 한 줄 설명, 오른쪽 화살표(`ChevronRight`)를 둔다. `AccountPage.module.css`에 `menu`·`menuLink`·`menuText`를 더한다.
5. 알림 설정 화면 (`pages/account/notifications/`, 설계 2·3·6)
   - `notificationRoute.ts`
     - `notificationLoader({ request })` → `memberOnly` 안에서 `Promise.all([getMe(signal), getNotificationSettings(signal)])`로 `{ email, newBenefit }`.
     - `NotificationResult = { saved?: true; formError?: string }`.
     - `notificationAction({ request })`: `formText(form, "newBenefit")`가 `"true"`·`"false"`가 아니면 요청 없이 저장 실패 문구를 돌려준다. `memberOnly` 안에서 `updateNotificationSettings({ newBenefit })`. `AuthRequiredError`는 다시 던지고 그 밖의 오류는 `{ formError: "알림 설정을 저장하지 못했습니다. 잠시 뒤 다시 시도해 주세요." }`. 성공하면 `showToast(...)` 뒤 `{ saved: true }`.
     - `notificationShouldRevalidate: ShouldRevalidateFunction` → `actionResult?.formError ? false : defaultShouldRevalidate`.
   - `NotificationSettingsPage.tsx`: `usePageTitle("알림 설정")`, `<AccountFrame title="알림 설정" underAccount>`. 상태 줄(새 혜택 알림·받는 주소와 켜짐/꺼짐 글자), `<Form method="post">` 안에 `FormError`와 제출 버튼(설계 2), 그 아래 `InfoBox` 안내(설계 6). 켜짐·꺼짐은 글자로 보여 주고 색만으로 구분하지 않는다. 저장 중 판단은 `navigation.state !== "idle" && navigation.formMethod !== undefined`로, 저장 요청과 그 뒤 다시 받는 동안을 모두 막는다. `AccountPage.module.css`에 `setting`·`settingRow`·`settingLabel`·`settingState`·`settingOn`·`infoList`를 더한다.
6. 라우터 (`frontend/src/router.tsx`)
   - `{ path: ACCOUNT_PATH, loader: accountLoader, element: <AccountHomePage />, hydrateFallbackElement: <AccountFallback title={ACCOUNT_TITLE} /> }`
   - `{ path: NOTIFICATION_SETTINGS_PATH, loader: notificationLoader, action: notificationAction, shouldRevalidate: notificationShouldRevalidate, element: <NotificationSettingsPage />, hydrateFallbackElement: <AccountFallback title="알림 설정" underAccount /> }`
   - 탈퇴는 경로를 `WITHDRAWAL_PATH`로 쓰고 `hydrateFallbackElement`에도 `underAccount`.
7. 헤더 (`components/layout/AccountLinks.tsx`)
   - 이메일 링크를 `ACCOUNT_PATH`로 바꾸고 `title`과 숨은 문구를 `ACCOUNT_TITLE`로 바꾼다. "아직 탈퇴뿐이라" 주석을 계정 화면 설명으로 고친다.
8. 개인정보 처리방침 (`pages/privacy/PrivacyPolicyPage.tsx:276`)
   - "관심 혜택은 해제하고, 새 혜택 알림은 <Link to={NOTIFICATION_SETTINGS_PATH}>알림 설정</Link>에서 끌 수 있습니다." 뒤의 회원 탈퇴 링크도 `WITHDRAWAL_PATH`로 쓴다.
9. 시안 채택 범위 (`docs/design/README.md`)
   - '따르지 않음과 대체 방향' 표의 알림 행을 둘로 나눈다.
     - "신청 마감 알림 화면, 마감 알림 설정(알림 시점·받는 방법), 마감 알림 안내 박스" | `DEC-NOTIFY`: 기한 알림(`REQ-NOTIFY-01`)은 보류한다 | 만들지 않는다. 메뉴에서도 뺀다
     - "알림 설정 사이드 박스" | `DEC-NOTIFY`: 동의는 새 혜택 알림 켜기·끄기 하나다 | 계정 관리 아래 알림 설정 화면에서 켜기·끄기 하나(F5)
   - '시안에 없는 P0 화면과 표시' 표에 "계정 관리"(`REQ-ACCOUNT-01`, `REQ-NOTIFY-03` | 단일 열. 계정 이메일과 알림 설정·회원 탈퇴 링크. 헤더 이메일에서 연결) 행을 더한다.
   - '화면별'에 `### F5 계정 관리·알림 설정`을 더한다. F2의 단일 열과 안내 박스로 구성하고, 지금 상태 글자와 반대로 바꾸는 버튼 하나, 저장 결과 토스트, 실패 시 폼 오류, 알림 대상·발송 시각·비워 둔 프로필 항목 안내와 프로필 링크를 적는다.
   - F4 탈퇴 설명에 위치 표시가 계정 관리 아래라는 것을 더한다.
   - '범위 밖'을 "시안 6. 신청 마감 알림은 기한 알림이 보류라(`DEC-NOTIFY`) 만들지 않는다. 새 혜택 알림 설정은 F5에 있다."로 바꾼다.
10. 검증 뒤 PR 직전 마지막 커밋에서 `status`를 `done`으로 바꾼다.

## 완료 조건 매핑

| 완료 조건 | 구현 단계 | 확인 방법 |
| --- | --- | --- |
| 로그인한 사용자가 헤더의 이메일을 누르면 계정 화면이 열리고, 거기서 알림 설정과 회원 탈퇴로 갈 수 있다. | 3, 4, 6, 7 | 로그인 뒤 헤더 이메일 → `/account`. 두 링크로 `/account/notifications`·`/account/withdrawal`이 열리고 위치 표시의 '계정 관리'로 돌아오는지 확인 |
| 알림 설정은 지금 설정을 보여 준다. 설정한 적이 없으면 꺼짐이다. | 2, 5 | 새로 가입한 계정으로 `/account/notifications`에 "꺼짐"과 [알림 켜기]가 보이는지 확인 |
| 켜거나 끄면 저장되고, 화면을 다시 열어도 바꾼 값이 보인다. 저장에 실패하면 실패를 알리고, 저장되지 않은 값을 저장된 것처럼 보여 주지 않는다. | 2, 5 | 켜기 → 토스트, "켜짐", 새로고침해도 "켜짐", 네트워크 탭의 `PATCH` 204 뒤 `GET`. 끄기도 같다. 개발자 도구에서 `PATCH`를 차단(Network request blocking)하거나 백엔드를 끈 뒤 누르면 폼 오류가 보이고 상태 글자·버튼이 바뀌지 않는지 확인. Slow 3G에서 연타해도 `PATCH`가 한 번만 나가는지 확인 |
| 알림 대상과 발송 시각, 비워 둔 프로필 항목은 알림 대상에서 빠진다는 안내가 프로필 링크와 함께 보인다. | 5 | 안내 박스의 문구와 `/profile` 링크 확인 |
| 로그인하지 않았거나 로그인이 풀린 채로 계정 화면을 열거나 설정을 바꾸면 로그인 화면으로 가고, 로그인하면 계정 화면으로 돌아온다. | 4, 5 | 로그아웃 상태로 `/account`, `/account/notifications`를 열면 `/login?returnTo=...`로 가고 로그인 뒤 같은 화면으로 오는지 확인. 알림 설정 화면을 연 채 개발자 도구에서 `refresh_token` 쿠키를 지우고 액세스 토큰이 만료된 뒤(또는 다른 탭에서 로그아웃 뒤) 버튼을 누르면 로그인 화면으로 가고, 로그인 뒤 알림 설정 화면으로 오는지 확인 |
| 개인정보 처리방침의 알림 끄기 안내가 알림 설정으로 연결된다. | 8 | `/privacy`의 '정보주체와 법정대리인의 권리와 행사 방법' 절에서 '알림 설정' 링크가 `/account/notifications`로 가는지 확인 |
| 화면 시안 채택 범위에서 새 혜택 알림 설정과, 계속 만들지 않는 신청 마감 알림 설정이 구분돼 있다. | 9 | `docs/design/README.md`의 두 행, F5, '범위 밖' 확인 |

## 검증 명령

- `frontend/`에서 `npm run lint`, `npm run typecheck`, `VITE_API_BASE_URL=http://localhost:8080 npm run build`(CI와 같은 값).
- 백엔드는 바꾸지 않으므로 `./gradlew test`는 CI의 `test` 체크로 확인한다.
- 화면 확인: `docker compose -f docker-compose.local.yml up -d`, `backend/`에서 `SPRING_PROFILES_ACTIVE=local ./gradlew bootRun`, `frontend/`에서 `npm run dev`. 위 표의 확인 방법을 Chrome에서 `http://localhost:3000`으로 따른다.
- 남은 링크 확인: `grep -rn "account/withdrawal" frontend/src` 결과가 `lib/account/paths.ts`와 탈퇴 화면 파일의 import 경로뿐인지 확인.

## 제안 (이번 범위 밖)

- 알림 메일의 끄기 안내에 알림 설정 화면 주소(`{siteUrl}/account/notifications`)를 바로 넣는 것. 로그인하지 않았으면 로그인 뒤 그 화면으로 돌아온다. 백엔드 메일 문구 변경이라 별도 이슈로 제안한다.

## 결정 필요

없음. 경로(설계 1)와 켜기·끄기 방식(설계 2)은 2026-11-08 사용자 결정으로 정했다.
