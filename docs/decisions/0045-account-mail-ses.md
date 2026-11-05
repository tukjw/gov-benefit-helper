# 0045. 계정 메일 Amazon SES 비동기 발송

- 상태: 채택
- 날짜: 2026-11-05
- 출처: #127, [플랜](../plans/127-switch-mail-to-ses.md)
- 대체: [0032](0032-account-mail-resend.md)

## 배경

0032는 계정 메일을 커밋 뒤 비동기로 Resend HTTP API에 보내기로 했다. 새 혜택 알림(`DEC-NOTIFY`)도 같은 서비스로 보내기로 했는데, 알림은 매일 08:00에 알림을 켠 사용자 수만큼 한꺼번에 나간다. Resend 무료 플랜의 하루 100건을 계정 메일과 알림이 나눠 쓰면, 알림 대상이 늘어난 날에는 가입 인증번호와 비밀번호 재설정 메일이 나가지 못할 수 있다. 그래서 알림 발송을 만들기 전에 발송 서비스를 Amazon SES로 바꾼다. 보낸 만큼 요금을 내고, 하루 발송 할당량과 초당 속도를 계정 단위로 늘릴 수 있으며, 반송·수신 거부 신고 주소를 자동으로 막는 계정 수준 억제 목록이 있다. 발송 구조(커밋 뒤 비동기, 실패를 삼킴, 재시도 없음)는 그대로 두고 발송 구현과 그에 딸린 결정만 바꾼다.

## 결정

계정 메일은 0032의 경계를 그대로 유지하고, 실제 발송은 AWS SDK for Java 2.x의 SES API v2 클라이언트(`SendEmail`)로 재시도 없이 한다.

- 유지(0032와 같음)
  - 인터페이스: `sendSignupCode(String email, String code, Duration validFor)`, `sendPasswordReset(String email, String resetUrl, Duration validFor)`. 제목과 본문은 구현이 정하고, 본문은 텍스트만 보낸다.
  - 발송 시점: `AccountMailListener`가 `@TransactionalEventListener(phase = AFTER_COMMIT)` + `@Async(AccountMailConfig.EXECUTOR)`로 보낸다. 롤백된 요청의 번호나 링크는 나가지 않고, 응답은 발송을 기다리지 않는다.
  - 실행기 `accountMailExecutor`(가상 스레드, 종료할 때 10초까지 대기), `auth.mail.async=false`면 동기, `spring.task.execution.mode=force`.
  - 발송 실패: 리스너가 행 ID만 오류 로그로 남기고 응답을 바꾸지 않는다. 재시도하지 않는다.
  - 구현 선택 `auth.mail.sender`(`${MAIL_SENDER:}`): 비우면 기동 실패, `logging`은 local 전용, 테스트는 `test`와 `RecordingAccountMailSender`.
- 바뀜
  - `ses`: `SesAccountMailSender`. `SesV2Client`를 다음처럼 만든다.
    - 자격 증명: `StaticCredentialsProvider`(`SES_ACCESS_KEY_ID`, `SES_SECRET_ACCESS_KEY`). 리전: `Region.of(SES_REGION)`. SDK 기본 자격 증명·리전 체인은 쓰지 않는다.
    - HTTP 클라이언트: `url-connection-client`(연결 5초, 소켓 10초). `apache5-client`, `netty-nio-client`는 의존성에서 뺀다.
    - 재시도: `AwsRetryStrategy.doNotRetry()`.
    - `auth.mail.ses.endpoint`가 있으면 `endpointOverride`. 테스트만 쓴다.
    - 요청: `FromEmailAddress`(`SES_FROM`), `Destination.ToAddresses`(수신자 하나), `Content.Simple`의 제목·텍스트 본문(UTF-8).
    - `close()`에서 클라이언트를 닫는다. Spring이 빈을 없앨 때 부른다.
  - `SES_ACCESS_KEY_ID`, `SES_SECRET_ACCESS_KEY`, `SES_REGION`, `SES_FROM` 중 하나라도 비어 있으면 `ses` 빈을 만들 때 기동에 실패한다. 메시지에는 빠진 변수 이름만 담는다.
  - 실패 예외: 서비스 오류는 `SES 응답 상태 <HTTP 상태> <오류 코드>`, 연결 실패는 `SES 연결 실패: <가장 안쪽 원인의 클래스 이름>`. SES 오류 메시지와 원래 예외는 담지 않는다.
  - `SesProperties.toString`은 접근 키 ID와 비밀 키를 가린다.
  - 반송·신고 알림은 SES의 이메일 피드백 전달로 발신 주소에 온다. 코드는 알림 주소를 따로 지정하지 않는다.
  - 테스트는 `SesStubServer`(로컬 HTTP stub)로 보낸다. SES를 검증하는 테스트는 `auth.mail.sender=ses`와 `auth.mail.ses.endpoint`로 덮는다.

## 근거

