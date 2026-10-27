# 0032. 계정 메일 Resend 비동기 발송

- 상태: 채택
- 날짜: 2026-10-27
- 출처: #78, [플랜](../plans/78-resend-account-mail.md)
- 대체: [0020](0020-account-mail-sender.md)

## 배경

0020은 계정 메일을 `AccountMailSender` 뒤에 두고, 실제 발송 서비스가 정해질 때까지 커밋 뒤 동기로 보내며 local 로그 구현과 테스트 가짜만 두었다. 0020은 실제 발송을 붙일 때 발송을 비동기로 바꿔, 비밀번호 재설정 요청의 응답 시간으로 가입 여부가 드러나지 않게 하라고 남겼다. #78에서 `DEC-OPS`의 메일 발송 서비스를 Resend로 정했다. 그래서 Resend 구현을 더하고, 발송 시점을 커밋 뒤 비동기로 바꾸고, 실패와 설정 누락을 어떻게 다룰지 다시 정한다.

## 결정

계정 메일은 0020의 경계(종류별 메서드, 커밋 뒤 발송, 실패를 삼킴, `auth.mail.sender`로 구현 선택)를 유지한다. 발송은 커밋 뒤 계정 메일 전용 가상 스레드 실행기에서 비동기로 하고, 실제 발송은 Resend HTTP API를 재시도 없이 부른다.

- 인터페이스: `sendSignupCode(String email, String code, Duration validFor)`, `sendPasswordReset(String email, String resetUrl, Duration validFor)`. 제목과 본문은 구현이 정한다(0020과 같음).
- 발송 시점: 서비스가 `SignupCodeIssued`·`PasswordResetIssued`를 발행하고, `AccountMailListener`가 `@TransactionalEventListener(phase = AFTER_COMMIT)` + `@Async(AccountMailConfig.EXECUTOR)`로 보낸다. 롤백된 요청의 번호나 링크는 나가지 않고, 요청의 응답은 발송을 기다리지 않는다.
- 실행기 `accountMailExecutor`: 가상 스레드를 쓰는 `SimpleAsyncTaskExecutor`(스레드 이름 `account-mail-`). 큐가 없고, 종료할 때 진행 중인 발송을 10초까지 기다린다. `auth.mail.async=false`면 `SyncTaskExecutor`로 호출한 스레드에서 보낸다. 테스트만 `false`를 쓴다.
- 기본 실행기 유지: `spring.task.execution.mode=force`. 계정 메일 실행기가 `Executor` 빈이라, 지정하지 않으면 Boot의 `applicationTaskExecutor`가 만들어지지 않는다.
- 발송 실패: 리스너가 예외를 잡아 행 ID만 오류 로그로 남기고(이메일·번호·링크 없음) 응답을 바꾸지 않는다. 재시도하지 않으며, 사용자는 발송 간격이 지난 뒤 다시 받는다.
- 구현 선택(`auth.mail.sender`, `application.yaml`에서는 `${MAIL_SENDER:}`)
  - `resend`: `ResendAccountMailSender`. `POST <base-url>/emails`에 `Authorization: Bearer <RESEND_API_KEY>`, `User-Agent: gov-benefit-helper`, 본문 `from`·`to`·`subject`·`text`(텍스트만)를 보낸다. `RESEND_API_KEY`나 `RESEND_FROM`이 비어 있으면 빈을 만들 때 기동에 실패한다.
  - `logging`: `LoggingAccountMailSender`. 메일을 보내지 않고 번호와 링크를 INFO 로그로 남긴다. `application-local.yaml`에서만 켠다.
  - 지정하지 않거나 비움: 구현 빈이 없어 기동에 실패한다.
  - 테스트: 공통 설정(`src/test/resources/config/application.yaml`)이 `auth.mail.sender=test`, `auth.mail.async=false`를 둔다. 테스트 소스의 `RecordingAccountMailSender`(`@Component`, `@ConditionalOnProperty(havingValue = "test")`)가 보낸 메일을 기록한다. Resend를 검증하는 테스트는 `resend`로 덮고 로컬 stub 서버(`ResendStubServer`)로 보낸다. `local` 프로필을 켜는 테스트는 `auth.mail.sender`를 `logging`이 아닌 값으로 덮는다.
- 로그에 비밀값을 남기지 않는다: Resend 오류 응답의 본문은 읽지 않고, 실패 예외(`AccountMailSendException`)에는 상태 코드나 연결 실패 원인의 클래스 이름만 담는다. 이벤트 record, 요청 DTO, `ResendEmailRequest`, `ResendProperties`의 `toString`은 번호·링크·토큰·주소·키를 가린다.

## 근거

