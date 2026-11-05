status: done

# 구현 계획 — #127 메일 발송 서비스를 Resend에서 Amazon SES로 전환

브랜치: `improve/127-switch-mail-to-ses` (develop `2bb2bc2` 기준)

## 목표 및 범위

- 목표: 가입 인증번호와 비밀번호 재설정 메일을 Amazon SES(서울 리전) API로 보낸다. 후속 이슈의 새 혜택 알림이 하루 발송 한도를 계정 메일과 나눠 쓰지 않도록, 알림 발송을 만들기 전에 발송 서비스를 바꾼다.
- 포함:
  - `SesAccountMailSender`, `SesProperties`, 설정 `auth.mail.sender=ses`와 환경 변수 `SES_ACCESS_KEY_ID`·`SES_SECRET_ACCESS_KEY`·`SES_REGION`·`SES_FROM`.
  - Resend 구현·설정·테스트 삭제(`ResendAccountMailSender`, `ResendEmailRequest`, `ResendProperties`, `ResendStubServer`와 Resend 테스트).
  - SES stub 서버와 발송·실패·재시도 없음·비동기·기동 실패 테스트.
  - 결정 문서 0045(0032 대체), `DEC-OPS(메일 발송)`·`DEC-NOTIFY(새 혜택 알림)` 수정.
  - 개인정보 처리방침의 처리 위탁·국외 이전·보유 기간·변경 이력 수정.
  - 운영 문서: SES 준비 절차(발신 도메인 인증, IAM 사용자, 샌드박스 해제, 억제 목록), Resend 정리 절차, 배포·부하 테스트 문서의 Resend 표현.
- 제외:
  - 새 혜택 알림 발송(후속 이슈).
  - 반송·수신 거부 이벤트를 받아 처리하는 기능(SNS·이벤트 대상), 전달 확인. 억제 목록은 SES가 계정 단위로 자동 관리한다.
  - 재시도와 아웃박스. 결정 0032의 판단을 그대로 둔다.
  - HTML 메일, SES 템플릿, 구성 세트(configuration set).
  - AWS 콘솔 작업과 DNS 변경. 사용자가 운영 문서를 따라 직접 한다.

## 확인된 사실과 제약

- 현재 구조(결정 0032)
  - `AccountMailListener`가 `@TransactionalEventListener(AFTER_COMMIT)` + `@Async(AccountMailConfig.EXECUTOR)`로 `AccountMailSender`를 부르고, 예외를 잡아 행 ID만 로그로 남긴다. 이 경로와 실행기는 발송 구현과 상관이 없어 바꾸지 않는다.
  - `AccountMailConfig`가 `auth.mail.sender` 값으로 구현 빈을 고른다(`logging`, `resend`). `resend` 빈 메서드 안에서 키·발신 주소가 비었으면 `IllegalStateException`으로 기동을 막는다.
  - 테스트 공통 설정은 `auth.mail.sender=test`, `auth.mail.async=false`다. `RecordingAccountMailSender`는 `test`일 때만 등록된다. `AccountMailRollbackTest`(롤백된 요청은 발송되지 않음)는 가짜 발송으로 검증하므로 바꾸지 않는다.
  - `ResendStubServer`(mockwebserver3)는 요청 기록, 오류 응답, 연결 끊기, 응답 붙잡기(`hold`/`release`/`held`)를 제공한다. SES stub도 같은 기능이 필요하다.
  - Resend를 가리키는 파일: `application.yaml`(`auth.mail.resend`), `application-local.yaml`(주석), `backend/.env.example`, `deploy/app/app.env.example`, 테스트 공통 설정(주석), `RecordingAccountMailSender`(Javadoc), `docs/ops/mail-domain.md`, `docs/ops/deploy.md` 76행, `docs/ops/load-test.md` 144행, `docs/product/development.md` 108·136·139행, `PrivacyPolicyPage.tsx`. `docs/research/`, 지난 플랜, 결정 0020·0032는 기록이라 고치지 않는다.
