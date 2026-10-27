status: in-progress

# 구현 계획 — #78 Resend로 가입 인증번호와 비밀번호 재설정 메일을 실제로 발송

브랜치: `feat/78-resend-account-mail` (develop `288af7c` 기준)

## 목표 및 범위

- 목표: 가입 인증번호와 비밀번호 재설정 링크를 Resend HTTP API로 실제 발송한다. 발송은 커밋 뒤 별도 스레드에서 해 요청 응답이 발송을 기다리지 않게 하고, 재설정 요청의 응답 시간으로 가입 여부가 드러나지 않게 한다.
- 포함:
  - `ResendAccountMailSender`와 설정 `auth.mail.sender=resend`, Resend 설정(`auth.mail.resend.*`)과 환경 변수 `RESEND_API_KEY`·`RESEND_FROM`·`MAIL_SENDER`.
  - 계정 메일 전용 비동기 실행기와 리스너의 `@Async`.
  - 테스트용 Resend stub 서버, 발송·실패·비동기·롤백·기동 실패 테스트.
  - 결정 문서 0032(0020 대체), `DEC-OPS` 메일 발송 서비스 결정 기록, 발신 도메인 인증 절차 문서.
- 제외:
  - 실패한 발송의 재시도와 발송 기록 저장(아웃박스). 사용자가 발송 간격 뒤 다시 받는다(설계 3).
  - HTML 메일과 템플릿 엔진. 본문은 텍스트만 보낸다(설계 4).
  - 알림 메일(`REQ-NOTIFY-*`)의 발송 경계. 0020의 영향 항목을 0032에 그대로 옮긴다.
  - 운영 배포 설정(O2)과 운영 프로필. 배포 환경에 환경 변수를 넣는 일은 배포 이슈에서 한다.
  - Resend 대시보드 작업과 DNS 레코드 추가. 사용자가 직접 한다.

## 확인된 사실과 제약

- 현재 발송 경로(결정 0020)
  - `EmailVerificationService.request`(62행)와 `PasswordResetService.request`(68행)가 `@Transactional` 안에서 `SignupCodeIssued`·`PasswordResetIssued`를 발행한다.
  - `AccountMailListener`가 `@TransactionalEventListener(AFTER_COMMIT)`에서 `AccountMailSender`를 동기로 부르고, `RuntimeException`을 잡아 행 ID와 예외를 `log.error`로 남긴다. 예외 객체를 함께 넘기므로 예외 메시지와 원인이 로그에 그대로 찍힌다.
  - `AccountMailConfig`는 `auth.mail.sender=logging`일 때만 `LoggingAccountMailSender`를 등록한다. 지정하지 않으면 빈이 없어 기동에 실패한다. `application-local.yaml`만 `logging`을 쓴다.
  - `AccountMailProperties`(`auth.mail`)는 발송 제한 값만 갖는 record이고 `AccountConfig`가 등록한다.
- 테스트
  - `RecordingAccountMailSender`(테스트 소스의 `@Component`)가 모든 `@SpringBootTest`에 등록된다. 17개 파일, 39곳이 요청 직후 `lastSignupCode`·`lastResetToken`·`sent(...)`로 기록을 바로 읽는다. 발송이 비동기가 되면 이 39곳이 깨진다.
  - `ApiDocsTest`는 `local` 프로필을 켜고 `auth.mail.sender=test`로 덮는다.
  - `src/test/resources/config/application.yaml`이 테스트 공통 설정이다(main 설정 위에 덮인다).
  - 롤백된 요청이 발송되지 않는지 확인하는 테스트는 아직 없다.
  - `AccountMailConfigTest`는 `ApplicationContextRunner`로 `AccountMailConfig`만 띄운다.
  - `mockwebserver3`(5.5.0)이 테스트 의존성에 있고 `Gov24StubServer`가 쓴다.
