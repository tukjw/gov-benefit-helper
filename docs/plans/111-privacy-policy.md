status: in-progress

# 구현 계획 — #111 개인정보 처리방침 페이지 공개와 만 14세 미만 가입 제한

브랜치: `feat/111-privacy-policy` (develop `6a10238` 기준)

## 목표 및 범위

- 목표: 실제 처리와 같은 개인정보 처리방침을 로그인 없이 볼 수 있게 공개하고(개인정보 보호법 제30조), 만 14세 미만은 가입하지 않게 한다(제22조의2의 법정대리인 동의 대신).
- 포함:
  - 인증번호 요청·가입 API의 만 14세 이상 확인 필드 `ageConfirmed`와 검증.
  - 가입 화면의 만 14세 확인 체크박스와 처리방침 안내.
  - 처리방침 화면 `/privacy`, 푸터 링크.
  - API 명세, `REQ-ACCOUNT-01`, `DEC-AUTH`, 결정 문서 0044, 디자인 문서.
- 제외:
  - 법정대리인 동의 절차. 이슈가 가입 제한으로 정했다.
  - 이미 가입한 계정의 나이 확인. 지금 계정은 운영자 테스트 계정뿐이다.
  - 프로필 생년월일로 만 14세 미만을 다시 막는 것. 가입할 때 확인받았고, 생년월일은 선택 항목이라 비교 기준이 되지 못한다.
  - 시안의 이용약관·저작권정책 링크. 해당 문서가 없다.
  - 새 혜택 알림 메일 발송. 아직 구현되지 않았다. 처리방침에는 지금 처리하는 알림 설정 값만 적고, 발송을 구현하는 이슈가 처리방침을 고친다(2026-11-03 사용자 결정, 결정 0044).
  - 만료·폐기된 리프레시 토큰, 사용한 재설정 토큰의 파기. 아래 `제안`에 적는다.

## 확인된 사실과 제약