- 개인정보 처리방침은 `main`(v0.1.3)에 배포되어 있다. 시행일은 2026년 11월 4일이다. 본문은 "내용이 바뀌면 시행 전에 이 화면에 알리고, 이전 내용과 시행일을 함께 남깁니다"라고 약속하고, 결정 0044는 "내용을 바꾸면 시행일을 고치고 이전 내용과 시행일을 '처리방침의 변경' 절에 남긴다"고 정했다.
- Amazon SES(2026-11-05, AWS 공식 문서에서 확인)
  - SES API v2 `SendEmail`: `POST /v2/email/outbound-emails`, JSON 본문 `FromEmailAddress`, `Destination.ToAddresses`, `Content.Simple.Subject{Data,Charset}`, `Content.Simple.Body.Text{Data,Charset}`. 성공하면 200 `{"MessageId": ...}`. 오류: 400(`MessageRejected`, `MailFromDomainNotVerifiedException`, `SendingPausedException`, `AccountSuspendedException`, `BadRequestException` 등), 404, 429(`TooManyRequestsException`).
  - 서울 리전 `ap-northeast-2`, 엔드포인트 `email.ap-northeast-2.amazonaws.com`. Easy DKIM 도메인은 `dkim.amazonses.com`이다. 사용자 지정 MAIL FROM을 두지 않으면 SES의 기본 MAIL FROM(`amazonses.com`의 하위 도메인)이 반송을 받는다.
  - 샌드박스: 새 계정은 리전마다 샌드박스다. 인증된 주소·도메인과 메일박스 시뮬레이터에만 보낼 수 있고, 24시간에 200통, 초당 1통이다. 해제는 콘솔의 Account dashboard → Get set up → Request production access(메일 유형 Transactional, 웹사이트 URL, 반송·신고 처리 절차 확인)로 신청하며, AWS Support가 96시간 안에 처음 답한다. 해제 뒤에도 발신 주소·도메인은 인증되어 있어야 한다.
  - 계정 수준 억제 목록: 2019-11-25 이후 계정은 반송(하드 바운스)과 수신 거부 신고 모두 기본으로 켜져 있다. 목록의 주소로 보내면 SES가 요청을 받아들이지만 보내지 않는다(API 오류가 아니다). 주소는 직접 지울 때까지 남는다. 샌드박스에서는 억제 목록을 켤 수 있지만 `PutSuppressedDestination`과 대량 작업은 쓸 수 없다. Gmail은 신고 정보를 SES에 주지 않는다.
  - 요금: API 발송 1,000통당 0.10달러(à la carte), 최소 요금 없음.
  - 계약 주체: AWS Customer Agreement(2026-10-29 개정)에서 계정 국가가 대한민국이면 AWS 계약 당사자는 Amazon Web Services Korea LLC(서울 강남구 테헤란로 231 East Tower 12층)다.
  - Data Privacy FAQ: 고객이 콘텐츠를 저장할 리전을 고르며, AWS는 "요청한 서비스 제공에 필요한 경우"와 법령·정부 명령에 따르는 경우를 빼면 고른 리전 밖으로 콘텐츠를 옮기거나 복제하지 않는다.
  - SES가 보낸 메일 내용을 얼마나 보관하는지는 확인한 문서(데이터 보호, 개인정보 삭제)에 나오지 않는다. 계정을 닫으면 데이터는 90일 보관 뒤 삭제된다.
- AWS SDK for Java 2.x(2026-11-05 Maven Central 기준 최신 2.57.9)
  - `software.amazon.awssdk:sesv2`는 런타임 기본 HTTP 클라이언트로 `apache5-client`와 `netty-nio-client`를 가져온다. JDK `HttpURLConnection` 기반 `url-connection-client`로 바꿀 수 있다.
  - 기본 재시도: legacy 전략은 최대 4회 시도, standard는 3회다. 429·5xx·연결 오류를 다시 보낸다. `AwsRetryStrategy.doNotRetry()`로 끈다.
  - 서비스 오류 예외(`SdkServiceException`)의 메시지는 SES의 오류 메시지를 담는다. 샌드박스에서 인증되지 않은 수신자로 보내면 오류 메시지에 수신 주소가 들어간다.
- 운영 계정의 서울 리전 샌드박스 해제와 발신 도메인 인증은 끝났고, IAM 발송 전용 사용자는 아직 없다(2026-11-05 사용자 확인).
- SES API를 쓰기 시작하면 하루 발송 한도는 플랜이 아니라 계정의 발송 할당량(Sending quota, 샌드박스 해제 뒤 AWS가 정하고 늘려 달라고 요청할 수 있음)이다.

## 설계 결정

1. **AWS SDK v2 `sesv2` 클라이언트로 보내고, HTTP 클라이언트는 `url-connection-client`로 바꾼다.** 결정 문서 0045.
   - `implementation platform('software.amazon.awssdk:bom:2.57.9')`, `implementation 'software.amazon.awssdk:sesv2'`(`apache5-client`, `netty-nio-client` 제외), `implementation 'software.amazon.awssdk:url-connection-client'`.
   - `UrlConnectionHttpClient.builder().connectionTimeout(5s).socketTimeout(10s)`로 지금과 같은 시간 제한을 둔다.
   - 대안: `RestClient`로 직접 호출하고 SigV4 서명을 구현. 의존성이 늘지 않지만 서명(정규 요청, 해시, 날짜 범위)을 직접 맞추고 검증해야 하며, 틀리면 운영에서만 드러난다. 결정 0032가 SDK를 쓰지 않은 이유(base URL 변경, 예외 메시지 통제)는 SDK의 `endpointOverride`와 예외를 바꿔 던지는 방식(설계 4)으로 해결된다.
   - 대안: SMTP 인터페이스 + `JavaMailSender`. HTTP stub으로 요청 내용을 검증하는 방식과 맞지 않고 SMTP 자격 증명을 따로 만들어야 한다.
2. **SDK의 재시도를 끈다.** 결정 0032의 "재시도 없음"을 잇는다.
   - `overrideConfiguration(o -> o.retryStrategy(AwsRetryStrategy.doNotRetry()))`. 끄지 않으면 429·5xx·연결 실패에서 같은 메일을 최대 4번 보내, 완료 조건 2의 "메일은 한 번만 요청된다"를 어긴다.