- 비밀값을 받는 기존 방식: `JwtConfig`가 빈을 만들 때 `JWT_SECRET`이 없으면 `IllegalStateException`으로 기동을 막는다. `Gov24Properties`는 `toString`에서 키를 가린다. `Gov24Client`는 JDK `HttpClient` + `JdkClientHttpRequestFactory` + `RestClient`로 시간 제한을 직접 정한다.
- `.env`는 `spring.config.import`로 프로퍼티 파일처럼 읽는다. 키가 프로퍼티 이름 그대로 들어오므로 `AUTH_MAIL_SENDER=resend`처럼 환경 변수식 이름을 써도 `auth.mail.sender`로 바뀌지 않는다. `.env`로 받으려면 `application.yaml`에 `${...}` 자리표시자가 있어야 한다.
- Resend API(2026-10-27 공식 문서에서 확인)
  - `POST https://api.resend.com/emails`, `Authorization: Bearer <API 키>`, JSON 본문 `from`(이름 포함 가능, 예: `이름 <addr@domain>`)·`to`(문자열 또는 배열)·`subject` 필수, `text`·`html` 선택. 성공하면 `{"id": "..."}`.
  - 모든 요청에 `User-Agent` 헤더가 있어야 하며, 없으면 403(오류 코드 1010)이다.
  - 상태 코드: 400·401·403·404·429(팀당 초당 10건 기본 제한)·5xx.
  - `Idempotency-Key` 헤더(선택, 24시간 유효)가 있다. 재시도를 하지 않으므로 쓰지 않는다.
- 응답: 인증번호 요청과 재설정 요청은 성공하면 204다. 재설정 요청은 가입 여부·발송 제한과 관계없이 항상 204다.

## 설계 결정

1. **비동기는 리스너에 `@Async`를 붙여 계정 메일 전용 실행기에서 한다.** 결정 문서 0032.
   - `AccountMailListener`의 두 메서드에 `@Async(AccountMailConfig.EXECUTOR)`를 더한다. `@TransactionalEventListener(AFTER_COMMIT)`은 그대로라 롤백된 요청은 계속 발송되지 않는다. 실패를 잡아 로그만 남기는 방식도 그대로다.
   - 실행기 `accountMailExecutor`: 가상 스레드를 쓰는 `SimpleAsyncTaskExecutor`(스레드 이름 `account-mail-`). 큐가 없어 가득 찼을 때 거절하거나 호출 스레드에서 실행하는 경우가 생기지 않는다. 종료할 때는 진행 중인 발송을 `setTaskTerminationTimeout`(10초)만큼 기다린다.
   - 대안: 리스너는 동기로 두고 Resend 구현 안에서만 비동기. 테스트를 고치지 않아도 되지만 비동기가 구현 하나에 묶이고, 테스트가 검증하는 경로와 운영 경로가 달라진다.
   - 대안: 트랜잭션 아웃박스. 재시작해도 메일이 남지만, 인증번호와 재설정 토큰을 평문으로 DB에 둬야 하고(0019는 해시만 저장) 테이블·스케줄러·정리 작업이 늘어난다.
   - 대안: `spring.threads.virtual.enabled`로 전역 가상 스레드. Tomcat 요청 스레드와 수집 스케줄러까지 바뀌어 이 이슈의 범위를 넘는다.
   - 대안: 크기를 제한한 `ThreadPoolTaskExecutor`. 큐가 가득 찼을 때 `CallerRuns`면 다시 응답이 발송을 기다리고, 거절하면 `afterCommit`에서 던진 예외가 응답 쪽으로 번진다.
   - 한계: 메모리에만 있으므로 프로세스가 강제 종료되면 아직 보내지 않은 메일이 사라진다. 사용자는 발송 간격 뒤 다시 받는다.
2. **테스트는 공통 설정에서 실행기를 동기로 바꾸고, 비동기는 따로 검증한다.**
   - 설정 `auth.mail.async`(기본 `true`). `false`면 `accountMailExecutor`가 `SyncTaskExecutor`라 `@Async` 메서드가 호출 스레드에서 실행된다. `src/test/resources/config/application.yaml`에 `false`를 두어 기존 39곳이 지금처럼 요청 직후 기록을 읽는다.
   - "응답이 발송을 기다리지 않는다"는 `auth.mail.async=true`인 테스트 한 클래스에서 stub이 응답을 붙잡고 있는 동안 요청이 먼저 끝나는지로 확인한다(단계 7).
   - 대안: 39곳을 Awaitility 대기로 바꾸기. 의존성이 늘고 수정이 많으며, 대기 시간만큼 테스트가 느려진다.
