# 0020. 계정 메일 발송 구현 경계

- 상태: 대체됨 → [0032](0032-account-mail-resend.md)
- 날짜: 2026-10-16
- 출처: #47, [플랜](../plans/47-email-verification-password-reset.md)

## 배경

#47에서 가입 인증번호와 비밀번호 재설정 링크를 메일로 보내게 됐다. 실제 발송 서비스는 `DEC-OPS`에서 고르므로 아직 정하지 않았고, `REQ-ACCOUNT-01`은 메일 발송을 교체할 수 있는 구현 뒤에 두고 가짜 발송으로 검증하라고 한다. 발송 인터페이스의 모양, 발송 시점, 실제 구현이 없는 동안 프로필별로 무엇을 쓸지를 정해야 했다. 실제 발송 구현과 이후의 메일 기능이 이 결정을 따른다.

## 결정

계정 메일은 종류마다 메서드를 둔 `AccountMailSender` 뒤에 두고, 서비스는 이벤트만 발행해 트랜잭션이 커밋된 뒤 동기로 보낸다. 구현은 `auth.mail.sender`로 고르며, 지정하지 않으면 구현이 없어 기동에 실패한다.

- 인터페이스: `sendSignupCode(String email, String code, Duration validFor)`, `sendPasswordReset(String email, String resetUrl, Duration validFor)`. 제목과 본문은 구현이 정한다.
- 발송 시점: 서비스가 `SignupCodeIssued`·`PasswordResetIssued`를 발행하고, `AccountMailListener`가 `@TransactionalEventListener(phase = AFTER_COMMIT)`에서 보낸다. 롤백된 요청의 번호나 링크는 나가지 않는다.
- 발송 실패: 리스너가 예외를 잡아 행 ID만 오류 로그로 남기고(이메일·번호·링크 없음) 응답을 바꾸지 않는다. 사용자는 발송 간격이 지난 뒤 다시 받는다.
- 구현 선택:
  - `auth.mail.sender=logging`: `LoggingAccountMailSender`. 메일을 보내지 않고 번호와 링크를 INFO 로그로 남긴다. `application-local.yaml`에서만 켠다.
  - 지정하지 않음: 구현 빈이 없어 기동에 실패한다. `local` 없이 실행하는 프로필(예: `collect` 단독)도 같다.
  - 테스트: 테스트 소스의 `@Component` `RecordingAccountMailSender`가 컴포넌트 스캔으로 모든 `@SpringBootTest`에 들어가 보낸 메일을 기록한다. `local` 프로필을 켜는 테스트는 `auth.mail.sender`를 `logging`이 아닌 값으로 덮어 두 구현이 함께 등록되지 않게 한다.
- 이벤트 record와 요청 DTO의 `toString`은 번호·링크·토큰을 가린다.

## 근거

- **종류별 메서드**: 가짜 구현이 번호와 링크를 구조화해 기록하므로 테스트가 본문을 파싱하지 않는다. 실제 발송이 SMTP든 HTTP API든 상관없다. 범용 `send(to, subject, body)`는 메일 문구가 서비스에 흩어지고 테스트가 본문에서 번호를 뽑아야 한다. `JavaMailSender` + GreenMail은 SMTP 경로까지 검증하지만, 발송 서비스가 HTTP API 방식이면 쓸모가 없어진다.
- **커밋 후 발송**: 트랜잭션 안에서 보내면 롤백돼도 메일이 이미 나간다. 커밋 전 발송 실패로 롤백하는 방식은 메일 서비스 장애가 가입 인증 요청을 막는다.
- **실패를 삼킴**: 커밋 뒤라 실패로 응답해도 행은 남아 발송 간격에 걸린다. 비밀번호 재설정은 가입된 이메일에서만 발송하므로, 실패를 응답에 드러내면 가입 여부가 드러난다.
- **동기 발송(2026-10-16 사용자 결정)**: 지금은 가짜·로그 구현뿐이라 응답 시간에 영향이 없고, 스레드 풀과 테스트의 발송 대기가 필요 없다. 한계: 실제 발송이 느리면 재설정 요청의 응답 시간으로 가입 여부가 드러날 수 있다.
- **기본 프로필은 기동 실패(2026-10-16 사용자 결정)**: 실제 구현이 들어올 때까지 운영 설정에서 인증번호가 로그로 새지 않게 한다. 로그 구현을 기본으로 두면 배포 설정 실수 하나로 번호가 운영 로그에 남는다.
- **테스트 가짜를 컴포넌트 스캔으로**: `@SpringBootTest` 16개에 import를 더하지 않는다. `@TestConfiguration`은 스캔에서 빠지므로 일반 `@Component`로 둔다.

## 적용 위치

- `backend/src/main/java/kr/govbenefit/account/mail/AccountMailSender.java`, `LoggingAccountMailSender.java`, `AccountMailListener.java`, `SignupCodeIssued.java`, `PasswordResetIssued.java`
- `backend/src/main/java/kr/govbenefit/account/config/AccountMailConfig.java`: `auth.mail.sender`로 구현 선택
- `backend/src/main/resources/application.yaml`(`auth.mail.sender` 주석), `application-local.yaml`(`auth.mail.sender: logging`)
- `backend/src/test/java/kr/govbenefit/account/mail/RecordingAccountMailSender.java`
- `backend/src/test/java/kr/govbenefit/account/config/AccountMailConfigTest.java`
- `backend/src/test/java/kr/govbenefit/common/api/ApiDocsTest.java`: `local` 프로필에서 `auth.mail.sender=test`

## 영향

- 실제 발송 이슈(`DEC-OPS`)는 `AccountMailSender`를 구현하고 `auth.mail.sender`에 새 값을 더한다. 그때 발송을 비동기로 바꿔 재설정 요청의 응답 시간 차이를 없애고, 이 결정을 대체한다.
- 알림 메일(`REQ-NOTIFY-*`)이 생기면 이 경계를 넓힐지, 알림용 경계를 따로 둘지 정한다.
- `local` 프로필을 켜는 새 `@SpringBootTest`는 `auth.mail.sender`를 덮어써야 한다.