3. **자격 증명·리전은 전용 환경 변수로 받아 코드에서 직접 지정한다.**
   - `StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey))`, `Region.of(region)`. SDK 기본 체인(`AWS_ACCESS_KEY_ID`, `~/.aws/credentials`, 인스턴스 메타데이터)과 기본 리전 체인은 쓰지 않는다.
   - 이유: 기본 체인은 로컬의 다른 AWS 프로필을 몰래 집어 가고, 값이 비었을 때 기동을 막는 조건(완료 조건 4)을 판단할 수 없다. 이름을 `SES_*`로 두어 이 키가 발송 전용임을 드러낸다.
   - 변수: `MAIL_SENDER=ses`, `SES_ACCESS_KEY_ID`, `SES_SECRET_ACCESS_KEY`, `SES_REGION`, `SES_FROM`. 넷 중 하나라도 비면 `ses` 빈을 만들 때 기동에 실패한다(결정 0032와 같은 방식). 메시지에는 빠진 변수 이름만 담는다.
4. **실패 예외에는 HTTP 상태와 SES 오류 코드만 담는다.** 결정 문서 0045.
   - `SdkServiceException`: `AccountMailSendException("SES 응답 상태 " + statusCode + " " + errorCode)`. `awsErrorDetails().errorMessage()`와 원래 예외는 담지 않는다(수신 주소가 들어갈 수 있다).
   - `SdkClientException`(연결·시간 초과 등): `AccountMailSendException("SES 연결 실패: " + 가장 안쪽 원인의 클래스 이름)`. 원인 예외는 넘기지 않는다.
   - `SesProperties.toString`은 접근 키 ID와 비밀 키를 `****`/`(없음)`으로 가린다. 요청 객체는 SDK의 `SendEmailRequest`라 따로 record를 두지 않는다. SDK 요청 객체는 로그에 남기지 않는다.
   - SDK의 요청 로그(`software.amazon.awssdk.request`)는 DEBUG에서만 찍히고 운영 기본은 INFO다. 이 수준을 바꾸지 않는다고 운영 문서에 적는다.
5. **테스트는 `endpointOverride`로 로컬 stub 서버에 보낸다.**
   - `auth.mail.ses.endpoint`(기본 빈 값)가 있으면 `endpointOverride(URI)`를 건다. 운영은 비워 두어 리전 엔드포인트를 쓴다.
   - stub은 요청의 `Authorization` 헤더가 `AWS4-HMAC-SHA256 Credential=<접근 키 ID>/<날짜>/ap-northeast-2/ses/aws4_request`로 시작하는지 확인한다. 실제 서명 값은 검증하지 않는다(서명은 SDK의 책임). 서명 범위의 서비스 이름 `ses`는 구현 중 실제 요청으로 확인한다.
6. **SES 클라이언트는 빈과 함께 닫는다.**
   - `SesAccountMailSender implements AutoCloseable`이고 `close()`에서 `SesV2Client`를 닫는다. Spring이 `@Bean`의 추론된 destroy 메서드(`close`)로 부른다. 종료할 때 실행기가 진행 중인 발송을 기다리는 순서(결정 0032)는 바꾸지 않는다. (구현 중 확인) 재설정 메일을 stub이 붙잡은 채 컨텍스트를 닫으면, 실행기가 발송이 끝나기를 기다린 뒤 클라이언트가 닫혀 발송이 실패하지 않았다. 확인에 쓴 임시 테스트는 남기지 않았다(컨텍스트를 직접 닫으면 테스트 프레임워크의 후처리가 실패한다).
7. **발신 도메인은 지금과 같은 `mail.govbenefit.kr`이고, MAIL FROM은 SES 기본값(`amazonses.com`의 하위 도메인)을 쓴다.** 사용자가 2026-11-05에 사용자 지정 MAIL FROM 없이 설정했다.
   - SES 도메인 인증은 Easy DKIM(CNAME 3개)이다. 수신 서버의 반송은 SES가 기본 MAIL FROM으로 받아 억제 목록에 반영하므로, 반송용 DNS 레코드를 따로 두지 않는다.
   - 반송·신고 알림은 이메일 피드백 전달(기본으로 켜짐)로 발신 주소 `SES_FROM`(`help@mail.govbenefit.kr`)에 메일로 온다. `SendEmail`에 `FeedbackForwardingEmailAddress`를 넣지 않으면 발신 주소로 가기 때문이다. 이 주소는 Cloudflare 이메일 라우팅으로 운영자 메일함에 전달된다(2026-11-05 사용자 확인). 코드는 알림 주소를 따로 지정하지 않는다.
   - 피드백 전달을 끄면 SNS나 이벤트 게시를 설정해야 하고, 아무것도 없으면 SES가 어차피 발신 주소로 보낸다. 그래서 켜 둔 채로 쓴다.
   - 한계: SPF는 `amazonses.com` 기준으로 통과하지만 발신 도메인과 맞지 않는다(SPF 정렬 실패). DMARC는 DKIM 정렬로 통과하므로 수신 확인에서 `dkim=pass`와 `dmarc=pass`를 본다. 수신 서버가 SPF 정렬을 따로 요구해 도달률이 떨어지면 사용자 지정 MAIL FROM을 더한다(Resend가 쓰는 `send.mail.govbenefit.kr`과 겹치지 않는 이름으로).
   - Resend 레코드는 Resend 키를 폐기할 때 지운다.