3. **재시도하지 않는다.** 결정 문서 0032.
   - 2xx가 아니거나 연결·읽기에 실패하면 예외를 던지고, 리스너가 잡아 오류 로그만 남긴다. 0020과 같이 사용자는 발송 간격(60초) 뒤 다시 받는다.
   - 대안: 연결 실패·5xx만 1~2회 재시도. 일시 장애를 흡수하지만, 응답만 잃고 실제로는 발송된 경우 중복 발송을 막으려면 `Idempotency-Key`가 필요하다. 실제 장애 빈도를 본 뒤 정한다.
4. **Resend는 `RestClient`로 HTTP API를 직접 부르고, 본문은 텍스트만 보낸다.** 결정 문서 0032.
   - `Gov24Client`와 같이 JDK `HttpClient`(연결 5초) + `JdkClientHttpRequestFactory`(읽기 10초)를 쓴다. 헤더는 `Authorization: Bearer <키>`, `User-Agent: gov-benefit-helper`, `Content-Type: application/json`이다.
   - 본문: `{"from": <RESEND_FROM>, "to": [<email>], "subject": ..., "text": ...}`. 인증번호와 재설정 링크만 담는 메일이라 HTML이 필요 없다.
   - 대안: Resend 공식 Java SDK. 의존성과 SDK 자체 HTTP 클라이언트가 늘고, stub 검증에 필요한 base URL 변경과 오류 메시지 내용을 직접 통제하기 어렵다.
   - 대안: Resend SMTP + `JavaMailSender`. 완료 조건의 "Resend API로 요청, stub 서버로 확인"과 맞지 않는다.
5. **실패 예외에는 상태 코드와 실패 유형만 담는다.**
   - `RestClient`의 기본 오류 예외(`HttpClientErrorException` 등)는 메시지에 응답 본문을 담는다. Resend의 검증 오류 본문에 수신 주소가 들어갈 수 있고, 리스너가 예외를 그대로 로그에 남기므로 완료 조건 2를 어긴다.
   - `Gov24Client`처럼 `exchange`로 상태 코드만 꺼내고 본문은 읽지 않는다. 2xx가 아니면 `AccountMailSendException("Resend 응답 상태 " + status)`를 던진다. 연결·읽기 실패(`ResourceAccessException`)는 원인을 버리고 `AccountMailSendException("Resend 연결 실패: " + 원인 클래스 이름)`으로 바꾼다. JDK 예외 메시지에 무엇이 담길지 보장할 수 없어서다.
   - 요청 본문 record `ResendEmailRequest`와 `ResendProperties`의 `toString`은 주소·본문·키를 가린다(0020의 `toString` 규칙을 잇는다).
6. **API 키나 발신 주소가 없는 채 `resend`를 고르면 빈을 만들 때 기동에 실패한다.**
   - `JwtConfig`와 같은 방식이다. `ResendProperties`에 `@Validated`·`@NotBlank`를 붙이면 `logging`이나 테스트에서도 바인딩할 때 검증이 걸리므로, `resend` 빈 메서드 안에서만 검사한다.
   - 발신 주소가 없으면 모든 발송이 Resend의 검증 오류로 실패하므로, 완료 조건에 있는 API 키와 함께 발신 주소도 검사한다.
7. **발송 구현을 지정하지 않은 기본 설정은 지금처럼 기동에 실패한다.**
   - `application.yaml`의 `auth.mail.sender`를 `${MAIL_SENDER:}`로 두어, 배포 환경이 `MAIL_SENDER=resend`(환경 변수나 `backend/.env`)로 고른다. 비워 두면 0020과 같이 빈이 없어 기동에 실패한다.
   - 대안: 기본값을 `resend`로. 배포 설정이 하나 줄지만, 모든 `@SpringBootTest`와 `collect` 단독 실행에 Resend 키가 필요해지고 테스트에서 가짜 발송과 함께 등록되지 않게 막아야 한다.
