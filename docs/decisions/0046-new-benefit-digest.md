# 0046. 새 혜택 알림 묶음과 알림 항목의 연결

- 상태: 채택
- 날짜: 2026-11-05
- 출처: #130, [플랜](../plans/130-new-benefit-notify-digest.md)

## 배경

새 혜택 알림은 사용자별로 하루치 새 혜택을 한 통으로 묶어 매일 08:00(KST)에 보낸다(`DEC-NOTIFY(새 혜택 알림)`). #117은 (사용자, 새 혜택) 알림 항목을 `new_benefit_target`에 저장하지만, 어느 항목을 묶었는지와 발송 대기인지는 남기지 않는다. 발송 의도는 DB에 남겨 프로세스가 종료돼도 아직 보내지 않은 발송을 복구할 수 있어야 한다(`REQ-NOTIFY-02`). 후속 발송 작업이 묶음을 그대로 읽으므로, 묶음을 저장하는 방식과 항목을 잇는 방식을 정한다.

## 결정

- 묶음은 `new_benefit_digest` 행 하나이고 메일 한 통이다. 사용자·묶은 날짜(`digest_date`, KST)당 하나이며(`UNIQUE(user_id, digest_date)`), 만들 때 상태는 발송 대기(`PENDING`)다.
- 알림 항목은 `new_benefit_target.digest_id`로 묶음을 가리킨다. 비어 있으면 아직 묶지 않은 항목이다. 묶음의 내용은 이 컬럼으로 읽는다.
- 매일 08:00 묶기는 알림이 꺼진 사용자의 묶지 않은 항목을 먼저 지우고, 알림을 켠 사용자마다 묶지 않은 항목을 모두 그날 묶음 하나에 넣는다. 사용자마다 한 트랜잭션이다. 그날 묶음이 이미 있는 사용자는 건너뛰고, 남은 항목은 다음 날 묶음에 들어간다.
- 계정이 지워지면 묶음도 지워지고(`user_id` `ON DELETE CASCADE`), 묶음이 지워지면 그 항목도 지워진다(`digest_id` `ON DELETE CASCADE`).

## 근거

- 묶음을 가리키는 컬럼이 하나라서 한 항목이 두 묶음에 들어갈 수 없다. 묶지 않은 항목은 FK가 만드는 `digest_id` 인덱스로 찾는다.
- `UNIQUE(user_id, digest_date)` 때문에 같은 날 다시 실행해도 그날 묶음은 하나다.
- 사용자마다 트랜잭션이라 중단돼도 만든 묶음은 그대로 남는다. 남은 항목은 `digest_id`가 비어 있어 다음 실행이 이어서 묶는다.
- 그날 묶음에 늦게 생긴 항목을 덧붙이지 않는 이유: 발송 작업이 읽고 있는 묶음의 내용이 바뀔 수 있다.
- `digest_id`를 `ON DELETE CASCADE`로 둔 이유: 기본값(`RESTRICT`)이면 탈퇴의 연쇄 삭제에서 묶음이 항목보다 먼저 지워질 때 실패한다. `SET NULL`이면 항목이 묶지 않은 상태로 돌아가 다시 묶이고 다시 보내진다.
- 알림이 꺼진 사용자의 항목을 끌 때가 아니라 08:00에 지우는 이유: 이슈가 정한 동작이 "08:00에 알림이 꺼진 사용자"이고, 설정 API를 바꾸지 않는다.
- 제외한 대안
  - 묶음 항목 테이블을 따로 둠: 선정 기록은 바뀌지 않지만 테이블이 늘고, 묶지 않은 항목을 반조인으로 찾는다. 알림이 꺼진 사용자의 항목은 결국 선정 테이블에서 지운다.
  - 묶을 때 항목을 묶음 쪽으로 옮기고 선정 테이블 행을 지움: 행이 있으면 묶지 않은 항목이라 뜻은 단순하다. 하지만 옮기는 도중의 유실·중복을 막아야 하고, 같은 혜택을 두 번 선정하면 PK로 실패하던 안전장치가 묶은 뒤에는 없다.
  - 집합 SQL 한 번으로 전부 묶음: 빠르지만 큰 트랜잭션 하나가 항목과 계정 행을 오래 잡는다.
  - 기동 시 놓친 묶기를 따라잡음: 오후에 재시작하면 오후에 묶여 08:00 발송이 흐려지고, 실행 경로가 둘이 된다.

## 적용 위치

- `backend/src/main/resources/db/migration/V17__create_new_benefit_digest.sql`
- `backend/src/main/java/kr/govbenefit/notification/domain/NewBenefitDigest.java`, `NewBenefitDigestStatus.java`, `NewBenefitTarget.java`
- `backend/src/main/java/kr/govbenefit/notification/repository/NewBenefitDigestRepository.java`, `NewBenefitTargetRepository.java`
- `backend/src/main/java/kr/govbenefit/notification/service/NewBenefitDigestBundler.java`, `NewBenefitDigestWriter.java`
- `backend/src/main/resources/application.yaml`: `notification.new-benefit.digest-cron`
- `backend/src/test/java/kr/govbenefit/notification/service/NewBenefitDigestTest.java`, `backend/src/test/java/kr/govbenefit/account/controller/WithdrawalApiTest.java`

## 영향

- 발송 작업은 묶음 하나를 메일 한 통으로 보내고, 묶음의 내용을 `digest_id`로 읽는다. 묶은 뒤에는 묶음의 항목을 바꾸지 않는다.
- 발송 상태는 묶음 행에 둔다. `status`에 값을 더할 때 이 결정을 대체하지 않는다.
- 알림 항목을 저장하는 다른 경로가 생기면 `digest_id`를 비운 채 넣는다.
- 묶음을 지우는 작업을 만들면 그 항목도 함께 지워진다.