8. **운영 배포 순서: 샌드박스 해제 승인 → PR 머지 → 릴리스.**
   - `develop`에 머지되면 다음 릴리스에 함께 나간다. 승인 전에 릴리스되면 실제 사용자에게 메일이 가지 않으므로 PR은 승인을 받은 뒤 머지한다. 샌드박스 해제는 2026-11-05에 승인되어 이 조건은 이미 충족했다. 운영 문서 5에는 다른 리전이나 계정에서 다시 할 때를 위해 절차를 남긴다.
   - 운영 `app.env`는 배포 전에 `MAIL_SENDER=ses`와 SES 값으로 바꾼다. 비어 있으면 기동에 실패해 배포 스크립트의 상태 확인에서 멈춘다.
   - Resend 키는 운영에서 SES로 실제 메일을 받은 뒤 폐기한다. 그 전까지는 이전 버전으로 되돌릴 수 있도록 남긴다.
9. **억제 목록은 계정 기본값(반송·신고)을 확인만 하고, 탈퇴와 연동하지 않는다.**
   - 이벤트 처리가 범위 밖이라 반송·신고된 주소는 SES 억제 목록에만 남는다. 처리방침의 보유 기간에 이 사실과 삭제 요구 시 운영자가 지우는 절차를 적는다(설계 10). 탈퇴할 때 자동으로 지우는 연동은 하지 않는다. 억제 목록에 오르는 주소는 반송되거나 신고한 주소뿐이라 드물고, 지우면 다시 반송될 수 있기 때문이다.
10. **개인정보 처리방침: 수탁자는 Amazon Web Services Korea LLC이고, 국외 이전 표에서 메일 발송을 뺀다.** 2026-11-05 사용자 결정.
    - 처리 위탁: `Plus Five Five, Inc.(Resend)` 행을 `Amazon Web Services Korea LLC(Amazon SES)`로 바꾼다. 위탁 업무는 "가입 인증번호와 비밀번호 재설정 메일 발송, 반송·수신 거부 주소 관리".
    - 국외 이전: 메일 내용은 서울 리전에서 처리되고 계약 당사자가 국내 법인이다. AWS는 고른 리전 밖으로 콘텐츠를 옮기지 않는다고 밝힌다. 그래서 Resend 열을 지우고 SES 열을 더하지 않는다. Cloudflare 열과 안내 문장은 그대로 둔다.
    - 보유 기간: "위탁·국외 이전 업체가 보관하는 기간" 문단에 SES를 더한다. 발송 기록은 "위탁 목적에 필요한 기간(AWS의 보관 기준)"으로 적고(SES가 보관 기간을 공개하지 않음), 반송·수신 거부로 억제 목록에 오른 주소는 "운영자가 지울 때까지, 삭제를 요구하면 지체 없이 삭제"로 적는다. 근거와 확인 날짜를 주석으로 단다.
    - 시행일과 변경 이력은 설계 11.
    - 반송·신고 알림 메일이 거치는 Cloudflare 이메일 라우팅과 운영자 메일함은 적지 않는다(사용자 결정).
11. **처리방침 시행일은 운영 배포일로 하고, 바뀐 항목의 전후를 '처리방침의 변경' 절에 남긴다.** 2026-11-05 사용자 결정.
    - `EFFECTIVE_DATE`를 운영 배포 예정일로 바꾼다. 날짜는 PR 직전 마지막 커밋에서 정한다.
    - '처리방침의 변경' 절: "이전 처리방침: 없음(처음 공개)"을 이전 시행일(2026년 11월 4일)과 바뀐 항목(처리 위탁 수탁자, 국외 이전, 보유 기간)의 전후로 바꾼다.
    - 이번 변경은 미리 알리지 못하고 바로 시행되므로, 약속 문구를 "내용이 바뀌면 바뀐 내용과 시행일을 이 화면에 알리고, 이전 내용을 함께 남깁니다."로 고친다.
    - 대안: 시행 예정 공지를 별도 이슈로 먼저 릴리스한다. 약속은 지키지만 릴리스가 두 번 필요하고 전환이 늦어진다.

## 단계별 구현 계획

1. **플랜 상태**: 착수할 때 이 파일의 `status`를 `in-progress`로 바꾼다. 샌드박스 해제는 승인되었다. 운영 문서 2\~4(도메인 인증, IAM, 억제 목록) 중 남은 작업은 운영 배포 전에 사용자가 끝낸다(설계 8).
2. **의존성** `backend/build.gradle`
   - `implementation platform('software.amazon.awssdk:bom:2.57.9')`
   - `implementation('software.amazon.awssdk:sesv2') { exclude group: 'software.amazon.awssdk', module: 'apache5-client'; exclude group: 'software.amazon.awssdk', module: 'netty-nio-client' }`
   - `implementation 'software.amazon.awssdk:url-connection-client'`
   - `./gradlew dependencies --configuration runtimeClasspath`로 Apache·Netty 클라이언트가 없는지 확인한다.