8. **테스트 공통 설정이 `auth.mail.sender=test`를 두고, 테스트 가짜는 그 값일 때만 등록한다.**
   - `RecordingAccountMailSender`에 `@ConditionalOnProperty(name = "auth.mail.sender", havingValue = "test")`를 붙인다. Resend 통합 테스트가 `auth.mail.sender=resend`로 띄울 때 발송 구현이 둘이 되지 않는다. 기존 테스트와 `ApiDocsTest`(`test`)는 그대로 등록된다.
   - (구현 중 변경) 처음에는 `matchIfMissing = true`로 "지정하지 않았을 때"도 등록하려 했다. 그러나 `application.yaml`의 `${MAIL_SENDER:}`는 값이 없으면 빈 문자열이 되어 "지정하지 않음"으로 보지 않으므로, 공통 설정에서 값을 명시한다.
9. **발신 도메인 인증 절차는 `docs/ops/mail-domain.md`에 둔다.**
   - 결정 문서는 결정과 근거를 담고 절차는 담지 않으며, 플랜은 이슈가 끝나면 고치지 않는다. 다시 배포하거나 키를 바꿀 때 찾을 수 있도록 운영 절차 문서를 따로 둔다. `docs/README.md`의 안내에 `ops/`를 한 줄 더한다.
   - 대안: 결정 0032의 `영향`에 적기. 절차가 바뀔 때마다 채택된 결정 본문을 고쳐야 한다.
10. **(구현 중 추가) `spring.task.execution.mode=force`로 Boot의 기본 실행기를 유지한다.** 결정 문서 0032.
    - Boot 4.1의 `applicationTaskExecutor`는 `Executor` 빈이 하나라도 있으면 만들어지지 않는다(`TaskExecutorConfigurations.OnExecutorCondition`: `Executor` 빈 없음 또는 `spring.task.execution.mode=force`). 계정 메일 실행기를 빈으로 두면 기본 실행기가 사라져 MVC 비동기 처리 등 다른 곳의 동작이 바뀐다.
    - 대안: 실행기를 빈으로 두지 않고 리스너가 직접 `execute`. `@Async`를 쓸 수 없고 테스트에서 실행기를 바꿀 방법이 따로 필요하다.

## 단계별 구현 계획

1. **플랜 상태**: 착수할 때 이 파일의 `status`를 `in-progress`로 바꾼다.
2. **Resend 설정** `kr.govbenefit.account.config`
   - `ResendProperties` record(`@ConfigurationProperties("auth.mail.resend")`): `URI baseUrl`, `String apiKey`, `String from`, `Duration connectTimeout`, `Duration readTimeout`. `toString`은 `apiKey`를 `****`/`(없음)`으로, `from`은 그대로 둔다(발신 주소는 비밀이 아니다).
   - `AccountMailConfig`
     - `@EnableAsync`와 `@EnableConfigurationProperties(ResendProperties.class)`를 더한다.
     - `public static final String EXECUTOR = "accountMailExecutor"`.
     - `@Bean(EXECUTOR) TaskExecutor accountMailExecutor(@Value("${auth.mail.async:true}") boolean async)`: `async`면 `SimpleAsyncTaskExecutor("account-mail-")`에 `setVirtualThreads(true)`, `setTaskTerminationTimeout(10_000)`. 아니면 `new SyncTaskExecutor()`(설계 1·2).
     - `@Bean @ConditionalOnProperty(name = "auth.mail.sender", havingValue = "resend") AccountMailSender resendAccountMailSender(ResendProperties properties)`: `apiKey`가 비었으면 `IllegalStateException("Resend API 키(RESEND_API_KEY)가 설정되지 않았습니다.")`, `from`이 비었으면 `IllegalStateException("메일 발신 주소(RESEND_FROM)가 설정되지 않았습니다.")`(설계 6).
     - 클래스 Javadoc의 결정 번호를 0032로 바꾸고 `resend`와 실행기를 설명한다.
   - `AccountMailProperties` Javadoc: `auth.mail.sender`·`async`·`resend`는 `AccountMailConfig`가 읽는다고 고친다.