- 가입은 `POST /api/auth/email-verification`(`EmailVerificationRequest{email}`) → `/confirm` → `POST /api/auth/signup`(`SignUpRequest{email, password, verificationToken}`) 순서다(결정 0041). 두 요청 DTO는 `@Valid`로 검증되고, 검증에 실패하면 서비스가 불리지 않는다. 따라서 메일이 나가지 않고, 인증 기록이 생기지 않으며, 가입 티켓도 쓰이지 않는다.
- `GlobalExceptionHandler.handleMethodArgumentNotValid`는 필드 오류를 `InputError(field, value, reason)`로 바꾼다. Boolean 필드는 `@NotNull`(null)과 `@AssertTrue`(false)를 함께 걸면 경우마다 오류가 하나씩 생긴다. `@AssertTrue`는 null을 통과시킨다. 알림 설정의 `NotificationSettingRequest`가 `@NotNull Boolean`을 쓴다.
- `SignUpRequest` 생성자를 직접 부르는 테스트: `BookmarkConcurrencyTest`, `PasswordValidatorTest`, `EmailVerificationConcurrencyTest`, `LoginAttemptConcurrencyTest`, `WithdrawalConcurrencyTest`, `RefreshTokenConcurrencyTest`, `SignUpConcurrencyTest`. API 요청 본문은 `SignUpSteps`(`requestCode`, `signUp`)와 `SignUpApiTest`가 만든다. 서비스의 `EmailVerificationService.request(email)`는 이메일만 받으므로 바뀌지 않는다.
- 프론트 가입 상태는 action 결과(`SignupState`)와 숨은 입력에만 있다. 단계 사이에 값을 넘기려면 숨은 입력이 필요하고, 다른 경로로 이동하면 상태를 잃는다(`signupRoute.ts`).
- `RootLayout`이 모든 경로와 오류 화면에 `SiteFooter`를 그린다. 시안 푸터는 처리방침 링크를 굵게(700) `#0B50D0`(`--color-primary-strong`)으로 둔다(`docs/design/handoff/README.md`).
- 실제 처리(처리방침에 적을 값):
  - `user_account`: 이메일, 비밀번호(BCrypt), 가입 시각, 새 혜택 알림 설정. 탈퇴하면 삭제되고, 프로필·관심 혜택·리프레시 토큰·재설정 토큰은 `ON DELETE CASCADE`로 함께 지워진다.
  - `email_verification`: 이메일, 인증번호·티켓 SHA-256, 시도 횟수, 시각. 발송 후 24시간(`auth.mail.window`)이 지난 행을 매일 04:30에 지운다(결정 0041).
  - `refresh_token`: 토큰 해시, 발급·만료·교체·폐기 시각. `password_reset_token`: 토큰 해시, 시각. 둘 다 만료되거나 써도 행을 지우는 작업이 없어 탈퇴할 때까지 남는다.
  - `user_profile`: 생년월일, 성별, 시도·시군구, 건강 관련 동의 시각, 생애·가구 속성(`user_profile_attribute`). 건강 관련 4개 속성은 별도 동의를 받는다(#110).
  - 로그인 실패 기록(이메일, IP, 시각): 15분(`auth.login-attempt.window`) 동안 앱 메모리에만 있고, 재시작하면 사라진다(`LoginAttemptLimiter`).
  - 비회원 조건 비교: 요청 값을 처리만 하고 저장하지 않는다. 브라우저 `sessionStorage`(`guestProfile.ts`)에 그 탭 동안만 남는다.
  - 쿠키: `refresh_token`(`api.govbenefit.kr`, 경로 `/api/auth`, HttpOnly·Secure·SameSite=Strict, 14일). 액세스 토큰은 메모리에만 있다. `localStorage`의 `gbh:view`는 보기 방식 설정이라 개인정보가 아니다. 분석·광고 도구는 없다.
  - 앱 로그에는 이메일·IP를 남기지 않는다. 사용자 ID와 건수만 남긴다. Docker 로그는 크기 기준으로 교체된다(`10m × 3`).
  - 백업이 없다(`DEC-OPS`).
  - 운영 DB는 MySQL 8.4 기본 설정이라 바이너리 로그가 켜져 있고 30일(`binlog_expire_logs_seconds` 기본값) 뒤 지워진다(`deploy/db/compose.yaml`에 따로 정한 값이 없다). 따라서 지운 행의 값도 바이너리 로그에는 최대 30일 남는다. 구현하면서 확인했다.
  - 운영 응답(`govbenefit.kr`, `api.govbenefit.kr`)에 Cloudflare 쿠키는 없다. 다만 `Report-To`·`NEL` 헤더가 있어, 접속 오류가 생기면 브라우저가 Cloudflare로 오류 보고를 보낸다. 2026-11-03에 `curl -I`로 확인했다.
- 위탁·국외 이전(2026-11-03 조사):
  - Resend: Plus Five Five, Inc., 2261 Market Street #5039, San Francisco, CA 94114, `privacy@resend.com`(DPA). 발송 리전은 도쿄이고, 하위 처리자는 모두 미국에 있다. 발송 기록 보관은 30일이고(요금 페이지 Data retention, 모든 플랜), 계약이 끝나면 90일 안에 삭제한다(DPA Exhibit A). 이전 항목은 수신 이메일과 메일 내용(인증번호, 재설정 링크)이다.
  - Cloudflare: Cloudflare, Inc., 101 Townsend St., San Francisco, CA 94107, `privacyquestions@cloudflare.com`. 프론트는 Pages, API는 Tunnel로 Cloudflare를 거친다. TLS가 Cloudflare에서 끝나므로 접속 IP, 브라우저 정보, 요청·응답 내용(이메일·비밀번호·프로필 포함)이 Cloudflare 네트워크를 지난다. 비회원 조건 비교 목록에는 요청량 제한이 있다. Cloudflare 개인정보처리방침은 최종 사용자 데이터를 고객의 처리자로서 다루고 "주로 미국과 유럽경제지역(EEA)에 저장한다"고 적는다. 보관 기간은 명시하지 않았다.
  - 국외 이전 기재 사항(제28조의8 제2항): 이전 항목, 이전 국가·시기·방법, 받는 자(법인명·연락처), 이용 목적·보유 기간, 거부 방법·절차·효과. 계약 이행에 필요한 위탁·보관이므로 이를 처리방침에 공개하면 별도 동의는 필요 없다(제28조의8 제1항 제3호). 조문 번호는 구현할 때 법령 원문으로 확인한다.
- 작성지침: 개인정보보호위원회가 2025년 4월에 개정했고, 2026년 4월에 다시 개정했다(생성형 AI 부록, 수탁자 유형 기재, 행태정보 예시, 간이형 정비). 2025년 개정본은 법령상 필수와 권장을 나누고, 동의 없이 처리하는 항목과 동의 항목을 구분하며, 처리방침을 회원가입 영역에도 공개할 수 있게 했다. 원문은 구현할 때 개인정보보호위원회 누리집에서 받는다.
- 2026-11-03 사용자 결정:
  - 나이 확인 시점은 첫 단계(이메일)이고, API는 인증번호 요청과 가입 두 곳에서 검증한다.
  - 개인정보 보호책임자는 '운영자'로 적고, 연락처는 `contact@govbenefit.kr`이다.
  - 시행일은 공개하는 날인 2026년 11월 4일이다(2026-11-04 사용자 결정으로 오늘 날짜에 맞췄다).
  - 알림 메일은 발송을 구현할 때 처리방침에 더한다.

## 설계 결정

1. **만 14세 확인은 `ageConfirmed: true`로 인증번호 요청과 가입 요청에 보낸다.** 2026-11-03 사용자 결정. 결정 0044.
   - `true`가 아니면(`false`, `null`, 빠짐) 400 `INVALID_INPUT`이고, 필드는 `ageConfirmed`다. 값은 받은 값(`"false"`)이고, 없었으면 `null`이다.
   - 인증번호 요청에서 막으면 만 14세 미만의 이메일이 메일 발송·인증 기록(24시간)으로 처리되지 않는다. 가입 요청에서도 막아야 API를 직접 부르는 경우까지 "확인 없이 가입되지 않는다"가 성립한다.
   - 인증번호 확인(`/confirm`) 요청에는 두지 않는다. 앞 단계에서 확인을 마쳤고, 가입에서 다시 확인한다.
   - 확인 시각은 저장하지 않는다. 확인 없이는 계정이 생기지 않으므로 `user_account.created_at`이 확인 시각이다.
   - 대안: 가입 API에서만 검증. 단순하지만 만 14세 미만의 이메일을 인증 단계에서 처리한다.
2. **0041을 대체하지 않고 0044에서 필드를 더한다.** 0041의 흐름·저장·판정 규칙은 바뀌지 않고 요청 필드 하나가 늘어난다. 0044에 "0041의 인증번호 요청·가입 요청에 `ageConfirmed`를 더한다"고 적는다.
3. **처리방침 본문은 프론트의 화면 컴포넌트에 둔다.** `docs/`에 마크다운으로 두고 렌더링하면 마크다운 파서 의존성이 생기고, 정적 SPA에서 본문을 따로 불러와야 한다. 본문의 수치 옆에는 근거 설정 키를 주석으로 적어, 설정을 바꿀 때 검색으로 찾게 한다.
4. **처리 항목·기간·위탁이 바뀌는 변경은 같은 PR에서 처리방침을 고친다.** 결정 0044. 이후 이슈(알림 발송 등)가 따라야 하는 규칙이라 결정 문서로 둔다.
5. **처리 근거 구분(처리방침 2절)**
   - 동의 없이, 계약 이행(제15조 제1항 제4호): 계정(이메일·비밀번호), 인증 기록, 로그인 유지 기록, 재설정 기록, 프로필(건강 관련 4개 속성 제외), 관심 혜택, 알림 설정.
   - 동의 없이, 정당한 이익(제15조 제1항 제6호): 로그인 실패 기록(이메일·IP), Cloudflare를 지나는 접속 정보. 부정 로그인과 과도한 요청을 막는다.
   - 별도 동의(제23조): 건강 관련 4개 속성. #110 안내문의 목적·항목·보유 기간·거부 시 불이익을 그대로 옮긴다.
   - 근거 분류가 작성지침의 예시와 다르면 구현할 때 지침을 따르고, PR 설명에 적는다.
6. **가입 화면의 처리방침 링크는 새 창으로 연다.** 같은 창으로 이동하면 진행 중인 가입 단계를 잃는다. 푸터 링크는 같은 창이다.
7. **가입 화면에서 `ageConfirmed` 오류가 이메일 단계가 아닌 곳에서 오면 이메일 단계로 돌린다.** 숨은 입력이 조작된 경우만 해당한다. 체크박스는 이메일 단계에만 있기 때문이다.
8. **Cloudflare의 이전 국가는 "미국 등(주로 미국·유럽경제지역)"으로 적는다.** 2026-11-03 사용자 결정. 이슈는 "미국"이었으나 Cloudflare 개인정보처리방침이 최종 사용자 데이터를 주로 미국과 EEA에 저장한다고 적어, 받는 자의 공개 문서와 맞춘다.

## 단계별 구현 계획

1. **DTO**
   - `SignUpRequest`
     - `public static final String AGE_CONFIRMATION_MESSAGE = "만 14세 이상만 가입할 수 있습니다.";`를 둔다.
     - 마지막 필드로 `@NotNull(message = AGE_CONFIRMATION_MESSAGE) @AssertTrue(message = AGE_CONFIRMATION_MESSAGE) Boolean ageConfirmed`를 더한다.
     - `@Schema`: "만 14세 이상이면 `true`. `true`가 아니면 400이다. 만 14세 미만은 가입할 수 없다." `requiredMode = REQUIRED`, `example = "true"`.
     - `toString`에 `ageConfirmed`를 더한다.
   - `EmailVerificationRequest`: 같은 필드와 메시지(`SignUpRequest.AGE_CONFIRMATION_MESSAGE`)를 더한다. 클래스 주석에 #111을 더한다.
2. **API 설명**
   - `EmailVerificationController.request`·`AuthController.signUp`의 `@Operation` 설명에 한 줄을 더한다: 만 14세 이상 확인(`ageConfirmed: true`)이 없으면 400 `INVALID_INPUT`이고, 메일을 보내지 않는다(가입은 티켓을 쓰지 않는다).
   - 400 응답 설명에도 같은 내용을 더한다.
   - `OpenApiConfig.DESCRIPTION`의 가입 순서 문장에 "인증번호 요청과 가입에는 만 14세 이상 확인(`ageConfirmed: true`)이 필요하다"를 더한다.
3. **백엔드 테스트**
   - `SignUpSteps.requestCode`·`signUp` 본문에 `"ageConfirmed", true`를 더한다. `body(...)`가 문자열만 받으면 값 타입을 `Object`로 넓힌다.
   - `SignUpRequest` 생성자를 부르는 7개 테스트에 `true`를 더한다.
   - `EmailVerificationApiTest`: `ageConfirmed`가 없거나, `false`이거나, `null`이면(`@ParameterizedTest`) 400이고 `errors[0].field`가 `ageConfirmed`, `value`가 `null`/`"false"`/`null`이다. 메일이 나가지 않고(`RecordingAccountMailSender`), `email_verification` 행이 생기지 않는다.
   - `SignUpApiTest`: 유효한 티켓으로 `ageConfirmed` 없이·`false`·`null`이면 400이고 계정이 생기지 않는다. 같은 티켓에 `true`를 보내면 201이다(티켓이 쓰이지 않았음).
   - `ApiDocsTest`: `SignUpRequest`의 required에 `ageConfirmed`를 더한다. `EmailVerificationRequest`의 required는 `email`, `ageConfirmed`다.
4. **프론트 API** `src/lib/api/auth.ts`: `requestSignupCode(email, ageConfirmed)`, `signUp(email, password, verificationToken, ageConfirmed)`로 본문에 `ageConfirmed`를 싣는다.
5. **가입 화면**
   - `signupRoute.ts`
     - `SignupState`에 `ageConfirmed?: boolean`을 더한다.
     - `sendCode(email, from, ageConfirmed)`: `form.get("ageConfirmed") === "true"`를 받는다. 이메일 단계에서 거짓이면 API를 부르지 않고 `fieldErrors.ageConfirmed`를 돌려준다. 이메일이 비어 있을 때와 함께 모아서 돌려준다.
     - 성공한 뒤의 상태와 `edit-email` 상태에 `ageConfirmed: true`를 실어, 이메일을 바꾸러 돌아가도 체크가 남게 한다.
     - `signup`: `ageConfirmed`를 API로 보낸다. `fieldErrorsOf` 결과에 `ageConfirmed`가 있으면 `{ step: "email", email, fieldErrors: { ageConfirmed } }`를 돌려준다(설계 7).
   - `SignupPage.tsx`
     - 이메일 단계: 이메일 입력 아래에 체크박스(`name="ageConfirmed" value="true"`, `defaultChecked={state.ageConfirmed}`)를 둔다. 라벨은 "만 14세 이상입니다(필수)", 힌트는 "만 14세 미만은 가입할 수 없습니다."이다. 오류는 `role="alert"`로 보이고 `aria-invalid`·`aria-describedby`를 붙인다.
     - 그 아래 안내: "가입하면 이메일과 비밀번호를 개인정보 처리방침에 따라 처리합니다." 처리방침 링크는 새 창이고 `(새 창)` 숨은 글자를 단다(설계 6).
     - 인증번호 다시 받기 폼과 비밀번호 폼에 `<input type="hidden" name="ageConfirmed" value="true" />`를 둔다.
   - `AccountPage.module.css`: 체크박스 줄 스타일. #110의 `SensitiveConsent` 체크박스와 같은 토큰을 쓴다.
6. **처리방침 화면**
   - `src/pages/privacy/PrivacyPolicyPage.tsx`·`PrivacyPolicyPage.module.css`를 새로 만든다. `usePageTitle("개인정보 처리방침")`을 쓰고, 맨 위에 목차를 둔다. 각 절은 `<section aria-labelledby>`와 `<h2>`로 만든다. 표는 `<table>`과 `<caption>`을 쓴다.
   - `router.tsx`: `{ path: "/privacy", element: <PrivacyPolicyPage /> }`. loader가 없고 로그인이 필요 없다.
   - 본문(설계 3·5. 서술은 "~합니다" 존댓말):
     1. 처리 목적: 회원 가입·관리(이메일 소유 확인, 로그인 유지, 부정 이용 방지), 혜택 조건 비교·프로필 저장·관심 혜택·알림 설정, 비회원 조건 비교.
     2. 처리 항목과 근거: 설계 5의 세 구분을 표로 적는다. 비회원 입력은 저장하지 않는다고 적는다. "만 14세 미만 아동의 개인정보는 처리하지 않으며, 가입할 때 만 14세 이상인지 확인합니다."
     3. 보유 기간: 계정·프로필·관심 혜택·알림 설정·로그인 유지·재설정 기록은 탈퇴할 때까지(탈퇴하면 즉시 삭제). 건강 관련 속성은 동의 철회(모두 빼고 저장) 또는 탈퇴까지. 인증 기록은 발송 후 24시간이 지나면 하루 한 번 파기. 로그인 실패 기록은 15분. 비회원 입력은 서버에 저장하지 않음.
     4. 파기 절차·방법: 보유 기간이 지나거나 탈퇴하면 DB에서 지운다. 바이너리 로그에는 최대 30일 남는다. 종이 문서는 없다. 백업을 두지 않는다.
     5. 제3자 제공: 하지 않는다.
     6. 처리 위탁: Resend(인증·재설정 메일 발송), Cloudflare(웹 화면 제공, API 연결, 요청량 제한·보안).
     7. 국외 이전: 받는 자별 표(법인명·연락처, 국가, 항목, 시기·방법, 목적, 보유 기간)와 법적 근거, 거부 방법·효과.
        - Resend: 일본(발송 리전 도쿄)·미국. 이메일·메일 내용. 메일을 보낼 때마다 HTTPS API로 보낸다. 발송 기록은 30일 보관한다.
        - Cloudflare: 미국 등(주로 미국·유럽경제지역, 설계 8). 접속 정보·요청 내용. 접속할 때마다 네트워크로 전달된다. 보유 기간은 Cloudflare 보관 기준이다.
        - 거부: 서비스 구조상 이전 없이 이용할 수 없으므로 가입하지 않거나 탈퇴하면 된다. 그러면 회원 기능을 쓸 수 없다.
     8. 정보주체와 법정대리인의 권리와 행사 방법: 열람·정정·삭제·처리정지·동의 철회. 화면(프로필 수정, 관심 해제, 알림 끄기, 탈퇴)이나 `contact@govbenefit.kr`로 요청할 수 있다. 대리인을 통한 요청도 가능하다.
     9. 자동 수집 장치: `refresh_token` 쿠키(목적·기간·속성), 브라우저 저장소 2가지, 분석·광고·행태정보 없음, 브라우저에서 거부하는 방법과 거부하면 로그인이 유지되지 않는다는 효과.
        - 운영 `govbenefit.kr`·`api.govbenefit.kr`에서 개발자 도구로 Cloudflare가 붙이는 쿠키(`__cf_bm` 등)가 있는지 확인하고, 있으면 함께 적는다.
     10. 안전성 확보 조치: 비밀번호 BCrypt, 토큰·인증번호 SHA-256 해시 저장, HTTPS, 액세스 토큰 메모리 보관, 로그인 시도·메일 발송 제한, 운영자 1인만 접근, DB를 외부에 열지 않음(Tunnel), 서버를 운영자가 관리하는 장소에 둠.
     11. 개인정보 보호책임자: 운영자, `contact@govbenefit.kr`. 고충 처리도 같은 연락처다.
     12. 권익침해 구제 방법: 개인정보분쟁조정위원회(1833-6972, kopico.go.kr), 개인정보침해신고센터(118, privacy.kisa.or.kr), 대검찰청(1301, spo.go.kr), 경찰청(182, ecrm.police.go.kr).
     13. 시행일: 2026년 11월 4일. 변경 이력은 첫 판이라 "없음"이다.
   - 외부 링크는 새 창과 `(새 창)` 숨은 글자를 단다(푸터와 같다).
7. **푸터** `SiteFooter.tsx`·`SiteFooter.module.css`: 안내 문장 아래에 `<Link to="/privacy">개인정보 처리방침</Link>`을 둔다. 굵게(700), `--color-primary-strong`으로 데이터 출처·문의 링크와 구분한다. 파일 주석에 #111을 더한다.
8. **작성지침 대조**: 개인정보보호위원회 누리집에서 최신 「개인정보 처리방침 작성지침」(2026년 4월 개정본)을 받아, 법령상 필수 기재사항마다 처리방침의 절을 맞춘 대조표를 만든다. 대조표는 PR 설명에 적는다. 빠진 필수 항목은 본문에 더한다. 해당 없는 항목(가명정보, 민감정보 공개 가능성, 생성형 AI 등)은 대조표에 "해당 없음"과 이유를 적는다.
9. **문서**
   - `docs/decisions/0044-age-confirmation-and-privacy-policy.md`(새 파일, `_template.md`): 설계 1·2·4. `README.md` 목록 표에 더한다.
   - `docs/product/requirements.md`의 `REQ-ACCOUNT-01` P0에 두 줄을 더한다.
     - 만 14세 이상인지 확인받아야 인증번호를 받고 가입할 수 있다. 확인하지 않은 요청은 400 항목 오류이고 메일을 보내지 않으며 계정을 만들지 않는다.
     - 개인정보 처리방침을 로그인 없이 볼 수 있게 공개하고, 모든 화면의 푸터와 가입 화면에서 연결한다. 처리방침의 항목·보유 기간·위탁은 실제 동작·운영 설정과 같다.
   - `docs/product/development.md`
     - `DEC-AUTH(로그인·탈퇴)` 결정 칸에 "만 14세 미만은 가입할 수 없다. 인증번호 요청과 가입에서 만 14세 이상인지 확인받는다(2026-11-03, #111)"를 더한다.
     - 근거 칸에 "법정대리인 동의 절차를 만드는 대신 가입을 막는다. 인증번호 요청에서 막아 만 14세 미만의 이메일을 처리하지 않는다"를 더한다.
     - 기록 문단에 "DEC-AUTH에 만 14세 미만 가입 제한을 2026-11-03에 더했다. 기록은 #111에서 했다."를 더한다.
   - `docs/design/README.md`
     - 공통: 푸터에 처리방침 링크(굵게, 강조색)를 둔다. 시안의 이용약관·저작권정책은 문서가 없어 두지 않는다.
     - 따르지 않음 표: 시안의 '[필수] 개인정보 수집·이용 동의' 체크박스. 이메일·비밀번호는 계약 이행으로 처리하므로 두지 않고, 만 14세 확인과 처리방침 안내로 대신한다.
     - 시안에 없는 P0 화면 표에 처리방침 행을 더한다. F2 '더함'에 만 14세 확인을 더한다.

## 완료 조건 매핑

- [ ] 모든 화면의 푸터에 '개인정보 처리방침' 링크가 다른 링크와 구분되게 보이고, 가입 화면에서도 열 수 있다. → 6·7단계. 로컬 화면에서 확인한다(프론트 테스트 도구가 없다).
- [ ] 처리방침에 작업 내용의 항목이 모두 있다. 적힌 항목, 보유 기간, 위탁 업체가 실제 동작·운영 설정과 같다(인증 기록 24시간, 로그인 시도 기록 15분, 탈퇴 즉시 삭제, Resend, Cloudflare). → 6단계 본문, 8단계 대조표. `확인된 사실과 제약`의 실제 처리 목록과 한 줄씩 대조해 PR 설명에 적는다.
- [ ] 만 14세 이상 확인 없이 가입을 요청하면 화면과 API 모두에서 가입되지 않는다. → 설계 1, 1·3·5단계. `EmailVerificationApiTest`, `SignUpApiTest`, 로컬 화면 확인.

## 검증 명령

- `backend/`에서 `./gradlew test`
- `frontend/`에서 `npm run lint`, `npm run typecheck`, `VITE_API_BASE_URL=http://localhost:8080 npm run build`
- 로컬 확인: 백엔드(`SPRING_PROFILES_ACTIVE=local ./gradlew bootRun`)와 프론트(`npm run dev`)를 띄우고 다음을 확인한다.
  - 홈·혜택 목록·404·로그인 화면의 푸터에 굵은 '개인정보 처리방침' 링크가 보이고, 누르면 로그아웃 상태로 `/privacy`가 열린다.
  - `/signup`에서 체크 없이 '인증번호 받기'를 누르면 체크박스 아래에 오류가 보이고 백엔드 로그에 `[메일 미발송]`이 없다. 체크하면 인증번호 단계로 간다. 처리방침 링크는 새 창으로 열린다.
  - 인증번호 다시 받기와 가입이 체크한 상태 그대로 된다.
  - 아래 요청이 400이고 `errors[0].field`가 `ageConfirmed`다.
    ```sh
    curl -si -X POST localhost:8080/api/auth/email-verification -H 'Content-Type: application/json' \
      -d '{"email":"me@example.com"}'
    ```

## 제안

플랜 범위 밖이라 진행하지 않는다.

- 만료·폐기된 리프레시 토큰과 사용·만료된 재설정 토큰 행을 파기하는 작업을 별도 이슈로 둔다. 지금은 탈퇴할 때까지 남아 처리방침에도 "탈퇴할 때까지"로 적는다.
- 운영 MySQL의 바이너리 로그 보관 기간(기본 30일)을 줄이거나 끄는 것을 별도 이슈로 검토한다. 백업·복제에 쓰지 않으면 삭제한 개인정보가 30일 남을 이유가 없다. 지금은 처리방침의 파기 절에 "최대 30일"로 적는다.

## 결정 필요

없음