3. **SES 설정** `kr.govbenefit.account.config`
   - `SesProperties` record(`@ConfigurationProperties("auth.mail.ses")`): `String accessKeyId`, `String secretAccessKey`, `String region`, `String from`, `URI endpoint`, `Duration connectTimeout`, `Duration readTimeout`. `toString`은 두 키를 가린다(설계 4).
   - `AccountMailConfig`
     - `@EnableConfigurationProperties(SesProperties.class)`로 바꾼다.
     - `resendAccountMailSender`를 지우고 `@Bean @ConditionalOnProperty(name = "auth.mail.sender", havingValue = "ses") AccountMailSender sesAccountMailSender(SesProperties properties)`를 둔다. `accessKeyId`·`secretAccessKey`·`region`·`from` 순으로 비었는지 검사해 `IllegalStateException("SES 접근 키 ID(SES_ACCESS_KEY_ID)가 설정되지 않았습니다.")` 형식으로 던진다.
     - 클래스 Javadoc의 결정 번호를 0045로 바꾼다.
   - `ResendProperties` 삭제. `AccountMailProperties` Javadoc의 `auth.mail.resend`를 `auth.mail.ses`/`SesProperties`로 고친다.
4. **SES 발송 구현** `kr.govbenefit.account.mail`
   - `SesAccountMailSender implements AccountMailSender, AutoCloseable`
     - 생성자: `SesV2Client.builder()`에 리전, 고정 자격 증명, `UrlConnectionHttpClient`(연결·소켓 시간 제한), `AwsRetryStrategy.doNotRetry()`, `endpoint`가 있으면 `endpointOverride`(설계 1\~3·5).
     - `sendSignupCode`, `sendPasswordReset`: 제목·본문은 `ResendAccountMailSender`와 같다(`SUBJECT_PREFIX`, 본문 문구 그대로).
     - `send(String to, String subject, String text)`: `SendEmailRequest`(`fromEmailAddress`, `destination.toAddresses(to)`, `content.simple`의 제목·텍스트 본문, `Charset` UTF-8)를 보낸다. 예외 변환은 설계 4.
     - `close()`: 클라이언트를 닫는다(설계 6).
   - `ResendAccountMailSender`, `ResendEmailRequest` 삭제.
   - `AccountMailSender`, `AccountMailListener`, `LoggingAccountMailSender`, `AccountMailSendException`, 이벤트 record의 Javadoc 결정 번호를 0045로 바꾸고, Resend를 가리키는 문구를 SES로 고친다.
5. **설정 파일**
   - `application.yaml`의 `auth.mail`: `sender` 주석의 `resend`를 `ses`로. `resend` 블록을 지우고 `ses` 블록을 둔다.
     ```yaml
     ses:
       access-key-id: ${SES_ACCESS_KEY_ID:}
       secret-access-key: ${SES_SECRET_ACCESS_KEY:}
       # 예: ap-northeast-2(서울). 샌드박스 해제는 리전마다 받는다.
       region: ${SES_REGION:}
       # 인증한 도메인의 주소. 예: 정부 혜택 도우미 <help@mail.govbenefit.kr>
       from: ${SES_FROM:}
       connect-timeout: 5s
       read-timeout: 10s
     ```
     - (구현 중 변경) `endpoint` 키는 두지 않고 주석으로만 설명한다. 빈 값을 `URI`로 바꾸는 동작에 기대지 않고, 키가 없으면 `null`이 되어 리전 엔드포인트를 쓴다.
   - `application-local.yaml`: 주석을 `AUTH_MAIL_SENDER=ses`로 고친다.
   - `src/test/resources/config/application.yaml`: 주석의 Resend를 SES로 고친다.
   - `backend/.env.example`: Resend 세 줄을 지우고 `#MAIL_SENDER=`, `#SES_ACCESS_KEY_ID=`, `#SES_SECRET_ACCESS_KEY=`, `#SES_REGION=`, `#SES_FROM=`을 빈 값으로 둔다. 주석에 발송 권한만 가진 IAM 사용자의 키이고 절차는 `docs/ops/mail-domain.md`라고 적는다.
   - `deploy/app/app.env.example`: `MAIL_SENDER=ses`, `SES_ACCESS_KEY_ID=`, `SES_SECRET_ACCESS_KEY=`(빈 값), `SES_REGION=ap-northeast-2`, `SES_FROM="정부 혜택 도우미 <help@mail.govbenefit.kr>"`. 리전과 발신 주소는 비밀이 아니고 운영 값이 하나라 #118처럼 채워 두고, 키만 비운다. 주석의 결정 번호를 0045로.
6. **SES stub 서버** `src/test/java/kr/govbenefit/account/mail/SesStubServer.java`
   - `ResendStubServer`를 바꿔 만든다. 기록: 메서드, 경로, `Authorization`, JSON 본문. `ReceivedRequest`의 `to()`는 `Destination.ToAddresses[0]`, `text()`는 `Content.Simple.Body.Text.Data`, `subject()`, `from()`, `signupCode()`, `resetUrl()`, `resetToken()`.
   - 기본 응답 200 `{"MessageId":"..."}`. `respondWithError(int status, String errorType, String message)`: 헤더 `x-amzn-ErrorType`과 본문 `{"message": ...}`로 SES 오류를 흉내 낸다. `disconnect()`, `hold()`/`release()`/`held()`/`awaitRequest()`, `reset()`, `close()`는 그대로 옮긴다.
   - `endpoint()`: stub의 기본 URL(끝 `/` 없음).
   - `ResendStubServer` 삭제.
