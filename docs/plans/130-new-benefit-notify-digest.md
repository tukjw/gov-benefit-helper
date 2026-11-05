status: in-progress

# 구현 계획 — #130 매일 08:00에 사용자별 새 혜택 묶음을 발송 대기로 저장

브랜치: `feat/130-new-benefit-notify-digest` (develop `f7d23a5` 기준)

## 목표 및 범위

- 목표: 매일 08:00(KST)에 알림을 켠 사용자마다 아직 묶지 않은 알림 항목을 모두 묶음 하나로 만들어 발송 대기로 저장한다(`REQ-NOTIFY-03`, `REQ-NOTIFY-02`, `DEC-NOTIFY(새 혜택 알림)`). 후속 발송 이슈가 이 묶음을 읽어 보낸다.
- 포함:
  - `new_benefit_digest` 테이블과 `new_benefit_target.digest_id` 컬럼(V17).
  - 08:00 묶기 작업(`kr.govbenefit.notification`)과 cron 설정.
  - 묶기 테스트, 탈퇴 테스트 보강.
  - 결정 문서 0046.
- 제외(이슈 본문):
  - 메일 작성, SES 발송, 재시도, 발송 직전의 알림 설정 확인(후속 이슈).
  - 08:00에 묶기 전에 아직 대상을 선정하지 않은 새 혜택을 먼저 선정하는 일(#117).
  - 알림 설정 화면(후속 이슈).
- 제외(이 플랜):
  - 기동 시 놓친 묶기를 따라잡는 실행(설계 4).
  - 그날 묶음에 늦게 생긴 항목을 덧붙이는 일(설계 5).
  - 발송 상태 값(발송 중·완료·실패)과 재시도 컬럼. 발송 이슈에서 더한다.

## 확인된 사실과 제약

- 알림 항목은 `new_benefit_target(user_id, benefit_id)`이고 PK가 두 컬럼이다. `user_id`는 `user_account`를 `ON DELETE CASCADE`로 가리킨다. 시각 컬럼과 발송 상태 컬럼은 없다. #117 설계 4가 "묶음 발송과 발송 의도는 발송 이슈에서 정한다"고 미뤘다.
- 알림 항목은 수집 종료 이벤트에서 혜택마다 한 트랜잭션으로 저장된다(`NewBenefitTargetWriter.save`). 쓰기는 네이티브 `INSERT`(`NewBenefitTargetRepository.insert`)이고, 엔티티 `NewBenefitTarget`은 `@IdClass`이며 생성 메서드가 없다.
- 알림 설정은 `user_account.new_benefit_notify`다. 끄기는 `UserAccount.changeNewBenefitNotify`로 값만 바꾼다.
- `@EnableScheduling`은 `CollectionConfig`에 있고 스케줄러 스레드는 기본값 하나다. 정기 작업은 cron을 설정값으로 두고 `@Scheduled(cron = "${...}", zone = "Asia/Seoul")`로 돈다(수집 04:00 `gov24.schedule.cron`, 이메일 인증 기록 파기 04:30 `auth.email-verification.purge-cron`). 두 작업 모두 단일 인스턴스를 전제한다.
- `Clock` 빈은 `Asia/Seoul`이다(`CollectionConfig`, 결정 0002).
- 테스트 컨텍스트에도 `@Scheduled`가 등록된다. 정기 수집은 클래스마다 `gov24.schedule.enabled=false`로 끄거나, 인증키가 없어 건너뛴다. 묶기에는 그런 조건이 없어서 08:00에 테스트가 돌면 테스트가 넣은 항목을 묶는다.
- 탈퇴(`AccountService.withdraw`)는 계정 행만 지우고 사용자 데이터는 FK로 함께 지워진다. 탈퇴 설명(`OpenApiConfig.DESCRIPTION`, `MeController`)에는 이미 '미발송 알림'이 있다.
- `DatabaseCleanupListener`는 시드를 뺀 모든 테이블을 `information_schema`에서 찾아 비우므로 새 테이블을 따로 등록하지 않는다. `WithdrawalApiTest.USER_TABLES`는 직접 적은 목록이다.
- 고정 시계는 `FixedClockConfiguration`의 `MutableClock`이고, 테스트마다 `set`으로 기준 시각을 맞춘다.
- 다음 마이그레이션 번호는 V17, 다음 결정 번호는 0046이다.

## 설계 결정

1. **묶음은 `new_benefit_digest` 행 하나이고, 항목은 `new_benefit_target.digest_id`로 묶음을 가리킨다.** 2026-11-05 사용자 결정. → [결정 0046](../decisions/0046-new-benefit-digest.md)
   - 묶음은 사용자·묶은 날짜(`digest_date`, KST)당 하나다(`UNIQUE(user_id, digest_date)`). 같은 날 다시 실행해도 그날 묶음은 하나다.
   - `digest_id`가 비어 있으면 아직 묶지 않은 항목이다. 컬럼이 하나라서 한 항목이 두 묶음에 들어갈 수 없다. 묶지 않은 항목은 FK가 만드는 `digest_id` 인덱스로 찾으므로 인덱스를 더하지 않는다.
   - 대안: 묶음 항목 테이블을 따로 둠. 선정 기록은 그대로 남지만 테이블이 늘고, 묶지 않은 항목을 반조인으로 찾는다. 알림을 끈 사용자의 항목은 결국 선정 테이블에서 지운다.
   - 대안: 묶을 때 항목을 묶음 쪽으로 옮기고 선정 테이블 행을 지움. 뜻은 단순하지만 옮기는 도중의 유실·중복을 막아야 하고, 같은 혜택을 두 번 선정하면 PK로 실패하던 안전장치가 묶은 뒤에는 없다.
2. **묶기는 사용자마다 한 트랜잭션으로 한다.** 2026-11-05 사용자 결정.
   - 한 트랜잭션에서 그날 묶음을 만들고, 그 사용자의 묶지 않은 항목에 묶음 ID를 넣는다. 중단돼도 만든 묶음은 남고, 남은 사용자는 다음 실행이 이어서 묶는다. #117의 혜택별 트랜잭션과 같은 모양이다.
   - 묶을 사용자는 알림을 켰고 묶지 않은 항목이 있는 사용자를 ID 순으로 1,000명씩 읽는다.
   - 실패하면 예외를 잡아 로그만 남긴다(#117과 같다). 남은 사용자는 다음 날 묶는다.
   - 한계:
     - 묶을 사용자를 읽은 뒤 그 사용자가 탈퇴하면 묶음 저장이 FK 위반으로 실패하고, 그 뒤 사용자는 다음 날 묶인다.
     - 묶을 사용자를 읽은 뒤 알림을 끄면 묶음이 생긴다. 발송 직전 설정 확인은 후속 이슈다.
     - 선정이 묶기와 겹치면, 한 사용자를 묶은 뒤 커밋된 항목은 다음 날 묶인다. 잃거나 겹치지는 않는다.
   - 대안: 집합 SQL 한 번(`INSERT … SELECT`, `UPDATE … JOIN`)으로 전부 묶음. 빠르지만 큰 트랜잭션 하나가 항목과 계정 행을 오래 잡는다.
3. **알림이 꺼진 사용자의 묶지 않은 항목은 08:00 묶기에서 먼저 지운다.** 2026-11-05 사용자 결정.
   - 이슈 문구("08:00에 알림이 꺼진 사용자")와 같다. 08:00 전에 껐다가 다시 켜면 항목이 남는다. 설정 API는 바꾸지 않는다.
   - 이미 묶은 항목은 지우지 않는다. 발송 직전 설정 확인은 후속 이슈다.
   - 대안: 끌 때 바로 지움. 08:00 전에 껐다 다시 켜도 지워져 이슈 동작과 다르고, 설정 API로 범위가 번진다.
4. **실행은 08:00 cron 하나다.** 2026-11-05 사용자 결정.
   - 08:00에 앱이 내려가 있으면 그날 항목은 다음 날 묶음에 들어간다(이슈 본문).
   - 대안: 기동 시 따라잡기. 오후에 재시작하면 오후에 묶여 08:00 발송이 흐려지고, 오늘 실행을 마쳤는지 따로 기록해야 하며 실행 경로가 둘이다.
5. **그날 묶음이 이미 있는 사용자는 건너뛴다.** 2026-11-05 사용자 결정.
   - 그날 묶음을 만든 뒤 생긴 항목은 다음 날 묶음에 들어간다. cron만 쓰므로 같은 날 다시 실행하는 경우는 수동 실행과 테스트뿐이다.
   - 묶음이 있는지는 먼저 조회해서 본다. 단일 인스턴스와 스케줄러 스레드 하나라 동시에 두 실행이 돌지 않으므로, UNIQUE 위반은 잡지 않고 실패로 둔다.
   - 대안: 아직 발송 대기인 그날 묶음에 덧붙임. 발송 작업이 읽고 있는 묶음의 내용이 바뀔 수 있다.
6. **상태는 `status`에 발송 대기(`PENDING`) 하나만 둔다.**
   - 발송 상태 값과 재시도 컬럼은 발송 이슈에서 더한다. `VARCHAR`라 값을 더할 때 스키마를 바꾸지 않는다.
7. **탈퇴하면 묶음과 항목이 FK로 함께 지워진다.**
   - `new_benefit_digest.user_id`는 `user_account`를 `ON DELETE CASCADE`로 가리킨다. `AccountService.withdraw`는 바꾸지 않는다.
   - `new_benefit_target.digest_id`도 `ON DELETE CASCADE`다. 기본값(`RESTRICT`)이면 탈퇴의 연쇄 삭제에서 묶음이 항목보다 먼저 지워질 때 실패한다. `SET NULL`이면 항목이 묶지 않은 상태로 돌아가 다시 묶이고 다시 보내진다.
8. **테스트에서는 묶기 cron을 끈다.**
   - 테스트 공통 설정에서 cron을 `-`(`Scheduled.CRON_DISABLED`)로 둔다. 묶기 테스트는 `bundleAll()`을 직접 부른다.

## 단계별 구현 계획

1. **마이그레이션** `V17__create_new_benefit_digest.sql`
   ```sql
   -- 새 혜택 알림 묶음(#130, REQ-NOTIFY-03). 사용자별로 그날 보낼 새 혜택을 메일 한 통으로 묶는다. 사용자·묶은 날짜당 하나다.
   -- status: PENDING(발송 대기). 탈퇴하면 함께 지워진다(DEC-AUTH).
   CREATE TABLE new_benefit_digest (
       id          BIGINT      NOT NULL AUTO_INCREMENT,
       user_id     BIGINT      NOT NULL,
       digest_date DATE        NOT NULL,
       status      VARCHAR(20) NOT NULL,
       created_at  DATETIME(6) NOT NULL,
       PRIMARY KEY (id),
       CONSTRAINT uk_new_benefit_digest_user_date UNIQUE (user_id, digest_date),
       CONSTRAINT fk_new_benefit_digest_user FOREIGN KEY (user_id) REFERENCES user_account (id) ON DELETE CASCADE
   );

   -- 알림 항목을 넣은 묶음. 비어 있으면 아직 묶지 않았다. 묶음이 지워지면 항목도 지워진다.
   ALTER TABLE new_benefit_target
       ADD COLUMN digest_id BIGINT NULL AFTER benefit_id,
       ADD CONSTRAINT fk_new_benefit_target_digest
           FOREIGN KEY (digest_id) REFERENCES new_benefit_digest (id) ON DELETE CASCADE;
   ```
2. **묶음** `notification.domain.NewBenefitDigest`, `NewBenefitDigestStatus`, `notification.repository.NewBenefitDigestRepository`
   - 엔티티: `id`(`IDENTITY`), `userId`, `digestDate`, `status`(`EnumType.STRING`), `createdAt`.
   - `static NewBenefitDigest pending(Long userId, LocalDate digestDate, LocalDateTime now)`.
   - `NewBenefitDigestStatus { PENDING }`.
   - 저장소: `boolean existsByUserIdAndDigestDate(Long userId, LocalDate digestDate)`.
3. **알림 항목** `notification.domain.NewBenefitTarget`, `notification.repository.NewBenefitTargetRepository`
   - 엔티티에 `Long digestId`를 더한다. 주석: 넣은 묶음. 비어 있으면 아직 묶지 않았다.
   - `List<Long> findUnbundledNotifyUserIds(@Param("afterId") Long afterId, Limit limit)`:
     `select distinct t.userId from NewBenefitTarget t join UserAccount a on a.id = t.userId where a.newBenefitNotify = true and t.digestId is null and t.userId > :afterId order by t.userId`.
   - `@Modifying` 네이티브 `int assignDigest(@Param("userId") Long userId, @Param("digestId") Long digestId)`:
     `UPDATE new_benefit_target SET digest_id = :digestId WHERE user_id = :userId AND digest_id IS NULL`.
   - `@Modifying` 네이티브 `int deleteUnbundledOfNotifyOff()`:
     `DELETE t FROM new_benefit_target t JOIN user_account a ON a.id = t.user_id WHERE a.new_benefit_notify = FALSE AND t.digest_id IS NULL`.
   - 기존 `insert`는 바꾸지 않는다. `digest_id`는 비어 있는 채로 들어간다.
4. **묶음 저장** `notification.service.NewBenefitDigestWriter`
   - `@Transactional int deleteUnbundledOfNotifyOff()`: 저장소 메서드를 부르고 지운 행 수를 돌려준다.
   - `@Transactional boolean bundle(Long userId, LocalDate digestDate, LocalDateTime now)`: 그날 묶음이 있으면 `false`를 돌려준다. 없으면 `NewBenefitDigest.pending`을 저장하고 `assignDigest(userId, digest.getId())`를 부른 뒤 `true`를 돌려준다.
   - 클래스 주석: 사용자 하나의 묶음과 항목 연결을 한 트랜잭션으로 저장한다. 중단돼도 사용자마다 묶음과 항목이 함께 있거나 함께 없다. `NewBenefitDigestBundler`만 부른다.
5. **묶기** `notification.service.NewBenefitDigestBundler`(트랜잭션 없음)
   - `@Scheduled(cron = "${notification.new-benefit.digest-cron}", zone = "Asia/Seoul") public void bundleDaily()`: `bundleAll()`을 부르고, `RuntimeException`을 잡아 `log.error("새 혜택 묶기 실패", e)`로 남긴다.
   - `public void bundleAll()`
     - `digestDate`·`now`를 `Clock`으로 한 번 정한다.
     - `writer.deleteUnbundledOfNotifyOff()`를 부른다.
     - `findUnbundledNotifyUserIds(afterId, Limit.of(1000))`로 사용자를 읽고, 사용자마다 `writer.bundle(userId, digestDate, now)`를 부른다. 비면 끝낸다.
     - `log.info("새 혜택 묶기 date={} deletedTargets={} digests={} skippedUsers={} elapsedMs={}")`를 남긴다.
   - 클래스 주석: `REQ-NOTIFY-03`, `DEC-NOTIFY`. 알림을 켠 사용자마다 묶지 않은 항목을 모두 그날 묶음 하나에 넣는다. 선정 날짜와 관계없이 묶으므로 묶기가 돌지 않은 날의 항목은 다음 묶음에 들어간다. 단일 인스턴스를 전제한다.
6. **설정**
   - `application.yaml`:
     ```yaml
     # 새 혜택 알림 묶기(결정 0046). 매일 08:00(Asia/Seoul)에 알림을 켠 사용자마다 묶지 않은 항목을 묶음 하나로 만든다.
     notification:
       new-benefit:
         digest-cron: "0 0 8 * * *"
     ```
   - 테스트 `config/application.yaml`: `notification.new-benefit.digest-cron: "-"`. 주석: 정해진 시각에 돌아 테스트가 넣은 항목을 묶지 않게 끈다. 묶기 테스트는 `bundleAll()`을 직접 부른다.
7. **테스트**
   - `notification.service.NewBenefitDigestTest`(`@SpringBootTest(properties = "gov24.schedule.enabled=false")`, `TestcontainersConfiguration`, `FixedClockConfiguration`)
     - 준비: 혜택 셋(X, Y, Z)을 `Benefit.create`로 만들고(`WithdrawalApiTest`의 `setUp`과 같다), 수집 실행 하나와 `NewBenefit.detect`로 새 혜택을 기록한다. 사용자는 `UserAccountRepository`로 만들고, 알림 항목은 `JdbcTemplate`으로 넣는다. 묶는 날짜는 시계를 그날 08:00(KST)으로 옮겨 정한다.
     - 테스트 항목:
       - U1(알림 켬, X·Y), U2(알림 켬, X), U3(알림 켬, 항목 없음)을 묶으면 U1과 U2에게만 2026-11-05 `PENDING` 묶음이 하나씩 생기고, 항목은 U1 묶음에 X·Y, U2 묶음에 X다.
       - 11-03에 X를 묶고, 11-04에는 묶기를 돌리지 않은 채 Y·Z를 넣은 뒤 11-05에 묶으면, 11-05 묶음 하나에 Y·Z가 함께 들고 11-03 묶음에는 X만 남는다.
       - 11-05에 두 번 묶어도 사용자당 11-05 묶음은 하나다. 첫 실행 뒤 넣은 Y는 묶이지 않고 남았다가 11-06 묶음에 들고, 11-05 묶음에는 X만 남는다.
       - 알림을 켠 채 11-04에 X를 묶은 사용자가 알림을 끄고 Y를 받은 상태에서 11-05에 묶으면, Y는 지워지고 그 사용자의 11-05 묶음은 없다. 11-04 묶음과 X는 그대로다. 알림을 켠 다른 사용자의 Y는 11-05 묶음에 든다. 알림을 다시 켜고 11-06에 묶어도 묶음이 생기지 않는다.
       - 중단 뒤 이어서: U1·U2에게 항목이 있을 때 `writer.bundle(U1, …)`만 불러 "U1까지 묶고 멈춘 상태"를 만든다. `bundleAll()`을 부르면 U2 묶음이 생기고, U1 묶음은 그대로 하나이며 두 묶음의 항목이 겹치지 않고 묶지 않은 항목이 없다.
   - `account.controller.WithdrawalApiTest`
     - `USER_TABLES`에 `new_benefit_digest`를 더한다.
     - 도우미 `saveNewBenefitTarget(userId)`를 `saveNewBenefitDigest(userId)`로 바꾼다. 그 사용자의 `PENDING` 묶음을 `NewBenefitDigest.pending`으로 저장하고, 대상 행을 그 묶음의 `digest_id`와 함께 넣는다(`JdbcTemplate`).
     - 탈퇴 테스트 이름에 묶음을 더한다(`탈퇴하면_계정과_프로필_속성_북마크_알림_대상_묶음_토큰이_남지_않는다`).
     - 탈퇴 테스트와 다른 사용자에게 영향이 없는지 보는 테스트가 `USER_TABLES`를 반복해 확인하므로, 묶음과 묶은 항목이 함께 지워지는지와 bob의 묶음이 남는지를 같이 본다.
   - 기존 테스트는 바꾸지 않고 통과한다. 선정 테스트의 대상 행은 `digest_id`가 비어 있다.
8. **결정 문서** `docs/decisions/0046-new-benefit-digest.md`와 `docs/decisions/README.md` 목록은 이 플랜과 함께 썼다. 구현을 마치면 `적용 위치`의 파일 이름이 실제와 같은지 확인한다.

## 완료 조건 매핑

- [ ] 08:00(KST)에 알림을 켠 사용자마다, 아직 묶지 않은 알림 항목이 모두 든 묶음 하나가 발송 대기로 저장된다. 묶을 항목이 없는 사용자에게는 묶음이 생기지 않는다. → 설계 1·2·4·6. U1\~U3 테스트.
- [ ] 묶기가 돌지 않은 날의 알림 항목은 다음 묶기에서 함께 묶인다. → 설계 1·4. 11-04에 묶기가 돌지 않은 날 테스트.
- [ ] 08:00에 알림이 꺼진 사용자의 아직 묶지 않은 알림 항목은 묶이지 않고 지워진다. 그 뒤 알림을 다시 켜도 그 항목은 묶이지 않는다. → 설계 3. 알림을 끈 사용자 테스트.
- [ ] 이미 묶은 알림 항목은 다음 묶음에 다시 들어가지 않는다. 같은 날 묶기를 다시 실행해도 사용자당 그날 묶음은 하나다. → 설계 1·5. 11-04에 묶기가 돌지 않은 날 테스트(11-03 묶음), 같은 날 두 번 묶기 테스트.
- [ ] 묶기가 도중에 중단된 뒤 다시 실행하면 남은 알림 항목을 빠짐없이 묶고, 이미 만든 묶음과 항목이 겹치지 않는다. → 설계 1·2. 중단 뒤 이어서 테스트.
- [ ] 탈퇴한 사용자의 발송 대기 묶음이 삭제된다. → 설계 7. `WithdrawalApiTest`.

## 검증 명령

- `backend/`에서 `./gradlew test`
- 로컬 확인: 로컬 DB에 알림을 켠 계정과 알림 항목이 있을 때, 묶기 cron을 매분으로 바꿔 실행한다.
  ```sh
  NOTIFICATION_NEWBENEFIT_DIGESTCRON="0 * * * * *" SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
  ```
  1분 뒤 다음을 실행한다.
  ```sql
  SELECT * FROM new_benefit_digest;
  SELECT t.*, d.digest_date FROM new_benefit_target t LEFT JOIN new_benefit_digest d ON d.id = t.digest_id;
  ```
  다음을 확인한다.
  - 알림을 켠 사용자마다 그날 묶음이 하나 있고, 그 사용자의 항목이 모두 그 묶음을 가리킨다.
  - 다음 실행(1분 뒤)에도 묶음이 늘지 않는다.
  - 로그에 `새 혜택 묶기`가 실행마다 한 줄씩 남는다.

## 결정 필요

없음
