# 0048. 새 혜택 알림 묶음의 발송·재시도·복구

- 상태: 채택
- 날짜: 2026-11-06
- 출처: #132, [플랜](../plans/132-new-benefit-notify-send.md)

## 배경

[0046](0046-new-benefit-digest.md)은 매일 08:00에 사용자별 새 혜택 묶음을 발송 대기(`PENDING`)로 저장한다. 묶음 하나는 메일 한 통이다. 이 묶음을 Amazon SES로 보내야 하며, 다음 조건을 지켜야 한다(`REQ-NOTIFY-02` P0).

- 일시 실패는 정한 횟수·기한 안에서 다시 보낸다.
- 발송 성공(SES 접수)과 최종 실패를 구분한다.
- 프로세스가 끝나도 보내지 않은 묶음을 이어서 보낸다.

알림은 계정 메일과 SES 계정의 초당 발송 속도를 나눠 쓰므로, 계정 속도 안에서 나눠 보낸다(`DEC-NOTIFY(새 혜택 알림)`, [0047](0047-mail-ses-and-new-benefit-mail.md)).

## 결정

- 발송 상태와 시도 기록은 묶음 행(`new_benefit_digest`)에 둔다.
  - `status`: `PENDING`(발송 대기·재시도 대기), `ACCEPTED`(SES 접수), `FAILED`(최종 실패), `CANCELED`(보내기 직전 알림이 꺼져 보내지 않음).
  - `attempt_count`(SES에 보낸 횟수), `next_attempt_at`(이 시각부터 보낸다. 묶을 때 묶은 시각), `last_error`(마지막 발송 실패의 예외 메시지), `accepted_at`(SES 접수 시각).
  - '접수'는 SES `SendEmail`의 200 응답이다. 실제 전달과 읽음은 기록하지 않는다.
  - 최종 실패의 이유(횟수 초과, 재시도 불가, 기한)는 상태로 나누지 않는다.
- 발송 작업은 `NewBenefitDigestSender` 하나다.
  - 30초 cron(`notification.new-benefit.send.cron`)으로 Spring 기본 스케줄러 스레드에서 돈다.
  - 실행마다 `PENDING`이고 `next_attempt_at`이 지난 묶음을 ID 순으로 읽어, 대상이 빌 때까지 보낸다.
  - 단일 인스턴스를 전제한다.
- 묶음 하나는 세 단계다.
  - 준비(트랜잭션): 묶은 날 24:00(KST)이 지났으면 `FAILED`, 계정의 알림이 꺼져 있으면 `CANCELED`로 끝낸다. 아니면 수신 주소와 혜택을 읽는다.
  - 발송: 트랜잭션 밖에서 SES를 부른다.
  - 기록(트랜잭션): 결과를 기록한다.
- 속도: SES 호출 시작 간격을 `1초 / notification.new-benefit.send.rate-per-second`(10) 이상으로 둔다. SES 계정 한도는 초당 14건이다.
- 재시도
  - 일시 실패(0047의 `retryable`)면 `notification.new-benefit.send.retry-delays`(1분, 5분, 30분, 2시간, 6시간)의 차례대로 다음 시도 시각을 정한다. 목록의 개수가 재시도 횟수다.
  - 다음 시도 시각이 묶은 날 24:00 이후거나 횟수를 다 썼으면 `FAILED`로 끝낸다.
  - 재시도 불가 실패는 첫 실패에서 `FAILED`다.
- 로그: 실패마다 묶음 ID, 시도 횟수, 재시도 가능 여부, 최종 실패 여부, 예외 메시지만 남긴다.
- 재시작 뒤 이어서 보내는 코드를 따로 두지 않는다. 기동 뒤 첫 폴링이 남은 `PENDING` 묶음을 보내고, 기한이 지난 묶음을 `FAILED`로 끝낸다.

## 근거

- **폴링 작업 하나**
  - 첫 발송, 재시도, 재시작 뒤 재개, 기한 처리가 한 경로다.
  - 묶기(0046)와 같은 스케줄러 스레드라 둘이 동시에 돌지 않는다. 묶기 중에 실행 시각이 된 폴링은 묶기가 끝난 뒤 돈다.
  - 실행이 한 스레드에서만 일어나 같은 묶음을 동시에 보내지 않으므로, 점유 없이 작업자 하나(`REQ-NOTIFY-02` P0)를 지킨다.
  - cron으로 두는 이유는 테스트에서 `-`로 끄기 위해서다. `fixedDelayString`은 `-`를 받지 않는다(Spring Framework 7.0.9 소스).
  - 제외: 정해진 시점마다 실행(묶기 끝, 재시도 cron, 기동 이벤트). 시작 경로가 셋이고, 기동 이벤트는 스케줄러 밖에서 돌아 같은 묶음을 동시에 보낼 수 있다.
  - 제외: 메모리 큐나 비동기 실행기. 재시작하면 큐가 사라져 DB 조회가 따로 필요하고, 여러 스레드라 점유가 필요하다.