7. **테스트**
   - `SesAccountMailSenderTest`(단위, Spring 없음) — `ResendAccountMailSenderTest`를 대체
     - `가입_인증번호를_SES_형식으로_요청한다`: `POST /v2/email/outbound-emails`, `Authorization`이 `AWS4-HMAC-SHA256 Credential=<키 ID>/`로 시작하고 `/ap-northeast-2/ses/aws4_request`를 담으며 비밀 키는 없음, `FromEmailAddress`, `ToAddresses` 하나, 제목, 본문에 번호와 "10분", `Html` 없음.
     - `재설정_링크를_SES_형식으로_요청한다`: 본문에 링크 전체와 "30분".
     - `오류_응답이면_상태와_오류_코드만_담은_예외를_던지고_다시_보내지_않는다`: 400 `MessageRejected`(메시지에 수신 주소), 429 `TooManyRequestsException`, 500 `InternalFailure` 각각 예외 메시지가 `SES 응답 상태 <상태> <오류 코드>`이고 원인이 없으며, 이메일·번호·링크·키가 없고, stub이 받은 요청이 1건이다.
     - `연결이_끊기면_원인_유형만_담은_예외를_던지고_다시_보내지_않는다`(`disconnect()`, 요청 1건), `서버에_연결하지_못하면_원인_유형만_담은_예외를_던진다`(닫은 stub 주소).
     - `설정의_toString은_접근_키를_가린다`.
   - `SesMailDeliveryTest`(`@SpringBootTest`) — `ResendMailDeliveryTest`를 대체. `auth.mail.sender=ses`, 테스트 키·리전·발신 주소, `@DynamicPropertySource`로 `auth.mail.ses.endpoint`, `OutputCaptureExtension`, `@DirtiesContext`.
     - `가입_인증번호_메일이_SES로_요청되고_그_번호로_확인된다`, `재설정_메일이_SES로_요청되고_그_링크로_재설정된다`: 수신자·발신 주소·번호·링크를 stub으로 확인하고 그 값으로 확인·재설정이 성공한다.
     - `SES가_오류를_응답해도_응답은_같고_한_번만_요청되며_로그에_민감한_값이_없다`: 400(수신 주소를 담은 메시지)·429·500에서 인증번호 요청·재설정 요청이 모두 204, 요청마다 stub 기록이 1건씩만 늘고, 로그에 실패 로그(행 ID)가 있으며 이메일·번호·링크·토큰·접근 키 ID·비밀 키가 없다.
     - `SES와_연결이_끊겨도_응답은_같고_한_번만_요청되며_로그에_민감한_값이_없다`.
   - `AccountMailAsyncTest`: `ResendStubServer`→`SesStubServer`, 설정을 `ses`로 바꾼다. 검증 내용은 그대로다.
   - `AccountMailConfigTest`
     - `resend면_Resend_구현을_쓴다`를 `ses면_SES_구현을_쓴다`로, 키·발신 주소 없음 두 테스트를 `ses인데_값이_하나라도_비면_빠진_변수_이름으로_기동에_실패한다`(`@ParameterizedTest`, 네 변수 각각)로 바꾼다.
     - SES 빈을 만든 컨텍스트가 닫힐 때 예외가 없는지 확인한다(설계 6).
   - `RecordingAccountMailSender` Javadoc의 `resend`를 `ses`로 고친다. `AccountMailRollbackTest`, `ApiDocsTest`는 그대로다.
8. **결정 문서**
   - `docs/decisions/0045-account-mail-ses.md`: 상태 채택, 출처 #127과 이 플랜, 대체 0032. 결정 0032의 경계(종류별 메서드, 커밋 뒤 비동기, 실패를 삼킴, 재시도 없음, 테스트 동기 실행기)를 그대로 옮기고, 발송 구현을 SES로 바꾼다. 설계 1\~5의 결정과 근거, SES에서 확인한 사실(API, 샌드박스, 억제 목록), 적용 위치, 영향(알림 메일은 같은 SES 계정의 발송 할당량을 쓴다, 반송·신고 이벤트 처리를 더하면 이 결정을 고친다)을 적는다.
   - `0032-account-mail-resend.md`: 상태를 `대체됨 → [0045](0045-account-mail-ses.md)`로.
   - `docs/decisions/README.md`: 0032 행 상태를 대체됨으로, 0045 행 추가.
9. **제품 결정** `docs/product/development.md`
   - 108행 부근의 기록에 "2026-11-05에 메일 발송 서비스를 Amazon SES로 바꿨다(#127)"를 더한다.
   - `DEC-OPS(메일 발송)`: 서비스를 Amazon SES(서울 리전) API로, 근거를 사용한 만큼 내는 요금(1,000통당 0.10달러)·계정 단위 발송 할당량과 속도 증설·억제 목록·HTTP API stub 검증으로, 한계를 "샌드박스 해제 전에는 인증된 주소에만 24시간 200통, 초당 1통", "해제 뒤에도 하루 발송 할당량과 초당 속도는 계정 값을 따르며 넘으면 429로 실패하고 다시 보내지 않는다", "프로세스 강제 종료 시 미발송 메일 손실"로 고친다. 링크를 결정 0045로.
   - `DEC-NOTIFY(새 혜택 알림)`: "계정 메일과 같은 Resend" → "계정 메일과 같은 Amazon SES 계정", 근거의 "Resend 연동과 발신 도메인 인증이 이미 있어(#78)"를 "SES 연동과 발신 도메인 인증이 이미 있어(#78, #127)"로, 한계를 "계정 메일과 알림이 SES 계정의 하루 발송 할당량과 초당 발송 속도를 나눠 쓴다. 알림이 한꺼번에 나갈 때 속도를 넘으면 그 시각의 계정 메일도 429로 실패할 수 있으므로, 알림 발송은 계정 속도 안에서 나눠 보낸다(후속 이슈)"로 고친다.