- **리스너에 `@Async`**: 비동기가 발송 구현과 상관없이 적용되고, 커밋 뒤 발송과 실패 처리를 한 곳에 둔다. Resend 구현 안에서만 비동기로 하면 테스트를 고치지 않아도 되지만, 구현을 하나 더할 때마다 비동기를 다시 챙겨야 하고 테스트가 검증하는 경로가 운영과 달라진다.
- **전용 가상 스레드 실행기**: 큐가 없어 가득 찼을 때 거절하거나 요청 스레드에서 실행하는 경우가 없다. 크기를 제한한 `ThreadPoolTaskExecutor`는 `CallerRuns`면 응답이 다시 발송을 기다리고, 거절하면 `afterCommit`에서 던진 예외가 응답 쪽으로 번진다. 전역 `spring.threads.virtual.enabled`는 Tomcat 요청 스레드와 수집 스케줄러까지 바꾼다.
- **아웃박스를 두지 않음**: 재시작해도 메일이 남지만, 인증번호와 재설정 토큰을 평문으로 DB에 둬야 한다(0019는 해시만 저장한다). 메일은 사용자가 발송 간격 뒤 다시 받으면 되므로, 프로세스가 강제 종료될 때 아직 보내지 않은 메일을 잃는 한계를 받아들인다.
- **테스트는 동기 실행기**: 기존 테스트 17개 파일, 39곳이 요청 직후 가짜 발송의 기록을 읽는다. 모두 Awaitility 대기로 바꾸면 의존성과 수정이 늘고 테스트가 느려진다. 비동기는 `AccountMailAsyncTest`가 stub이 응답을 붙잡은 동안 재설정 요청이 먼저 끝나는지로 확인한다.
- **재시도 없음**: 응답만 잃고 실제로는 발송된 경우 다시 보내면 메일이 두 번 간다. 막으려면 `Idempotency-Key`가 필요하다. 실제 장애 빈도를 본 뒤 정한다.
- **`RestClient`로 직접 호출**: `Gov24Client`와 같은 방식(JDK `HttpClient`, 시간 제한 직접 지정)이고 의존성이 늘지 않는다. 공식 Java SDK는 자체 HTTP 클라이언트를 가져오고, stub으로 보낼 base URL 변경과 예외 메시지의 내용을 통제하기 어렵다. SMTP는 HTTP API를 stub으로 검증하려던 방향과 맞지 않는다.
- **오류 본문을 읽지 않음**: `RestClient`의 기본 오류 예외는 메시지에 응답 본문을 담는다. Resend의 검증 오류 본문에 보낸 주소가 들어갈 수 있고, 리스너는 예외를 그대로 로그에 남긴다.
- **키·발신 주소가 없으면 기동 실패**: 발신 주소가 없으면 모든 발송이 Resend의 검증 오류로 실패한다. `@Validated`로 검사하면 `logging`이나 테스트에서도 바인딩할 때 검증이 걸리므로 `resend` 빈을 만들 때만 검사한다(`JwtConfig`와 같은 방식).
- **기본값은 여전히 기동 실패**: 기본값을 `resend`로 두면 배포 설정이 하나 줄지만, 모든 `@SpringBootTest`와 `collect` 단독 실행에 Resend 키가 필요해진다. `${MAIL_SENDER:}`는 `.env`(프로퍼티 파일로 읽어 환경 변수식 이름이 바뀌지 않는다)로도 고를 수 있게 둔 자리표시자다.
- **Resend API에서 확인한 사실**(2026-10-27, 공식 문서): `POST https://api.resend.com/emails`, `Authorization: Bearer`, `from`·`to`·`subject` 필수, 성공하면 `{"id": ...}`. 모든 요청에 `User-Agent`가 있어야 하며 없으면 403이다. 기본 제한은 팀당 초당 10건이고 넘으면 429다.

## 적용 위치

- `backend/src/main/java/kr/govbenefit/account/mail/AccountMailSender.java`, `AccountMailListener.java`, `LoggingAccountMailSender.java`, `ResendAccountMailSender.java`, `ResendEmailRequest.java`, `AccountMailSendException.java`, `SignupCodeIssued.java`, `PasswordResetIssued.java`
- `backend/src/main/java/kr/govbenefit/account/config/AccountMailConfig.java`(구현 선택, 실행기), `ResendProperties.java`
- `backend/src/main/resources/application.yaml`(`auth.mail.sender`·`async`·`resend`, `spring.task.execution.mode`), `application-local.yaml`(`auth.mail.sender: logging`)
- `backend/.env.example`(`MAIL_SENDER`, `RESEND_API_KEY`, `RESEND_FROM`)
- `backend/src/test/resources/config/application.yaml`(`auth.mail.sender: test`, `auth.mail.async: false`)
- `backend/src/test/java/kr/govbenefit/account/mail/RecordingAccountMailSender.java`, `ResendStubServer.java`, `ResendAccountMailSenderTest.java`, `ResendMailDeliveryTest.java`, `AccountMailAsyncTest.java`, `AccountMailRollbackTest.java`
- `backend/src/test/java/kr/govbenefit/account/config/AccountMailConfigTest.java`
- `backend/src/test/java/kr/govbenefit/common/api/ApiDocsTest.java`: `local` 프로필에서 `auth.mail.sender=test`
- 발신 도메인 인증 절차: [`docs/ops/mail-domain.md`](../ops/mail-domain.md)

## 영향

- 새 메일 발송 구현은 `AccountMailSender`를 구현하고 `auth.mail.sender`에 새 값을 더한다. 비동기는 리스너가 하므로 구현은 동기로 보내고 실패하면 예외를 던진다. 예외 메시지에 수신 주소·본문·응답 본문을 담지 않는다.
- 발송 실패를 재시도하게 되면 `Idempotency-Key`와 함께 이 결정을 대체한다.
- 알림 메일(`REQ-NOTIFY-*`)이 생기면 이 경계를 넓힐지, 알림용 경계를 따로 둘지 정한다.
- `local` 프로필을 켜는 새 `@SpringBootTest`는 `auth.mail.sender`를 덮어써야 한다.
- 다른 비동기 작업을 `@Async`로 더할 때는 실행기 이름을 지정한다. 지정하지 않으면 Boot의 `applicationTaskExecutor`를 쓴다.