3. **Resend 발송 구현** `kr.govbenefit.account.mail`
   - `AccountMailSendException extends RuntimeException`: 메시지만 받는 생성자 하나(설계 5).
   - `ResendEmailRequest` record(`from`, `List<String> to`, `subject`, `text`): `toString`은 `ResendEmailRequest[subject=...]`만 남긴다.
   - `ResendAccountMailSender implements AccountMailSender`
     - 생성자에서 `Gov24Client`와 같은 방식으로 `RestClient`를 만든다. `baseUrl`, 기본 헤더 `Authorization`·`User-Agent`.
     - `sendSignupCode`: 제목 `[정부 혜택 도우미] 가입 인증번호`, 본문은 인증번호와 "N분 동안 쓸 수 있습니다", 요청하지 않았다면 무시하라는 안내.
     - `sendPasswordReset`: 제목 `[정부 혜택 도우미] 비밀번호 재설정`, 본문은 링크와 유효시간, 요청하지 않았다면 무시하라는 안내.
     - `send(String to, String subject, String text)`: `POST /emails`. `exchange`로 상태 코드만 꺼내고, 2xx가 아니면 `AccountMailSendException`을 던진다. `ResourceAccessException`을 잡아 원인 클래스 이름만 담은 `AccountMailSendException`으로 바꾼다(설계 5). 응답 본문(`id`)은 쓰지 않는다.
     - 유효시간 문구는 `Duration.toMinutes()`로 "10분"·"30분"을 만든다(현재 값은 모두 분 단위).
   - `AccountMailListener`: 두 메서드에 `@Async(AccountMailConfig.EXECUTOR)`. 클래스 Javadoc에 "커밋 뒤 별도 스레드에서 보내 응답이 발송을 기다리지 않는다(결정 0032)"를 더한다. 오류 로그 문구와 행 ID는 그대로다.
   - `AccountMailSender`·`LoggingAccountMailSender`·이벤트 record의 Javadoc 결정 번호를 0032로 바꾼다.
4. **설정 파일**
   - `application.yaml`의 `auth.mail`
     - `sender: ${MAIL_SENDER:}`(설계 7)와 주석: `logging`(local 전용)·`resend`, 비우면 기동 실패, 결정 0032.
     - `async: true`와 주석: 커밋 뒤 별도 스레드에서 보낸다. 테스트만 `false`.
     - `resend`: `base-url: https://api.resend.com`, `api-key: ${RESEND_API_KEY:}`, `from: ${RESEND_FROM:}`, `connect-timeout: 5s`, `read-timeout: 10s`. 주석에 발신 주소 형식 예(`정부 혜택 도우미 <no-reply@govbenefit.kr>`)와 도메인 인증 문서 경로.
   - `application.yaml`의 `spring.task.execution.mode: force`와 주석(설계 10).
   - `application-local.yaml`: 주석의 결정 번호를 0032로 바꾸고, 실제 발송 확인은 환경 변수 `AUTH_MAIL_SENDER=resend`로 덮는다는 주석을 더한다. `sender: logging`은 그대로다.
   - `src/test/resources/config/application.yaml`: `auth.mail.sender: test`(설계 8), `auth.mail.async: false`(설계 2)와 주석.
   - `backend/.env.example`: 빈 값 주석 세 줄을 더한다.
     ```
     # 메일 발송 구현(결정 0032). resend면 아래 두 값이 필수다. local 프로필은 주석으로 두면 로그로만 남긴다.
     #MAIL_SENDER=
     # Resend API 키(발송 권한만 준 키). 발신 도메인 인증은 docs/ops/mail-domain.md
     #RESEND_API_KEY=
     # 발신 주소. 인증한 도메인의 주소여야 한다. 예: 정부 혜택 도우미 <no-reply@govbenefit.kr>
     #RESEND_FROM=
     ```