10. **개인정보 처리방침** `frontend/src/pages/privacy/PrivacyPolicyPage.tsx`(설계 10·11)
    - 처리 위탁 표의 Resend 행 교체.
    - 국외 이전 표: 헤더를 `["구분", "Cloudflare"]`로, Resend 칸을 지운다.
    - 보유 기간 끝 문단: SES 발송 기록과 억제 목록 주소를 적는다. 국외 이전 링크 문장은 Cloudflare에 맞게 고친다.
    - 각 항목에 근거와 확인 날짜를 주석으로 단다(AWS Customer Agreement 2026-10-29 개정, Data Privacy FAQ, SES 억제 목록·개인정보 삭제 문서, 2026-11-05 확인).
    - `EFFECTIVE_DATE`와 '처리방침의 변경' 절은 설계 11.
11. **운영 문서**
    - `docs/ops/mail-domain.md`를 SES 기준으로 다시 쓴다. 확인 날짜와 AWS 문서 링크를 맨 위에 둔다.
      1. 리전 고르기: SES 콘솔 오른쪽 위에서 아시아 태평양(서울) `ap-northeast-2`. 이후 모든 작업은 이 리전에서 한다.
      2. 발신 도메인 인증: Identities → Create identity → Domain `mail.govbenefit.kr`, Easy DKIM(RSA_2048_BIT), 사용자 지정 MAIL FROM은 쓰지 않는다(설계 7). 콘솔이 보여 주는 DKIM CNAME 3개를 DNS에 넣는다. Cloudflare 프록시는 끈다. DMARC는 DKIM 정렬로 통과한다는 점과 함께 기존 안내를 SES 기준으로 고친다. 사용자 지정 MAIL FROM이 필요해질 때를 위해 SES 문서 링크만 남긴다. 도메인의 Notifications에서 Email feedback forwarding이 Enabled인지 확인하고, 반송·신고 알림이 발신 주소로 와 Cloudflare 이메일 라우팅으로 운영자 메일함에 전달된다는 점과 그 주소의 라우팅 규칙을 지우지 말라는 점을 적는다.
      3. 발송 권한만 가진 IAM 사용자: 콘솔 로그인 없는 IAM 사용자 `govbenefit-ses-sender`, 인라인 정책 `ses:SendEmail` 하나, 리소스는 `arn:aws:ses:ap-northeast-2:<계정 ID>:identity/mail.govbenefit.kr`. 접근 키(용도: AWS 외부에서 실행되는 애플리케이션)를 만들어 바로 실행 환경에 넣는다. 키를 바꾸는 절차(새 키 발급 → 배포 → 옛 키 비활성화 → 삭제)도 적는다.
      4. 억제 목록: Configuration → Suppression list의 Account-level settings가 Enabled, 사유 Bounces and complaints인지 확인하고, 아니면 켠다. 삭제를 요구받았을 때 주소를 지우는 방법(콘솔 Remove 또는 `aws sesv2 delete-suppressed-destination`)과 대소문자를 그대로 써야 한다는 점을 적는다.
      5. 샌드박스 해제: Account dashboard → View Get set up page → Request production access. Transactional, 웹사이트 URL, 연락 주소, 계정 메일만 보내며 억제 목록으로 반송·신고를 막는다는 설명. 최대 96시간, 승인 전에는 인증한 주소로만 받아 볼 수 있다. 승인 뒤 발송 할당량과 속도를 Account dashboard에서 확인한다.
      6. 실행 환경 설정: `MAIL_SENDER=ses`와 `SES_*` 네 값. 하나라도 비면 기동에 실패한다. 로컬 확인은 `AUTH_MAIL_SENDER=ses`.
      7. 수신 확인: 기존 curl 절차를 옮기고, 받은 메일의 원본 보기에서 `dkim=pass`(도메인 `mail.govbenefit.kr`)와 `dmarc=pass`를 확인한다. `spf=pass`는 `amazonses.com` 기준이라 발신 도메인과 다르게 보이는 것이 정상이다. 실패 로그 해석: `SES 응답 상태 400 MessageRejected`(샌드박스에서 인증하지 않은 수신자, 또는 도메인 미인증), `403`(IAM 권한·키), `400 SendingPausedException`(계정 발송 일시 정지), `429 TooManyRequestsException`(초당 속도·하루 할당량 초과), `SES 연결 실패`(네트워크). 억제 목록의 주소는 오류 없이 보내지지 않으므로 콘솔의 Suppression list에서 찾는다. `software.amazon.awssdk` 로그 수준을 DEBUG로 올리지 않는다.
      8. Resend 정리: 운영에서 SES로 실제 메일을 받은 뒤 Resend API 키 삭제 → 도메인 삭제 → Resend DNS 레코드(`resend._domainkey`, `send` MX·TXT) 삭제 → 필요하면 계정 삭제. 그 전에는 키를 남겨 이전 버전으로 되돌릴 수 있게 한다.
    - `docs/ops/deploy.md` 76행: "SES 접근 키와 발신 주소는 [메일 발신 도메인 인증](mail-domain.md)에서 만든다."
    - `docs/ops/load-test.md` 144행: "SES 콘솔 Account dashboard의 발송 지표(또는 CloudWatch `AWS/SES`의 `Send`)에 측정 시간대의 발송이 없다."