- **SDK를 쓴다**: SES는 SigV4 서명이 필요하다. 직접 구현하면 정규 요청·해시·서명 범위를 맞추고 검증해야 하며, 틀리면 운영에서만 드러난다. 0032가 SDK를 쓰지 않은 이유(stub으로 보낼 주소 변경, 예외 메시지 통제)는 `endpointOverride`와 예외를 바꿔 던지는 방식으로 해결된다. SMTP는 HTTP stub으로 요청 내용을 검증하는 방식과 맞지 않는다.
- **HTTP 클라이언트를 하나로 줄인다**: SDK 기본 의존성은 동기 Apache 클라이언트와 비동기 Netty 클라이언트를 함께 가져온다. 동기 호출 하나에는 JDK 기반 클라이언트로 충분하다.
- **재시도를 끈다**: SDK 기본 재시도는 429·5xx·연결 오류에서 최대 3\~4번 시도한다. 응답만 잃고 실제로는 발송된 경우 같은 메일이 여러 번 간다. 0032의 "재시도 없음"을 지키려면 꺼야 한다.
- **자격 증명·리전을 직접 지정한다**: 기본 체인은 로컬의 다른 AWS 프로필이나 `AWS_*` 환경 변수를 집어 갈 수 있고, 값이 비었을 때 기동을 막을 수 없다.
- **SES 오류 메시지를 버린다**: 샌드박스에서 인증하지 않은 수신자로 보내면 오류 메시지에 수신 주소가 들어간다. 리스너는 예외를 그대로 로그에 남긴다. 오류 코드(`MessageRejected`, `TooManyRequestsException` 등)는 주소를 담지 않으며 원인을 찾는 데 충분하다.
- **확인한 사실**(2026-11-05, AWS 공식 문서와 stub 테스트)
  - `SendEmail`은 `POST /v2/email/outbound-emails`이고, 성공하면 200 `{"MessageId": ...}`, 오류는 400(`MessageRejected`, `SendingPausedException` 등)·404·429(`TooManyRequestsException`)다.
  - SDK 요청의 `Authorization`은 `AWS4-HMAC-SHA256 Credential=<키 ID>/<날짜>/<리전>/ses/aws4_request`로 시작한다.
  - 샌드박스는 리전마다 있고, 인증된 주소에만 24시간 200통, 초당 1통이다. 해제 뒤 한도는 계정의 발송 할당량과 속도를 따른다.
  - 계정 수준 억제 목록에 있는 주소로 보내면 SES는 요청을 받아들이지만 보내지 않는다. 주소는 직접 지울 때까지 남는다.
  - 이메일 피드백 전달은 기본으로 켜져 있고, 알림은 발신 주소로 간다.
  - stub이 응답을 붙잡은 채 컨텍스트를 닫으면 실행기가 발송을 기다린 뒤 클라이언트가 닫힌다.

## 적용 위치

- `backend/build.gradle`(AWS SDK BOM, `sesv2`, `url-connection-client`)
- `backend/src/main/java/kr/govbenefit/account/mail/SesAccountMailSender.java`, `AccountMailSender.java`, `AccountMailListener.java`, `LoggingAccountMailSender.java`, `AccountMailSendException.java`
- `backend/src/main/java/kr/govbenefit/account/config/AccountMailConfig.java`(구현 선택, 실행기), `SesProperties.java`
- `backend/src/main/resources/application.yaml`(`auth.mail.sender`·`async`·`ses`, `spring.task.execution.mode`), `application-local.yaml`(`auth.mail.sender: logging`)
- `backend/.env.example`, `deploy/app/app.env.example`(`MAIL_SENDER`, `SES_*`)
- `backend/src/test/resources/config/application.yaml`(`auth.mail.sender: test`, `auth.mail.async: false`)
- `backend/src/test/java/kr/govbenefit/account/mail/RecordingAccountMailSender.java`, `SesStubServer.java`, `SesAccountMailSenderTest.java`, `SesMailDeliveryTest.java`, `AccountMailAsyncTest.java`, `AccountMailRollbackTest.java`
- `backend/src/test/java/kr/govbenefit/account/config/AccountMailConfigTest.java`
- 발신 도메인 인증, IAM 사용자, 억제 목록, 샌드박스 해제 절차: [`docs/ops/mail-domain.md`](../ops/mail-domain.md)

## 영향

- 새 메일 발송 구현은 `AccountMailSender`를 구현하고 `auth.mail.sender`에 새 값을 더한다. 구현은 동기로 보내고 실패하면 예외를 던지며, 예외 메시지에 수신 주소·본문·응답 본문·키를 담지 않는다.
- AWS SDK 클라이언트를 더 쓰게 되면 재시도와 자격 증명을 이 결정처럼 코드에서 정한다.
- 새 혜택 알림 메일은 같은 SES 계정의 하루 발송 할당량과 초당 속도를 계정 메일과 나눠 쓴다. 알림을 한꺼번에 보내다 속도를 넘기면 그 시각의 계정 메일도 429로 실패한다. 알림 발송은 계정 속도 안에서 나눠 보낸다.
- 반송·신고 이벤트를 받아 처리(SNS, 이벤트 게시)하게 되거나 발송 실패를 재시도하게 되면 이 결정을 대체한다.
- `local` 프로필을 켜는 새 `@SpringBootTest`는 `auth.mail.sender`를 덮어써야 한다.