5. **테스트 가짜 조건**: `RecordingAccountMailSender`에 `@ConditionalOnProperty`를 붙이고 Javadoc에 이유를 더한다(설계 8).
6. **Resend stub 서버** `src/test/java/kr/govbenefit/account/mail/ResendStubServer.java`
   - `MockWebServer`와 `Dispatcher`. 받은 요청(메서드, 경로, `Authorization`, `User-Agent`, JSON 본문)을 기록한다.
   - `respondWith(int status, String body)`: 다음 응답을 바꾼다. 기본은 200 `{"id":"test-id"}`.
   - `hold()` / `release()`: 요청이 도착하면 기록한 뒤 `CountDownLatch`가 풀릴 때까지(최대 10초) 응답하지 않는다. `awaitRequest(Duration)`으로 도착을 기다린다.
   - `disconnect()`: 응답하기 전에 연결을 끊는다(`SocketEffect.CloseSocket`). 통합 테스트의 연결 실패 시나리오에 쓴다.
   - `held()`: 붙잡혀 아직 응답하지 않은 요청 수. 비동기 테스트가 응답 시점에 1인지 확인한다. 동기였다면 붙잡힌 시간이 지나 응답한 뒤라 0이다.
   - `close()`로 멈춘 뒤의 `baseUrl()`은 단위 테스트의 연결 실패 시나리오에 쓴다.
   - `reset()`: 기록과 바꾼 응답을 지운다.
7. **테스트**
   - `ResendAccountMailSenderTest`(단위, Spring 없음, stub 사용)
     - `가입_인증번호를_Resend_형식으로_요청한다`: `POST /emails`, `Authorization: Bearer test-key`, `User-Agent` 있음, 본문의 `from`·`to`·`subject`, `text`에 번호와 "10분".
     - `재설정_링크를_Resend_형식으로_요청한다`: `text`에 링크 전체와 "30분".
     - `오류_응답이면_상태_코드만_담은_예외를_던진다`: 422(본문에 수신 주소를 넣은 응답)와 500. 예외 메시지에 상태 코드가 있고, 이메일·번호·링크·키가 없다.
     - `연결이_끊기면_원인_유형만_담은_예외를_던진다`(`disconnect()`), `서버에_연결하지_못하면_원인_유형만_담은_예외를_던진다`(멈춘 stub 주소). 예외 메시지에 이메일·번호·링크·키가 없다.
     - `설정의_toString은_API_키를_가린다`, `요청_본문의_toString은_주소와_본문을_가린다`.
   - `ResendMailDeliveryTest`(`@SpringBootTest`, `auth.mail.sender=resend`, 테스트 키·발신 주소, `@DynamicPropertySource`로 stub 주소, 실행기는 공통 설정의 동기, `OutputCaptureExtension`)
     - `가입_인증번호_메일이_Resend로_요청되고_그_번호로_확인된다`: 인증번호 요청 204 → stub 본문의 `to`·`from`, `text`에서 뽑은 6자리로 확인 API가 성공한다.
     - `재설정_메일이_Resend로_요청되고_그_링크로_재설정된다`: 가입 후 재설정 요청 204 → stub 본문의 링크에서 `#token=` 뒤 값으로 재설정이 성공한다.
     - `Resend가_오류를_응답해도_응답은_같고_로그에_민감한_값이_없다`: 500·422에서 인증번호 요청·재설정 요청 모두 204다. 캡처한 로그에 실패 로그(행 ID)가 있고, 이메일·번호(stub이 받은 값)·링크·API 키가 없다.
     - `Resend와_연결이_끊겨도_응답은_같고_로그에_민감한_값이_없다`: `stub.disconnect()` 뒤 같은 검증.
   - `AccountMailAsyncTest`(`@SpringBootTest`, `auth.mail.sender=resend`, `auth.mail.async=true`, stub 주소)
     - `재설정_요청의_응답이_메일_발송을_기다리지_않는다`: `stub.hold()` 뒤 가입한 이메일로 재설정을 요청해 204를 받는다. 응답을 받은 시점에 stub이 요청을 받았고(`awaitRequest`) 아직 응답하지 않았음(`held()`가 1)을 확인한 뒤 `release()`한다. 동기였다면 stub이 붙잡은 10초 뒤에야 요청이 끝나 이 확인이 실패한다.
   - `AccountMailRollbackTest`(`@SpringBootTest`, 가짜 발송)
     - `롤백된_요청의_인증번호는_발송되지_않는다`: `TransactionTemplate` 안에서 `EmailVerificationService.request`를 부르고 `setRollbackOnly()` → 기록이 없다. 같은 호출을 커밋하면 한 건이 기록된다.
     - `롤백된_요청의_재설정_링크는_발송되지_않는다`: `PasswordResetService.request`로 같은 검증.
   - `AccountMailConfigTest`에 더한다.
     - `resend인데_API_키가_없으면_기동에_실패한다`: 실패 원인 메시지에 `RESEND_API_KEY`.
     - `resend인데_발신_주소가_없으면_기동에_실패한다`: 메시지에 `RESEND_FROM`.
     - `resend면_Resend_구현을_쓴다`.
     - `기본_실행기는_가상_스레드에서_비동기로_보낸다`(가상 스레드, 이름 `account-mail-`) / `async가_false면_호출한_스레드에서_보낸다`.
     - `지정하지_않으면_발송_구현이_없다`에 빈 문자열(`${MAIL_SENDER:}`) 경우를 더한다.
     - 클래스 Javadoc의 결정 번호를 0032로 바꾼다.