12. **검증과 마무리**: 아래 검증 명령을 돌리고, `rg -i resend`로 현재 절차 문서·설정 예시·코드에 남은 곳이 없는지 본다(기록 문서는 제외). PR 직전 마지막 커밋에서 `status`를 `done`으로 바꾼다. PR 설명에 운영 배포 전 준비(운영 `app.env`, 도메인 인증, IAM 키)와 운영 수동 확인 항목을 적는다.

## 완료 조건 매핑

- [ ] SES 발송을 설정하고 실행하면 가입 인증번호 메일과 비밀번호 재설정 메일이 SES API로 요청된다. 요청 내용(수신자, 발신 주소, 인증번호, 재설정 링크)은 stub 서버로 확인한다.
  - 단계 4·6·7: `SesAccountMailSenderTest`의 형식 테스트 두 개, `SesMailDeliveryTest`의 발송·확인 테스트 두 개.
- [ ] SES가 오류(429 포함)를 응답하거나 연결에 실패해도 인증번호·재설정 요청의 응답은 성공과 같고, 메일은 한 번만 요청된다. 로그에 이메일, 인증번호, 링크, 접근 키가 남지 않는다.
  - 설계 2·4, 단계 7: `SesAccountMailSenderTest`의 오류·연결 실패 테스트(요청 1건), `SesMailDeliveryTest`의 오류·연결 끊김 테스트(204, 요청 1건, 로그 검사).
- [ ] 비밀번호 재설정 요청의 응답이 메일 발송 완료를 기다리지 않고, 롤백된 요청의 인증번호나 재설정 링크는 발송되지 않는다.
  - `AccountMailAsyncTest`(SES stub으로 교체), `AccountMailRollbackTest`(변경 없음).
- [ ] 접근 키·리전·발신 주소 중 하나라도 비어 있는 채 SES 발송을 고르면 기동에 실패한다. `local` 프로필은 지금처럼 AWS 설정 없이 로그 발송으로 기동한다.
  - 설계 3, 단계 3·7: `AccountMailConfigTest`의 매개변수 테스트, `logging이면_로그_구현을_쓴다`. `local` 프로필 기동은 수동 확인(`SPRING_PROFILES_ACTIVE=local ./gradlew bootRun`)과 `ApiDocsTest`.
- [ ] 결정 0032가 새 결정 문서로 대체되고, `DEC-OPS(메일 발송)`와 `DEC-NOTIFY(새 혜택 알림)`에 SES와 바뀐 한계가 기록된다.
  - 단계 8·9.
- [ ] 개인정보 처리방침의 처리 위탁·국외 이전·보관 기간이 SES 기준이고, 근거와 확인 날짜가 남아 있다.
  - 설계 10, 단계 10.
- [ ] 현재 절차를 설명하는 운영 문서(발신 도메인 인증, 배포 설정, 부하 테스트 확인 절차)와 설정 예시에 Resend가 남아 있지 않다.
  - 단계 5·11·12. Resend 정리 절차의 "Resend" 언급은 폐기 절차라 남는다.
- [ ] 샌드박스 해제 뒤 운영에서 실제 메일 주소로 인증번호 메일을 받고, DKIM 인증을 통과했는지 확인한다(수동).
  - 설계 8, 운영 문서 7. 사용자가 릴리스 뒤 확인한다.

## 검증 명령

```sh
cd backend && ./gradlew test
cd backend && ./gradlew dependencies --configuration runtimeClasspath | grep -E 'apache5-client|netty-nio-client|url-connection-client'
cd frontend && npm run lint && npm run typecheck && npm run build
rg -i resend backend frontend/src deploy docs/ops docs/product   # 남은 곳이 Resend 정리 절차뿐인지 확인
```

수동:

- `SPRING_PROFILES_ACTIVE=local ./gradlew bootRun`이 AWS 설정 없이 뜨고, 인증번호가 로그로 남는다.
- 샌드박스 상태에서 SES 콘솔로 인증한 주소에 `AUTH_MAIL_SENDER=ses`로 로컬 발송을 확인한다(운영 문서 7).
- 샌드박스 해제 뒤 운영에서 실제 주소로 받아 `dkim=pass`와 `dmarc=pass`를 확인한다.

## 결정 필요

없음. 아래 세 가지는 2026-11-05에 사용자가 정했다.

1. 처리방침에 SES를 국외 이전으로 적지 않는다(설계 10). 계약 당사자는 Amazon Web Services Korea LLC이고, 메일 내용은 서울 리전에서 처리된다.
2. 시행일은 운영 배포일로 한다(설계 11). '처리방침의 변경' 절에 이전 시행일과 바뀐 항목의 전후를 남기고, 약속 문구를 고친다.
3. 반송·신고 알림이 Cloudflare 이메일 라우팅을 거쳐 운영자 메일함으로 가는 경로는 처리방침에 적지 않는다. 알림은 반송되거나 신고한 주소에 대해서만 오고, 운영자가 발송 평판을 관리하려고 받는 것이다.
