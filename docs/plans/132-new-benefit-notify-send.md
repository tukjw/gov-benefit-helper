status: done

# 구현 계획 — #132 발송 대기 중인 새 혜택 묶음을 메일로 보내고 실패하면 재시도

브랜치: `feat/132-new-benefit-notify-send` (develop `df850df` 기준)

## 목표 및 범위

- 목표: #130이 저장한 발송 대기 묶음을 묶음 하나당 메일 한 통으로 Amazon SES에 보낸다. 계정 메일 몫을 남기도록 정한 속도로 나눠 보내고, 일시 실패는 정한 횟수·기한 안에서 다시 보내며, 앱이 다시 시작돼도 보내지 않은 묶음을 이어서 보낸다(`REQ-NOTIFY-02` P0, `REQ-NOTIFY-03`, `DEC-NOTIFY(새 혜택 알림)`).
- 포함:
  - `new_benefit_digest`의 발송 상태 값과 시도 기록 컬럼(V18).
  - 발송 작업(`kr.govbenefit.notification`)과 설정.
  - 메일 발송 인터페이스에 새 혜택 알림 메일을 더하고, 실패 예외에 재시도 가능 여부를 담는다(`kr.govbenefit.account.mail`).
  - 개인정보 처리방침의 처리 목적·처리 항목·위탁 업무·보유 기간과 변경 이력.
  - 결정 문서 0047(0045 대체), 0048.
- 제외(이슈 본문):
  - 알림 설정 화면(후속 이슈). 메일 안의 알림 끄기 안내가 가리킬 화면이다.
  - 여러 작업자의 동시 점유, 영구 실패·취소·만료·결과 불명의 구분, SES 접수 뒤 응답 유실(`REQ-NOTIFY-02` P1).
- 제외(이 플랜):
  - 반송·수신 거부 이벤트 처리. 0045와 같이 SES 이메일 피드백 전달로 발신 주소에 온다.
  - 원클릭 수신 거부(`List-Unsubscribe`). 토큰과 공개 API가 필요하다.
  - 운영 문서(`docs/ops/mail-domain.md`) 수정. 발신 도메인·IAM 권한(`ses:SendEmail`)·환경 변수가 바뀌지 않는다.

## 확인된 사실과 제약

- 묶음은 `new_benefit_digest(id, user_id, digest_date, status, created_at)`이고 `status`는 `PENDING` 하나다(V17, 결정 0046). 0046은 "발송 상태는 묶음 행에 둔다. `status`에 값을 더할 때 이 결정을 대체하지 않는다"고 정했다. 묶음의 혜택은 `new_benefit_target.digest_id`로 읽는다.
- #130은 아직 운영에 배포되지 않았다(최근 릴리스 v0.1.4, #129). V17과 V18이 함께 배포되므로 운영에는 기존 묶음 행이 없다. 로컬 DB에는 있을 수 있다.
- 묶기는 매일 08:00 cron(`notification.new-benefit.digest-cron`)이고, 테스트 공통 설정에서 `-`로 끈다. 묶기가 08:00에 알림이 꺼진 사용자의 묶지 않은 항목을 지운다. 이미 묶은 항목은 그대로 둔다.
- 스케줄러는 Spring Boot 기본값이다. `@EnableScheduling`은 `CollectionConfig`에 있고, `TaskScheduler` 빈을 따로 두지 않아 스레드가 하나다. 정기 작업은 수집 04:00, 인증 기록 파기 04:30, 묶기 08:00, 로그인 실패 기록 정리(`fixedDelay` 15분)다.
- `@Scheduled`의 `-`(`CRON_DISABLED`)는 `cron`에만 쓸 수 있다. `fixedDelayString`은 값을 기간으로 변환하므로 `-`를 받지 않는다(Spring Framework 7.0.9 `ScheduledAnnotationBeanPostProcessor` 소스).
- 컨텍스트가 닫힐 때 `ThreadPoolTaskScheduler`(Spring 7.0.9 `ExecutorConfigurationSupport`, `ExecutorLifecycleDelegate` 소스)의 동작:
  - `ContextClosedEvent`에서 새 실행을 멈추고, 생명주기 종료 단계에서 실행 중인 작업이 끝나기를 기다린다. Boot의 단계별 제한 시간 기본값은 30초다.
  - 그 뒤 빈을 없앨 때 `shutdownNow()`로 실행 중인 스레드를 interrupt한다.
  - 운영 compose의 `stop_grace_period`는 40초다.
- 메일 발송:
  - `AccountMailSender`(`sendSignupCode`, `sendPasswordReset`) 뒤에 `SesAccountMailSender`·`LoggingAccountMailSender`(local)·`RecordingAccountMailSender`(테스트)가 있고, `auth.mail.sender`로 고른다(결정 0045).
  - SES 클라이언트는 SDK 재시도를 끄고(`AwsRetryStrategy.doNotRetry()`), 연결 5초·소켓 10초 시간 제한을 둔다.
  - 실패하면 `AccountMailSendException`을 던진다. 메시지는 `SES 응답 상태 <HTTP 상태> <오류 코드>` 또는 `SES 연결 실패: <원인 클래스 이름>`이며, 수신 주소·SES 오류 메시지·키를 담지 않는다.
  - 0045의 `영향`에 "발송 실패를 재시도하게 되면 이 결정을 대체한다"가 있다.
  - 테스트는 `SesStubServer`로 SES 오류(`respondWithError`)·연결 끊김(`disconnect`)·응답 지연(`hold`)을 만든다.