8. **결정 문서**
   - `docs/decisions/0032-account-mail-resend.md`(제목 "계정 메일 Resend 비동기 발송"), `대체: 0020`.
     - 배경: 0020이 남긴 실제 발송과 비동기 전환. `DEC-OPS`에서 Resend로 정했다.
     - 결정: 0020에서 유지되는 것(종류별 메서드, 커밋 후 발송, 실패를 삼킴, `auth.mail.sender`로 선택, 지정하지 않으면 기동 실패, `toString` 가림)과 바뀌는 것(전용 가상 스레드 실행기의 비동기 발송, `resend` 구현, 재시도 없음, 예외에 상태 코드만, 키·발신 주소 없으면 기동 실패, 테스트는 `auth.mail.sender=test`·`auth.mail.async=false`, 가짜는 `sender`가 `test`일 때만 등록, `spring.task.execution.mode=force`).
     - 근거: 설계 1~8의 대안과 이유, Resend 문서에서 확인한 사실(확인 날짜와 방법).
     - 적용 위치: 단계 2~7의 파일.
     - 영향: 알림 메일 경계(0020에서 옮김), `local` 프로필을 켜는 새 `@SpringBootTest`는 `auth.mail.sender`를 덮어써야 함(0020에서 옮김), 재시도가 필요해지면 `Idempotency-Key`와 함께 이 결정을 대체.
   - `0020-account-mail-sender.md`: `상태` 줄만 `대체됨 → [0032](0032-account-mail-resend.md)`.
   - `docs/decisions/README.md` 목록: 0020 상태를 바꾸고 0032 행을 더한다.