- **상태를 묶음 행에 둔다**
  - 0046이 "발송 상태는 묶음 행에 둔다"고 정했다. 폴링 조회가 테이블 하나로 끝난다.
  - `CANCELED`를 따로 두는 이유(2026-11-06 사용자 결정): `FAILED`에 사유를 남기면 측정할 때 실패와 섞이고, 묶음을 지우면 기록이 남지 않는다.
  - 제외: 발송 시도 테이블. 시도마다 이력이 남지만 테이블과 쓰기가 는다. 시도별 결과는 로그로 남긴다.
- **SES 호출을 트랜잭션 밖에서 한다**: SES 응답(최대 소켓 시간 제한 10초)을 기다리는 동안 DB 연결과 행 잠금을 잡지 않는다.
- **속도를 순차 호출 간격으로 지킨다**
  - 작업자가 하나라 간격만으로 설정한 속도를 넘지 않는다. 남은 속도(초당 4건)는 계정 메일 몫이고, 계정 메일은 제한하지 않는다.
  - 대기는 `System.nanoTime()` 기준이라 업무 시계(`Clock`)와 관계없다.
  - 제외: 토큰 버킷 라이브러리. 작업자가 하나라 쓸 곳이 없는 의존성이다.
  - 제외: 계정 메일과 제한기를 함께 씀. 가입 인증번호 메일이 알림 뒤에서 기다린다.
- **간격을 늘려 가며 재시도하고 그날 안에서 끝낸다**
  - 08:00에 첫 시도가 실패해도 마지막 재시도가 16:36이라 그날 안에 끝난다. SES가 몇 시간 멈춰도 횟수를 다 쓰지 않는다.
  - 기한은 이슈(#132)가 정한 묶은 날 24:00이다. 다음 날 08:00에는 그 사용자의 새 묶음이 생길 수 있으므로, 이 기한이 있으면 늦은 묶음과 새 묶음이 같은 날 두 통으로 가지 않는다.
  - 제외: 실패한 자리에서 바로 재시도. 짧은 장애에 횟수를 다 쓰고, 시도 횟수가 메모리에만 있다.
- **재시작 복구를 따로 두지 않는다**
  - 상태의 원천이 DB라 다음 폴링이 이어 간다.
  - `PENDING` 묶음의 다음 시도 시각은 언제나 기한 전이다(기한 뒤가 되면 `FAILED`로 끝낸다). 그래서 기한이 지난 묶음은 반드시 폴링에 읽혀 `FAILED`로 끝난다.
- **한계**
  - 점유 상태(`SENDING`)가 없다. SES가 접수한 뒤 기록이 커밋되기 전에 프로세스가 끝나거나 DB 기록이 실패하면, 그 묶음은 재시작 뒤 다시 보내진다. 응답 대기 시간 초과를 다시 보낼 때도 같다(`REQ-NOTIFY-02` P1).
  - 종료할 때 Spring은 새 폴링을 멈추고, 실행 중인 폴링을 생명주기 종료 단계 제한 시간(기본 30초)까지 기다린 뒤 interrupt한다(Spring Framework 7.0.9 `ExecutorConfigurationSupport` 소스). 그 안에 끝나지 않으면 SES 호출 중에 끊길 수 있다.
  - 발송이 길어지면 같은 스케줄러 스레드의 다른 정기 작업이 그만큼 밀린다.

## 적용 위치

- `backend/src/main/resources/db/migration/V18__add_new_benefit_digest_send.sql`
- `backend/src/main/java/kr/govbenefit/notification/domain/NewBenefitDigest.java`, `NewBenefitDigestStatus.java`
- `backend/src/main/java/kr/govbenefit/notification/repository/NewBenefitDigestRepository.java`, `NewBenefitTargetRepository.java`
- `backend/src/main/java/kr/govbenefit/notification/service/NewBenefitDigestSender.java`, `NewBenefitDigestSendWriter.java`
- `backend/src/main/java/kr/govbenefit/notification/config/NotificationConfig.java`, `NewBenefitSendProperties.java`
- `backend/src/main/resources/application.yaml`: `notification.new-benefit.send`. `application-local.yaml`: `notification.new-benefit.send.site-url`
- `backend/src/test/resources/config/application.yaml`: `notification.new-benefit.send.cron: "-"`
- `backend/src/test/java/kr/govbenefit/notification/service/NewBenefitDigestSendTest.java`, `NewBenefitDigestSesTest.java`
- 알림 기록의 보유 기간: `frontend/src/pages/privacy/PrivacyPolicyPage.tsx`

## 영향

- 같은 SES 계정으로 보내는 다른 알림이 생기면 이 작업의 속도 제한 안에서 함께 보낸다. 작업마다 따로 초당 10건이면 계정 한도를 넘는다.
- 발송 작업을 여러 인스턴스나 여러 스레드에서 돌리게 되면 같은 묶음의 동시 발송을 막는 점유가 필요하므로 이 결정을 대체한다.
- 묶음과 알림 항목은 탈퇴할 때까지 남는다(0046의 `ON DELETE CASCADE`). 보유 기간을 바꾸면 처리방침을 함께 고친다.
- 발송 상태 값이나 재시도 규칙을 바꾸면 이 결정을 대체한다.