- SES 서울 리전은 샌드박스가 해제됐고(2026-11-05), 계정의 초당 발송 한도는 14건이다(2026-11-06 사용자 확인). 계정 메일은 속도를 제한하지 않는다.
- 계정은 `UserAccount.email`, `newBenefitNotify`(가입하면 꺼짐, V12 `DEFAULT FALSE`)를 가진다. 프론트에는 알림을 켜는 화면이 아직 없다. 설정 API는 `PATCH /api/me/notification-settings` `{"newBenefit": true}`다.
- 혜택 상세 화면은 `/benefits/:serviceId`다(`frontend/src/router.tsx`). `benefit.name`·`agency_name`은 DB에서 NULL을 허용하지만, 로컬 DB의 혜택 10,950건 중 빈 값은 없다(2026-11-06 확인).
- 비밀번호 재설정 링크의 웹 주소는 설정값(`auth.password-reset.url`)이고, local에서 `http://localhost:3000`으로 덮는다.
- 개인정보 처리방침(`frontend/src/pages/privacy/PrivacyPolicyPage.tsx`, 결정 0044):
  - 처리 목적은 "새 혜택 알림 설정 보관"까지다. 처리 항목의 "관심 혜택·알림 설정" 행은 "저장한 혜택, 새 혜택 알림 켜기·끄기"이고, SES 위탁 업무는 "가입 인증번호와 비밀번호 재설정 메일 발송, 반송·수신 거부 주소 관리"다.
  - 시행일은 2026년 11월 5일이고(이전 2026년 11월 4일), '처리방침의 변경' 절에 11월 4일 시행본에서 바뀐 내용이 있다.
  - 결정 0044는 처리 항목·목적·보유 기간·위탁이 바뀌는 변경은 같은 PR에서 처리방침을 고치도록 정했다.
- 고정 시계(`MutableClock`)는 `Instant`를 고정한다. `System.nanoTime()`과 `Thread.sleep`은 실제 시간으로 흐른다.
- 다음 마이그레이션 번호는 V18, 다음 결정 번호는 0047이다.

## 설계 결정