9. **제품 문서** `docs/product/development.md`
   - `결정한 사항` 이력 문단에 "DEC-OPS 중 메일 발송 서비스를 2026-10-27에 Resend로 정했다. 기록은 #78에서 했다."를 더한다.
   - 결정 표에 `DEC-OPS(메일 발송)` 행을 더한다.
     - 선택: 계정 메일(가입 인증번호·비밀번호 재설정)은 Resend HTTP API로 보내고, 요청 처리와 분리해 커밋 뒤 비동기로 보낸다. 발신 도메인은 `govbenefit.kr`이다.
     - 이유: 이 이슈에서 사용자가 정한 선택(Resend)과, HTTP API라 로컬 stub으로 검증할 수 있고 DNS 레코드만으로 발신 도메인을 인증한다는 점. 요금·발송 한도는 작성할 때 Resend 요금 페이지에서 다시 확인해 확인 날짜와 함께 적는다. 한계: 발송 실패를 재시도하지 않는다([결정 0032](../decisions/0032-account-mail-resend.md)).
     - 결정일·근거: 2026-10-27(#78). DEC-AUTH(로그인·탈퇴).
   - `아직 결정할 사항`의 `DEC-OPS` 행에서 "메일 발송 서비스"를 지운다.
   - `DEC-AUTH(로그인·탈퇴)`의 "실제 발송 서비스는 DEC-OPS에서 고른다"를 "실제 발송은 DEC-OPS(메일 발송)를 따른다"로 바꾼다.
10. **발신 도메인 인증 절차** `docs/ops/mail-domain.md`(설계 9)
    - Resend 가입, Domains에서 `govbenefit.kr` 추가(리전 선택), 대시보드가 보여 주는 DNS 레코드(DKIM TXT, 반송용 MX·SPF TXT)를 도메인 DNS에 추가, 인증 확인, DMARC TXT는 선택.
    - 발송 권한만 주고 도메인을 `govbenefit.kr`로 한정한 API 키를 만든다.
    - 실행 환경에 `MAIL_SENDER=resend`, `RESEND_API_KEY`, `RESEND_FROM`을 넣는다. 값은 문서에 적지 않는다.
    - 수동 확인: 인증번호 요청 후 받은편지함(스팸함 포함)에서 메일과 발신 주소를 확인하고, Resend 대시보드의 Emails에서 전달 상태를 본다.
    - 레코드 이름과 값은 대시보드가 도메인마다 보여 주는 값을 따르고 문서에 고정하지 않는다.
    - `docs/README.md`의 첫 문단과 `먼저 읽을 문서` 표에 `ops/`(운영 절차)를 더한다.
11. **검증과 PR**: `./gradlew test`가 통과하면 마지막 커밋에서 `status: done`으로 바꾸고 PR을 올린다. 수동 확인(완료 조건 7)은 사용자가 도메인을 인증한 뒤 하므로 PR 설명에 남은 항목으로 적는다.

## 완료 조건 매핑

- [ ] Resend 발송을 설정하고 실행하면 가입 인증번호 메일과 비밀번호 재설정 메일이 Resend API로 요청된다. 요청 내용(수신자, 발신 주소, 인증번호, 재설정 링크)은 stub 서버로 확인한다. → 설계 4. `ResendAccountMailSenderTest`의 형식 검증, `ResendMailDeliveryTest`의 두 발송 시나리오(stub에서 받은 번호·링크로 확인·재설정까지 성공).
- [ ] Resend가 오류를 응답하거나 연결에 실패해도 인증번호·재설정 요청의 응답은 성공과 같고, 로그에 이메일·인증번호·링크·API 키가 남지 않는다. → 설계 3·5. `ResendMailDeliveryTest`의 오류 응답·연결 실패, `ResendAccountMailSenderTest`의 예외 메시지 검증.
- [ ] 비밀번호 재설정 요청의 응답이 메일 발송 완료를 기다리지 않는다. → 설계 1·2. `AccountMailAsyncTest`.
- [ ] 롤백된 요청의 인증번호나 재설정 링크는 발송되지 않는다. → 설계 1. `AccountMailRollbackTest`.
- [ ] API 키가 설정되지 않은 채 Resend 발송을 고르면 기동에 실패한다. → 설계 6. `AccountMailConfigTest`.
- [ ] `docs/product/development.md`에 메일 발송 서비스 결정이 기록되고, 결정 0020이 새 결정 문서로 대체된다. → 단계 8·9.
- [ ] 발신 도메인을 인증한 뒤 실제 메일 주소로 인증번호 메일을 받아 확인한다(수동). → 단계 10의 절차. 사용자가 수행한다.

## 검증 명령

- `backend/`에서 `./gradlew test`
- 수동 확인(도메인 인증 뒤, 기본 테스트에 포함하지 않음): `backend/.env`에 `RESEND_API_KEY`, `RESEND_FROM`을 넣는다. `.env`는 `application.yaml`의 가져오기라 `application-local.yaml`의 `sender: logging`을 이기지 못하므로, `local` 프로필에서 sender는 환경 변수 `AUTH_MAIL_SENDER`로 덮는다.
  ```sh
  SPRING_PROFILES_ACTIVE=local AUTH_MAIL_SENDER=resend ./gradlew bootRun
  curl -s -i -X POST localhost:8080/api/auth/email-verification -H 'Content-Type: application/json' \
    -d '{"email":"<받을 주소>"}'
  ```
  204를 받은 뒤 받은편지함에서 인증번호 메일을 확인하고, 그 번호로 `/api/auth/email-verification/confirm`이 성공하는지 본다.

## 결정 필요

없음
