# 0044. 만 14세 이상 확인과 개인정보 처리방침 유지

- 상태: 채택
- 날짜: 2026-11-04
- 출처: #111, [플랜](../plans/111-privacy-policy.md)

## 배경

서비스가 `govbenefit.kr`에 공개되어 누구나 가입할 수 있지만 개인정보 처리방침이 없었다. 개인정보 보호법 제30조는 처리방침을 정해 공개하도록 한다. 만 14세 미만의 개인정보를 처리하려면 법정대리인의 동의가 필요하다(제22조의2). 그런데 가입 과정에는 나이 확인이 없었다. #111에서 법정대리인 동의 절차를 만드는 대신 만 14세 미만은 가입하지 않게 했다. 처리방침에는 실제 처리 항목·보유 기간·위탁을 적으므로, 처리가 바뀌는 이후 이슈도 처리방침을 함께 고쳐야 한다.

## 결정

인증번호 요청과 가입 요청은 만 14세 이상 확인(`ageConfirmed: true`)이 있어야 받는다. 처리방침은 프론트 화면 `/privacy`에 두고, 개인정보의 처리 항목·목적·보유 기간·위탁·국외 이전·자동 수집 장치가 바뀌는 변경은 같은 PR에서 처리방침을 고친다.

- 요청: [0041](0041-email-verification-retention.md)의 `POST /api/auth/email-verification`과 `POST /api/auth/signup` 본문에 `ageConfirmed`(Boolean)를 더한다. 0041의 나머지 흐름·저장·판정 규칙은 그대로다.
- 검증: `@NotNull`과 `@AssertTrue`로 `true`만 받는다. `false`, `null`, 빠짐은 400 `INVALID_INPUT`이고 `errors[].field`는 `ageConfirmed`, `reason`은 `SignUpRequest.AGE_CONFIRMATION_MESSAGE`다. 검증은 서비스 호출 전이므로 메일이 나가지 않고, 인증 기록이 생기지 않으며, 가입 티켓도 쓰이지 않는다.
- 저장: 확인 결과는 저장하지 않는다. 확인 없이는 계정이 생기지 않으므로 `user_account.created_at`이 확인 시각이다.
- 화면: 가입의 이메일 단계에서 체크박스로 받고, 다음 단계에는 숨은 입력으로 넘긴다.
- 처리방침: 본문은 `frontend/src/pages/privacy/PrivacyPolicyPage.tsx`에 있다. 수치마다 근거 설정을 주석으로 단다. 푸터는 모든 화면에서 이 화면으로 연결한다. 내용을 바꾸면 시행일을 고치고 이전 내용과 시행일을 '처리방침의 변경' 절에 남긴다.

## 근거

- **인증번호 요청에서도 확인**: 가입 요청에서만 막으면, 만 14세 미만의 이메일이 메일 발송과 인증 기록(24시간)으로 처리된다. 가입 요청에서도 막아야 API를 직접 부르는 경우까지 확인 없이 가입되지 않는다(2026-11-03 사용자 결정). 인증번호 확인(`/confirm`)에는 두지 않는다. 앞뒤 단계에서 이미 확인한다.
- **법정대리인 동의 대신 가입 제한**: 동의 절차는 법정대리인 확인 수단과 기록이 필요하다. 이 서비스는 만 14세 미만을 주 대상으로 하지 않는다.
- **처리방침을 화면 컴포넌트에 둠**: `docs/`의 마크다운을 렌더링하면 파서 의존성이 생기고, 정적 SPA가 본문을 따로 불러와야 한다. 화면이 하나뿐이라 컴포넌트로 충분하다.
- **같은 PR에서 처리방침 수정**: 처리방침은 실제 동작과 같아야 한다(제30조). 나중에 몰아서 고치면 그 사이 공개된 내용이 사실과 다르다.
- 제외한 대안: 가입 API에서만 확인. 단순하지만 만 14세 미만의 이메일을 인증 단계에서 처리한다.

## 적용 위치

- `backend/src/main/java/kr/govbenefit/account/dto/SignUpRequest.java`, `EmailVerificationRequest.java`
- `frontend/src/pages/account/signup/SignupPage.tsx`, `signupRoute.ts`, `frontend/src/lib/api/auth.ts`
- `frontend/src/pages/privacy/PrivacyPolicyPage.tsx`, `frontend/src/components/layout/SiteFooter.tsx`
- `REQ-ACCOUNT-01`, `DEC-AUTH(로그인·탈퇴)`

## 영향

- 가입 API를 부르는 클라이언트(이후 네이티브 앱 포함)는 `ageConfirmed`를 보내야 한다.
- 새 혜택 알림 메일 발송, 새 위탁 업체, 새 프로필 항목, 보유 기간·파기 주기 변경, 분석 도구나 쿠키 추가처럼 개인정보 처리가 바뀌는 이슈는 완료 조건에 처리방침 수정을 넣는다.