1. **발송은 DB 폴링 작업 하나가 한다.** 2026-11-06 사용자 결정. → [결정 0048](../decisions/0048-new-benefit-digest-send.md)
   - `NewBenefitDigestSender`가 30초 cron(`notification.new-benefit.send.cron`)으로 돈다. 발송 대기(`PENDING`)이고 다음 시도 시각이 지난 묶음을 ID 순으로 읽어, 대상이 빌 때까지 한 번에 보낸다.
   - 한 실행에서 같은 묶음을 두 번 다루지 않는다. 읽기는 실행을 시작한 시각과 마지막으로 읽은 ID로 한다. 실패해서 다음 시도 시각이 뒤로 간 묶음은 다음 실행에서 다시 읽힌다.
   - 첫 발송, 재시도, 재시작 뒤 재개, 기한 처리가 모두 이 경로 하나다.
   - 묶기와 같은 스케줄러 스레드에서 돌아 둘이 동시에 실행되지 않는다. 08:00 묶기가 도는 동안 실행 시각이 된 폴링은 묶기가 끝난 뒤 돈다. 08:00:00에 둘이 함께 실행 시각이 되면 순서가 정해지지 않으므로, 묶음 발송은 묶기가 끝난 뒤 늦어도 다음 폴링(30초)에 시작한다.
   - cron으로 두는 이유는 테스트에서 `-`로 끄기 위해서다(#130 설계 8과 같다). `fixedDelayString`은 `-`를 받지 않는다.
   - 발송이 길어지면 같은 스레드의 다른 정기 작업이 밀린다. 초당 10건이면 1,000통에 약 100초다. 다른 cron 작업(04:00, 04:30)과는 시각이 겹치지 않는다.
   - 발송기 예외가 아닌 예외(DB 오류 등)는 그 실행을 끝내고 오류 로그를 남긴다. 남은 묶음은 다음 폴링이 이어서 보낸다(#130과 같다).
   - 대안: 정해진 시점마다 실행(묶기 끝, 재시도 cron, 기동 이벤트). 시작 경로가 셋이다. 기동 이벤트는 스케줄러 밖에서 돌아 08:00 발송과 동시에 같은 묶음을 보낼 수 있다.
   - 대안: 메모리 큐나 비동기 실행기. 재시작하면 큐가 사라져 DB 조회를 따로 만들어야 한다. 여러 스레드에서 돌아 같은 묶음의 동시 발송을 막을 점유가 필요하다.
2. **발송 상태와 시도 기록은 묶음 행에 둔다.** 2026-11-06 사용자 결정. → [결정 0048](../decisions/0048-new-benefit-digest-send.md)
   - `status`의 값:
     - `PENDING`: 발송 대기와 재시도 대기.
     - `ACCEPTED`: SES 접수.
     - `FAILED`: 최종 실패.
     - `CANCELED`: 보내기 직전에 알림이 꺼져 보내지 않음.
   - 더하는 컬럼:
     - `attempt_count`: SES에 보낸 횟수.
     - `next_attempt_at`: 이 시각부터 보낸다. 묶을 때 묶은 시각으로 둔다.
     - `last_error`: 마지막 발송 실패. 발송기 예외 메시지이므로 상태와 오류 코드만 담긴다.
     - `accepted_at`: SES가 접수한 시각.
   - '접수'는 SES `SendEmail`이 200으로 응답했다는 뜻이다. 실제 전달과 읽음은 기록하지 않는다.
   - 최종 실패의 이유(횟수 초과, 재시도 불가, 기한)는 상태로 나누지 않는다(이슈 제외 범위). `attempt_count`와 `last_error`로 알 수 있다. 기한이 지나 끝난 묶음은 `last_error`를 바꾸지 않는다.
   - SES `MessageId`는 저장하지 않는다. 쓰는 곳이 없다.
   - 대안(`CANCELED` 대신): `FAILED`에 사유 코드를 남김. 측정할 때 실패 건수와 섞인다.
   - 대안(`CANCELED` 대신): 묶음을 지움. 기록이 남지 않는다.
   - 대안: 발송 시도 테이블을 따로 둠. 시도마다 이력이 남지만 테이블과 쓰기가 는다. 시도별 결과는 로그로 남긴다.
3. **묶음 하나는 준비·발송·기록으로 나눈다.**
   - 준비(트랜잭션):
     - 묶음이 없으면(그사이 탈퇴) 건너뛴다.
     - 묶은 날 24:00이 지났으면 `FAILED`로 끝낸다.
     - 계정의 알림이 꺼져 있으면 `CANCELED`로 끝낸다.
     - 셋 다 아니면 수신 주소와 묶음의 혜택(이름, 운영 기관, 상세 링크)을 읽는다.
   - 발송(트랜잭션 밖): 속도 대기 뒤 SES에 보낸다. SES를 부르는 동안 DB 연결을 잡지 않는다.
   - 기록(트랜잭션): 접수 또는 실패를 기록한다. 그사이 탈퇴해 묶음이 없으면 기록하지 않는다.
   - "보내기 직전" 확인은 준비 단계다. 확인부터 SES 호출까지의 간격은 속도 대기(최대 0.1초)다.
   - `SENDING` 같은 점유 상태는 두지 않는다. 작업자 하나를 전제로 한다(`REQ-NOTIFY-02` P0).
   - 한계: SES가 접수한 뒤 기록이 커밋되기 전에 프로세스가 끝나거나 DB 기록이 실패하면, 그 묶음은 `PENDING`으로 남아 다시 보내진다. 이슈가 뺀 "SES 접수 뒤 응답 유실"(`REQ-NOTIFY-02` P1)이다.
4. **SES 호출은 초당 10건을 넘지 않게 간격을 둔다.** 2026-11-06 사용자 결정. → [결정 0048](../decisions/0048-new-benefit-digest-send.md)
   - 계정 한도는 초당 14건이다. 남은 4건은 계정 메일 몫이다. 계정 메일은 제한하지 않는다.
   - SES를 부르기 직전에, 앞 호출을 시작한 뒤 1/10초가 지날 때까지 기다린다. 작업자가 하나라 설정한 속도를 넘을 수 없다.
   - 대기는 `System.nanoTime()`과 `Thread.sleep` 기준이라 테스트의 고정 시계와 상관없이 실제 시간으로 흐른다. 대기 상태는 발송 작업 빈의 필드라 폴링 실행이 바뀌어도 이어진다. 첫 호출은 기다리지 않는다.
   - SES를 부르지 않는 묶음(취소, 기한 지남)은 기다리지 않는다.
   - 대기 중에 interrupt되면(종료) interrupt 표시를 되살리고 그 실행을 끝낸다.
   - 대안: 토큰 버킷 라이브러리(Bucket4j, Resilience4j). 작업자가 하나라 쓸 곳이 없는 의존성이다.
   - 대안: 계정 메일과 제한기 하나를 함께 씀. 가입 인증번호 메일이 알림 뒤에서 기다린다.
5. **일시 실패는 1분, 5분, 30분, 2시간, 6시간 뒤에 다시 보내고, 묶은 날 24:00까지만 보낸다.** 2026-11-06 사용자 결정. → [결정 0048](../decisions/0048-new-benefit-digest-send.md)
   - 간격 목록(`notification.new-benefit.send.retry-delays`)의 개수가 재시도 횟수다. 첫 시도를 포함해 최대 6번 보낸다.
   - 08:00에 첫 시도가 실패하면 08:01, 08:06, 08:36, 10:36, 16:36에 다시 보낸다.
   - 다음 시도 시각이 묶은 날 24:00 이후면 바로 `FAILED`로 끝낸다. 그래서 `PENDING` 묶음의 다음 시도 시각은 언제나 기한 전이다.
   - 일시 실패와 재시도 불가는 설계 7의 분류를 따른다. 재시도 불가 실패는 첫 실패에서 `FAILED`다.
   - 응답 대기 시간 초과도 일시 실패로 다시 보낸다(2026-11-06 사용자 결정). 한계: SES가 이미 접수했다면 같은 메일이 한 번 더 간다(`REQ-NOTIFY-02` P1).
   - 대안: 실패한 자리에서 바로 다시 보냄. SES가 잠깐 멈춰도 몇 분 안에 횟수를 다 쓰고, 시도 횟수가 메모리에만 있다.
   - 대안: 고정 간격으로 재시도 차례를 돌림. 첫 재시도가 늦거나 마지막 재시도가 몰린다.
6. **재시작 뒤 이어서 보내는 코드를 따로 두지 않는다.**
   - 상태의 원천은 DB다. 기동 뒤 첫 폴링이 `PENDING`이고 다음 시도 시각이 지난 묶음을 보낸다. 접수한 묶음은 `ACCEPTED`라 다시 읽히지 않는다.
   - 기한이 지난 `PENDING` 묶음도 같은 폴링이 읽고, 준비 단계(설계 3)가 `FAILED`로 끝낸다. 모든 `PENDING` 묶음의 다음 시도 시각이 기한 전이므로(설계 5), 기한이 지난 묶음은 반드시 읽힌다.
   - 종료할 때는 Spring 기본 동작을 따른다. 새 폴링은 시작하지 않고, 실행 중인 폴링을 30초까지 기다린 뒤 interrupt한다. 그 안에 끝나지 않으면 SES 호출 중에 끊길 수 있고, 설계 3의 한계가 생긴다.
   - 대안: 종료 신호를 받으면 지금 보내는 한 건까지만 마치고 멈춤. 끊김을 줄이지만 없애지 못하며, 이슈가 뺀 "SES 접수 뒤 응답 유실"의 범위다.
7. **새 혜택 알림 메일은 `AccountMailSender`에 메서드를 더해 보내고, 실패 예외에 재시도 가능 여부를 담는다.** 2026-11-06 사용자 결정. → [결정 0047](../decisions/0047-mail-ses-and-new-benefit-mail.md)(0045 대체)
   - `void sendNewBenefitDigest(String email, List<NewBenefitMailItem> benefits, String siteUrl)`. `NewBenefitMailItem(String name, String agencyName, String url)`. 링크는 부르는 쪽이 만든다. 비밀번호 재설정 링크와 같다.
   - 발송 작업이 이 메서드를 동기로 부른다. 계정 메일의 커밋 뒤 이벤트와 실행기는 거치지 않는다.
   - `AccountMailSendException`에 `boolean retryable`을 더한다.
     - 일시 실패(`true`): 429와 5xx 서비스 오류, 연결 실패(`SdkClientException`: 연결 거부·끊김·시간 초과).
     - 재시도 불가(`false`): 그 밖의 서비스 오류(400 `MessageRejected`·`SendingPausedException`, 403, 404 등).
     - 메시지 형식은 0045와 같다.
   - 계정 메일 리스너는 `retryable`을 쓰지 않고 그대로 다시 보내지 않는다. SDK 재시도도 끈 채로 둔다. 재시도 횟수·간격·기한은 발송 작업이 DB에 두고 정한다.
   - 인터페이스 이름(`AccountMailSender`)은 바꾸지 않는다. Javadoc만 회원에게 보내는 메일로 고친다.
   - 대안: SES 호출부를 공통 클래스로 빼고 계정 메일과 알림이 함께 씀. 계정 메일 코드를 옮기는 리팩터링이 끼어든다.
   - 대안: `notification`에 알림 전용 발송기를 따로 둠. SES 요청 조립과 예외 변환이 중복되고, 구현 선택·local 로그 구현·테스트 가짜 구현을 다시 만든다.
8. **메일은 텍스트 한 통이고, 알림 끄기는 사이트 주소와 함께 안내한다.** 2026-11-06 사용자 결정.
   - 본문은 0045와 같이 텍스트만 보낸다. 대부분의 메일 앱이 링크를 걸어 준다. HTML은 템플릿과 이스케이프가 늘어난다.
   - 제목: `[정부 혜택 도우미] 조건에 맞는 새 혜택 <N>건`.
   - 본문(혜택은 혜택 ID 순):
     ```text
     회원님의 조건에 해당하는 새 혜택이 등록되었습니다.

     1. <혜택 이름>
        운영 기관: <운영 기관>
        <사이트 주소>/benefits/<서비스ID>

     2. ...

     '해당'은 회원님이 입력한 프로필을 혜택의 대상 조건과 비교한 결과입니다. 실제로 받을 수 있는지는 운영 기관이 정하므로, 신청하기 전에 혜택 상세 화면의 원문을 꼭 확인하세요.

     이 메일은 새 혜택 알림을 켠 회원에게 보냅니다. 알림을 끄려면 <사이트 주소> 에 로그인한 뒤 알림 설정에서 끄세요.
     ```
   - 사이트 주소 뒤에 한 칸을 둔다. 메일 앱이 조사까지 링크에 넣지 않게 하기 위해서다.
   - 알림 끄기 안내는 아직 경로가 정해지지 않은 설정 화면을 링크로 걸지 않는다. 알림은 가입할 때 꺼져 있고 켜는 화면이 없으므로, 설정 화면이 나오기 전에는 실제 사용자가 메일을 받지 않는다.
   - 사이트 주소는 설정값 `notification.new-benefit.send.site-url`이다(운영 `https://govbenefit.kr`, local `http://localhost:3000`).
   - 혜택 이름과 운영 기관이 빈 경우는 다루지 않는다. 로컬 DB에 그런 혜택이 없다.
9. **개인정보 처리방침의 처리 목적·처리 항목·위탁·보유 기간을 고치고, 시행일은 운영 배포일로 한다.** 2026-11-06 사용자 결정.
   - 알림 기록은 "알림에 담은 새 혜택 목록과 발송 결과"다. `CANCELED`·`FAILED` 묶음도 탈퇴할 때까지 남으므로 "보낸 목록"으로 적지 않는다.
   - 처리 목적의 "목적이 바뀌면 미리 알립니다"는 새 목적이 아니라 기존 "혜택 정보 제공"을 구체적으로 적는 것으로 보고, 미리 알리지 않는다. 설정 화면이 나오기 전에는 알림을 받는 사용자가 없다.
   - 시행일과 변경 이력은 #127 설계 11과 같다. 날짜는 PR 직전 마지막 커밋에서 2026년 11월 8일로 정했다(2026-11-08 사용자 결정).
10. **실패 로그에는 묶음 ID, 시도 횟수, 실패 메시지만 남긴다.**
    - 실패마다 `log.warn("새 혜택 알림 발송 실패 digestId={} attempt={} retryable={} final={} error={}")`. `error`는 발송기 예외 메시지다. 예외 객체는 넘기지 않는다.
    - 실행마다 다룬 묶음이 있으면 요약 한 줄을 남긴다. 30초마다 빈 실행의 로그는 남기지 않는다.

## 단계별 구현 계획

1. **마이그레이션** `V18__add_new_benefit_digest_send.sql`
   ```sql
   -- 새 혜택 알림 발송(#132, REQ-NOTIFY-02, 결정 0048). 묶음마다 발송 시도와 결과를 기록한다.
   -- status: PENDING(발송 대기·재시도 대기), ACCEPTED(SES 접수), FAILED(최종 실패), CANCELED(보내기 직전 알림이 꺼져 보내지 않음)
   -- attempt_count: SES에 보낸 횟수. next_attempt_at: 이 시각부터 보낸다. last_error: 마지막 발송 실패(상태·오류 코드만).
   ALTER TABLE new_benefit_digest
       ADD COLUMN attempt_count   INT          NOT NULL DEFAULT 0 AFTER status,
       ADD COLUMN next_attempt_at DATETIME(6)  NULL AFTER attempt_count,
       ADD COLUMN last_error      VARCHAR(200) NULL AFTER next_attempt_at,
       ADD COLUMN accepted_at     DATETIME(6)  NULL AFTER last_error;

   -- 이미 있는 묶음은 묶은 시각부터 보낸다.
   UPDATE new_benefit_digest SET next_attempt_at = created_at;

   ALTER TABLE new_benefit_digest
       MODIFY COLUMN next_attempt_at DATETIME(6) NOT NULL,
       ADD INDEX idx_new_benefit_digest_status_next_attempt (status, next_attempt_at);
   ```
2. **묶음** `notification.domain.NewBenefitDigest`, `NewBenefitDigestStatus`
   - `NewBenefitDigestStatus`에 `ACCEPTED`, `FAILED`, `CANCELED`를 더하고 값마다 주석을 단다.
   - 엔티티에 `int attemptCount`, `LocalDateTime nextAttemptAt`, `String lastError`, `LocalDateTime acceptedAt`을 더한다. `pending(...)`은 `nextAttemptAt = now`로 둔다.
   - `LocalDateTime deadline()`: `digestDate.plusDays(1).atStartOfDay()`. 주석: 묶은 날 24:00(KST). 이 시각부터는 보내지 않는다.
   - `boolean isExpired(LocalDateTime now)`: `!now.isBefore(deadline())`.
   - `void accept(LocalDateTime now)`: 시도 횟수를 하나 올리고 `ACCEPTED`, `acceptedAt = now`.
   - `void cancel()`: `CANCELED`.
   - `void expire()`: `FAILED`.
   - `boolean fail(String error, boolean retryable, LocalDateTime now, List<Duration> retryDelays)`:
     - 시도 횟수를 하나 올리고 `lastError = error`로 둔다.
     - `retryable`이고, 시도 횟수가 `retryDelays.size()` 이하이고, `now + retryDelays.get(attemptCount - 1)`이 `deadline()` 전이면 `nextAttemptAt`을 그 시각으로 두고 `false`를 돌려준다.
     - 아니면 `FAILED`로 두고 `true`(최종 실패)를 돌려준다.
3. **저장소**
   - `NewBenefitDigestRepository.findDueIds(@Param("status") NewBenefitDigestStatus status, @Param("now") LocalDateTime now, @Param("afterId") Long afterId, Limit limit)`:
     `select d.id from NewBenefitDigest d where d.status = :status and d.nextAttemptAt <= :now and d.id > :afterId order by d.id`. 발송 작업은 `PENDING`을 넘긴다.
   - `NewBenefitTargetRepository.findDigestBenefits(@Param("digestId") Long digestId)`:
     `select b from NewBenefitTarget t join Benefit b on b.id = t.benefitId where t.digestId = :digestId order by b.id`. 반환은 `List<Benefit>`.
4. **메일 발송** `account.mail` (결정 0047)
   - `NewBenefitMailItem(String name, String agencyName, String url)` record.
   - `AccountMailSender`: Javadoc을 "회원에게 보내는 메일(계정 메일, 새 혜택 알림)"로 고치고, `sendNewBenefitDigest(String email, List<NewBenefitMailItem> benefits, String siteUrl)`를 더한다. 주석: 혜택은 받은 순서대로 적는다. `siteUrl`은 알림 끄기 안내에 쓴다.
   - `AccountMailSendException(String message, boolean retryable)`와 `isRetryable()`. 클래스 주석에 분류(설계 7)를 적는다.
   - `SesAccountMailSender`:
     - `sendNewBenefitDigest`: 제목과 본문은 설계 8. 줄바꿈은 `\n`, 같은 `send(to, subject, text)`로 보낸다.
     - `send`의 예외 변환: `SdkServiceException`은 `retryable = status == 429 || status >= 500`, `SdkClientException`은 `retryable = true`.
   - `LoggingAccountMailSender.sendNewBenefitDigest`: `log.info("[메일 미발송] 새 혜택 알림 to={} benefits={}", email, benefits)`.
   - `AccountMailListener`는 바꾸지 않는다.
5. **설정** `notification.config.NotificationConfig`, `NewBenefitSendProperties`
   - `@ConfigurationProperties("notification.new-benefit.send") record NewBenefitSendProperties(int ratePerSecond, List<Duration> retryDelays, String siteUrl)`. 주석은 설계 4·5·8.
   - `NotificationConfig`: `@Configuration(proxyBeanMethods = false) @EnableConfigurationProperties(NewBenefitSendProperties.class)`.
   - `application.yaml`:
     ```yaml
     notification:
       new-benefit:
         digest-cron: "0 0 8 * * *"
         # 새 혜택 알림 발송(결정 0048). 발송 대기 묶음을 30초마다 찾아, 대상이 빌 때까지 보낸다.
         send:
           cron: "*/30 * * * * *"
           # SES 계정 한도(초당 14건)보다 낮게 두어 계정 메일 몫을 남긴다.
           rate-per-second: 10
           # 일시 실패 뒤 다시 보내기까지의 간격. 개수가 재시도 횟수다. 묶은 날 24:00(KST)부터는 보내지 않는다.
           retry-delays: 1m,5m,30m,2h,6h
           # 메일의 혜택 상세 링크와 알림 끄기 안내에 쓰는 웹 주소(DEC-APP)
           site-url: https://govbenefit.kr
     ```
   - `application-local.yaml`: `notification.new-benefit.send.site-url: http://localhost:3000`(로컬 프론트).
   - 테스트 `config/application.yaml`: `notification.new-benefit.send.cron: "-"`. 주석: 정해진 시각에 돌아 테스트가 만든 묶음을 보내지 않게 끈다. 발송 테스트는 `sendDue()`를 직접 부른다.
6. **준비·기록** `notification.service.NewBenefitDigestSendWriter`
   - 클래스 주석: 묶음 하나의 발송 준비와 결과 기록을 각각 한 트랜잭션으로 한다. SES 호출은 두 트랜잭션 밖에서 한다. `NewBenefitDigestSender`만 부른다.
   - 안쪽 `record NewBenefitDigestMail(String email, List<NewBenefitMailItem> benefits)`.
   - `@Transactional Optional<NewBenefitDigestMail> prepare(Long digestId, LocalDateTime now)`: 설계 3의 준비 단계.
     - 묶음이 없으면 빈 값.
     - `isExpired(now)`면 `expire()` 뒤 빈 값.
     - 계정(`UserAccountRepository.findById(...).orElseThrow()`)의 알림이 꺼져 있으면 `cancel()` 뒤 빈 값.
     - 아니면 `findDigestBenefits`를 `NewBenefitMailItem(name, agencyName, siteUrl + "/benefits/" + serviceId)`로 바꿔 돌려준다.
   - `@Transactional void accept(Long digestId, LocalDateTime now)`.
   - `@Transactional Optional<NewBenefitDigest> fail(Long digestId, AccountMailSendException e, LocalDateTime now)`: `digest.fail(e.getMessage(), e.isRetryable(), now, properties.retryDelays())`를 부르고 그 묶음을 돌려준다. 발송 작업이 시도 횟수와 상태를 로그에 남길 때 쓴다.
   - `accept`와 `fail`은 묶음이 없으면(그사이 탈퇴) 아무것도 하지 않는다. `fail`은 이때 빈 값을 돌려주고, 발송 작업은 최종 실패로 센다.
7. **발송** `notification.service.NewBenefitDigestSender`(트랜잭션 없음)
   - 클래스 주석: `REQ-NOTIFY-02`, `REQ-NOTIFY-03`, 결정 0048. 단일 인스턴스와 스케줄러 스레드 하나를 전제한다. SES 접수 뒤 기록 전에 끝나면 다시 보낼 수 있다.
   - `@Scheduled(cron = "${notification.new-benefit.send.cron}", zone = "Asia/Seoul") public void sendScheduled()`: `sendDue()`를 부르고, `RuntimeException`을 잡아 `log.error("새 혜택 알림 발송 실패", e)`로 남긴다.
   - `public void sendDue()`:
     - `startedAt = LocalDateTime.now(clock)`. `findDueIds(startedAt, afterId, Limit.of(100))`를 빌 때까지 읽는다.
     - 묶음마다 `writer.prepare(id, now)`. 빈 값이면 건너뛴다.
     - 준비가 끝나면 `awaitTurn()`. interrupt되면 실행을 끝낸다.
     - `mailSender.sendNewBenefitDigest(mail.email(), mail.benefits(), siteUrl)`.
       - 성공하면 `writer.accept(id, now)`.
       - `AccountMailSendException`이면 `writer.fail(id, e, now)` 뒤 설계 10의 실패 로그.
     - `now`는 단계마다 `LocalDateTime.now(clock)`으로 다시 읽는다.
     - 다룬 묶음이 있으면 `log.info("새 혜택 알림 발송 accepted={} retrying={} failed={} skipped={} interrupted={} elapsedMs={}")`. `skipped`는 취소와 기한 지남, `interrupted`는 속도 대기 중 interrupt로 멈췄는지다.
   - `private boolean awaitTurn()`: 설계 4.
     - 필드 `long nextSendNanos`(생성할 때 `System.nanoTime()`)와 `long intervalNanos`(`1초 / ratePerSecond`)를 둔다.
     - `nextSendNanos - System.nanoTime()`이 양수면 그만큼 `Thread.sleep`.
     - 그다음 `nextSendNanos = max(현재, nextSendNanos) + intervalNanos`. `nanoTime`은 음수일 수 있으므로 차이로 비교한다.
     - `InterruptedException`이면 interrupt 표시를 되살리고 `false`.
8. **테스트용 가짜 발송** `account.mail.RecordingAccountMailSender`
   - `record SentDigest(String email, List<NewBenefitMailItem> benefits, String siteUrl, long sentAtNanos)`를 기록한다. `sentDigests()`로 읽는다.
   - `failNextDigests(RuntimeException... failures)`: 다음 알림 발송부터 한 번에 하나씩 차례로 던진다. 던진 호출은 기록하지 않고, 다 던지면 다시 기록한다.
   - `clear()`는 알림 기록과 남은 실패도 비운다.
9. **테스트**
   - `notification.service.NewBenefitDigestSendTest`(`@SpringBootTest(properties = "gov24.schedule.enabled=false")`, `TestcontainersConfiguration`, `FixedClockConfiguration`. `NewBenefitDigestTest`와 컨텍스트를 함께 쓴다)
     - 준비: `NewBenefitDigestTest`와 같은 방식으로 새 혜택 X·Y·Z와 사용자, 알림 항목을 만든다. 혜택에는 이름과 운영 기관을 넣는다. 묶음은 시계를 2026-11-05 08:00(KST)으로 옮겨 `bundler.bundleAll()`로 만든다. `@BeforeEach`에서 `RecordingAccountMailSender.clear()`.
     - 테스트 항목:
       - U1(X·Y), U2(X)를 묶고 `sendDue()`를 부르면 두 통이 나간다. U1 메일에 X·Y의 이름·운영 기관·`https://govbenefit.kr/benefits/<서비스ID>`가, U2 메일에 X가 든다. 두 묶음은 `ACCEPTED`, 시도 1번, `acceptedAt`은 시계 시각이다. 다시 `sendDue()`를 불러도 더 보내지 않는다.
       - 묶은 뒤 U1이 알림을 끄면 U1에게는 보내지 않고 `CANCELED`다. 알림을 다시 켜고 `sendDue()`를 불러도 보내지 않는다. U2는 `ACCEPTED`다.
       - (초당 건수 × 2 + 1)명, 곧 21명의 묶음을 보내면 21통이 나가고, 연속한 11건의 `sentAtNanos` 차이가 모두 1초 이상이다. 기록은 대기 직후에 하므로 GC 정지 같은 지연을 감안해 10ms를 허용한다. 대기를 없애면 11건이 약 40ms 안에 나가 실패하는 것을 확인했다.
       - 일시 실패 둘(`retryable=true`) 뒤 성공:
         - 첫 `sendDue()` 뒤 `PENDING`, 시도 1번, 다음 시도 08:01이다.
         - 30초 뒤 `sendDue()`는 보내지 않는다.
         - 08:01에 다시 실패하면 다음 시도 08:06이다.
         - 08:06에 성공하면 `ACCEPTED`, 시도 3번이다.
       - 일시 실패만 6번이면 08:00, 08:01, 08:06, 08:36, 10:36, 16:36에 시도한 뒤 `FAILED`다. 그 뒤 시계를 옮겨 `sendDue()`를 불러도 보내지 않는다.
       - 기한: 시계를 23:58로 옮겨 처음 보낸 묶음이 일시 실패하면 다음 시도 23:59다. 23:59에 다시 실패하면 다음 시도(00:04)가 기한 뒤라 `FAILED`다.
       - 재시도 불가 실패(`retryable=false`)면 첫 시도에서 `FAILED`, 시도 1번이다. 시계를 옮겨도 다시 보내지 않는다.
       - 도중에 멈춘 뒤 이어서:
         - U1 묶음을 만들어 보낸다(`ACCEPTED`). 같은 날 U2·U3 묶음을 `NewBenefitDigestWriter.bundle`로 만든다.
         - 다음 발송이 `IllegalStateException`을 던지게 하고 `sendDue()`를 부르면, 예외가 밖으로 나오며 U2에서 멈춘다. U2·U3는 `PENDING`이고 시도 0번이다.
         - 다시 `sendDue()`를 부르면 U2·U3에게만 보내고 U1에게는 다시 보내지 않는다.
       - 기한이 지난 대기 묶음: 2026-11-04 묶음을 만들고 시계를 11-05 08:00으로 옮겨 `sendDue()`를 부르면 보내지 않고 `FAILED`, 시도 0번이다.
   - `notification.service.NewBenefitDigestSesTest`(`auth.mail.sender=ses`와 `SesStubServer`. `SesMailDeliveryTest`처럼 `@DirtiesContext`, `OutputCaptureExtension`)
     - SES 요청이 묶음마다 한 번이다. 수신 주소는 그 사용자, 제목은 `[정부 혜택 도우미] 조건에 맞는 새 혜택 2건`이다. 본문에 두 혜택의 이름·운영 기관·링크와 '해당'·원문 확인·알림 끄기 안내가 있다.
     - stub이 `429 TooManyRequestsException`으로 답하면 `PENDING`이고 `lastError`가 `SES 응답 상태 429 TooManyRequestsException`이다. 1분 뒤 200으로 답하면 `ACCEPTED`다.
     - 연결이 끊기면 `PENDING`으로 남아 다시 보낸다.
     - stub이 수신 주소를 담은 설명과 함께 `400 MessageRejected`로 답하면 첫 시도에서 `FAILED`다. 출력에 묶음 ID와 `MessageRejected`는 있고, 수신 주소·혜택 이름·접근 키 ID·비밀 키는 없다.
   - `account.mail.SesAccountMailSenderTest`
     - 알림 메일을 SES 형식으로 요청한다: 수신자 하나, 제목·본문 UTF-8, `Html` 없음, 혜택 순서대로.
     - 기존 오류 응답 테스트에 `isRetryable()`을 더한다(400 `MessageRejected` `false`, 429 `true`, 500 `true`). 403 `AccessDeniedException`, 404 `NotFoundException`을 더해 `false`를 본다. 연결 끊김과 연결 실패는 `true`다.
   - 기존 테스트는 바꾸지 않고 통과한다. `NewBenefitDigestTest`는 `PENDING`만 보고, `WithdrawalApiTest`는 `NewBenefitDigest.pending`으로 묶음을 만든다.
10. **개인정보 처리방침** `frontend/src/pages/privacy/PrivacyPolicyPage.tsx`
    - 처리 목적: "혜택 정보 제공: …, 새 혜택 알림 설정 보관"을 "…, 새 혜택 알림 설정 보관과 알림 메일 발송"으로 고친다.
    - 처리 항목: "관심 혜택·알림 설정" 행을 "관심 혜택·알림"으로 바꾸고, 항목을 "저장한 혜택, 새 혜택 알림 켜기·끄기, 알림 기록(알림에 담은 새 혜택 목록과 발송 결과)"으로 고친다.
    - 보유 기간: 첫 행 머리를 "계정(이메일·비밀번호), 프로필, 관심 혜택, 알림 설정, 알림 기록"으로 고친다. 기간("탈퇴할 때까지. 탈퇴하면 즉시 삭제합니다.")은 그대로다.
    - 처리 위탁: Amazon SES의 위탁 업무를 "가입 인증번호·비밀번호 재설정·새 혜택 알림 메일 발송, 반송·수신 거부 주소 관리"로 고친다.
    - 처리방침의 변경:
      - `EFFECTIVE_DATE`를 운영 배포 예정일로, `PREVIOUS_EFFECTIVE_DATE`를 "2026년 11월 5일"로 바꾼다.
      - "이전 처리방침({PREVIOUS_EFFECTIVE_DATE} 시행)에서 바뀐 내용"에 이번 변경 넷(처리 목적, 처리 항목, 보유 기간, 처리 위탁)을 적는다.
      - 기존 목록은 "2026년 11월 5일 시행 처리방침이 그 전 처리방침(2026년 11월 4일 시행)에서 바뀐 내용"으로 바꿔 그 아래에 남긴다.
11. **결정 문서**
    - `docs/decisions/0047-mail-ses-and-new-benefit-mail.md`(0045 대체), `docs/decisions/0048-new-benefit-digest-send.md`는 이 플랜과 함께 썼다.
    - `docs/decisions/0045-account-mail-ses.md`의 상태를 `대체됨 → 0047`로 바꾸고, `docs/decisions/README.md` 목록을 고쳤다.
    - 결정 0045를 가리키는 곳을 0047로 바꾼다. 가리키는 번호만 바꾸고 내용은 그대로 둔다. #127이 0032를 0045로 바꾼 것과 같다.
      - `backend/src/main`·`backend/src/test`의 주석과 설정 주석. `grep -rn "0045" backend/src`로 찾는다. `ApplyPeriodClassifierTest`의 서비스ID는 결정 번호가 아니다.
      - `docs/product/development.md`의 `DEC-OPS(메일 발송)` 행 링크, `docs/ops/mail-domain.md` 첫 문단, `deploy/app/app.env.example`의 주석.
    - 구현을 마치면 두 문서의 `적용 위치`가 실제 파일과 같은지 확인한다.

## 완료 조건 매핑

- [ ] 발송 대기 묶음 하나마다 SES 발송 요청이 한 번 나가고, 메일에 그 묶음의 새 혜택이 모두 들어 있다. → 설계 1·3·7·8. 두 통 테스트, SES 요청 형식 테스트.
- [ ] 보내기 직전에 알림이 꺼진 사용자의 묶음은 보내지 않고, 그 뒤에도 다시 보내지 않는다. → 설계 2·3. 알림을 끈 사용자 테스트.
- [ ] 알림 발송은 정한 초당 건수를 넘지 않는다. → 설계 4. 21명 테스트.
- [ ] 일시 실패한 묶음은 정한 횟수 안에서 다시 보내 성공하면 '접수'로 끝난다. 횟수나 기한을 넘기면 최종 실패로 끝나고 더 보내지 않는다. → 설계 5. 일시 실패 뒤 성공, 6번 실패, 기한 테스트, SES 429 테스트.
- [ ] 다시 보내도 성공할 수 없는 실패는 재시도 없이 최종 실패로 끝난다. → 설계 5·7. 재시도 불가 테스트, SES 400 테스트, 분류 테스트.
- [ ] 보내는 도중 앱이 다시 시작되면, 보내지 않은 묶음을 이어서 보낸다. 이미 접수된 묶음은 다시 보내지 않는다. → 설계 1·6. 도중에 멈춘 뒤 이어서 테스트.
- [ ] 기한이 지난 발송 대기 묶음은 보내지 않고 최종 실패로 끝난다. → 설계 3·6. 기한이 지난 대기 묶음 테스트.
- [ ] 발송 실패 로그에 수신 주소, 메일 본문, 접근 키가 남지 않는다. → 설계 7·10. SES 400 테스트의 출력 확인.
- [ ] 개인정보 처리방침의 처리 목적·처리 항목·위탁 업무·보유 기간이 실제 동작과 같다. → 설계 2·9. 단계 10.

## 검증 명령

- `backend/`에서 `./gradlew test`
- `frontend/`에서 `npm run lint`, `npm run typecheck`, `npm run build`
- 로컬 확인(메일을 보내지 않고 로그로 확인)
  1. 로컬 DB에 새 혜택(`new_benefit`)이 있을 때, 알림을 켠 계정과 알림 항목을 만든다.
     ```sql
     UPDATE user_account SET new_benefit_notify = TRUE WHERE email = '<로컬 계정>';
     INSERT INTO new_benefit_target (user_id, benefit_id)
     SELECT a.id, n.benefit_id FROM user_account a JOIN new_benefit n WHERE a.email = '<로컬 계정>' LIMIT 2;
     ```
  2. 묶기를 매분으로 바꿔 실행한다.
     ```sh
     NOTIFICATION_NEWBENEFIT_DIGESTCRON="0 * * * * *" SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
     ```
  3. 1분 안에 로그에 `새 혜택 묶기`가, 30초 안에 `[메일 미발송] 새 혜택 알림`과 `새 혜택 알림 발송 accepted=1`이 남는지 본다.
  4. 다음 쿼리로 묶음이 `ACCEPTED`, `attempt_count = 1`이고, 다음 폴링에서 다시 보내지 않는지 본다.
     ```sql
     SELECT id, user_id, digest_date, status, attempt_count, next_attempt_at, last_error, accepted_at FROM new_benefit_digest;
     ```
- 실제 SES 확인: `docs/ops/mail-domain.md` 7의 준비를 마친 뒤, 위 1·2를 `AUTH_MAIL_SENDER=ses`를 더해 실행한다. 받은편지함에서 제목·혜택 목록·상세 링크(`http://localhost:3000/benefits/<서비스ID>`)·안내 문구를 확인한다.

## 결정 필요

없음. 아래는 2026-11-06에 사용자가 정했다.

1. 발송 구조는 DB 폴링 작업 하나(30초 cron, 공용 스케줄러 스레드)다(설계 1).
2. 보내기 직전에 알림이 꺼진 사용자의 묶음은 `CANCELED`로 끝낸다(설계 2).
3. 알림 발송 속도는 초당 10건이다. SES 계정 한도는 초당 14건이다(설계 4).
4. 재시도 간격은 1분, 5분, 30분, 2시간, 6시간이고 묶은 날 24:00까지다. 응답 대기 시간 초과를 포함해 SDK 클라이언트 오류는 모두 일시 실패다(설계 5·7).
5. 알림 메일은 `AccountMailSender`에 메서드를 더해 보내고, 0045를 0047로 대체한다(설계 7).
6. 알림 끄기 안내는 사이트 주소와 "로그인한 뒤 알림 설정에서 끄세요"로 한다(설계 8).
7. 처리방침은 처리 목적·처리 항목·위탁·보유 기간을 고치고, 알림 기록은 "알림에 담은 새 혜택 목록과 발송 결과"로 적는다. 시행일은 운영 배포일이다(설계 9).
