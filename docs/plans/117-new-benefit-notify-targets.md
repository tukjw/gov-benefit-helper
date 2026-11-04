status: done

# 구현 계획 — #117 새 혜택에 조건이 해당하는 알림 사용자를 골라 알림 항목으로 저장

브랜치: `feat/117-new-benefit-notify-targets` (develop `7c57da1` 기준)

## 목표 및 범위

- 목표: 수집이 끝나면 판단 가능 상태인 새 혜택마다, 알림을 켠 사용자 중 조건 비교 결과가 '해당'인 사용자를 골라 (사용자, 혜택) 알림 항목으로 저장한다(`REQ-NOTIFY-03` P0, `DEC-NOTIFY(새 혜택 알림)`).
- 포함:
  - `new_benefit.targets_selected_at` 컬럼과 `new_benefit_target` 테이블(V16).
  - 수집 종료 이벤트에서 도는 대상 선정(`kr.govbenefit.notification`).
  - 혜택 하나의 판정 입력을 만드는 `MatchCandidateLoader` 메서드.
  - 탈퇴 설명 문구, 선정 테스트, 탈퇴 테스트.
- 제외(이슈 본문):
  - 메일 작성과 하루 묶음 발송, 발송 시점의 알림 설정 확인(후속 이슈).
  - 대상 선정 시간과 DB 부하 측정, 기존 혜택의 조건이 바뀌어 새로 해당하게 된 경우(`REQ-NOTIFY-03` P1).
  - 선정 뒤 프로필을 바꿔 새로 해당하게 된 혜택.
- 제외(이 플랜):
  - 수집 종료 이벤트 밖의 선정 실행(주기 작업, 기동 시·발송 전 따라잡기). 후속 제안에 적는다.
  - SQL로 대상 후보를 미리 거르는 최적화. 부하 측정 뒤에 정한다.
  - 알림 항목의 발송 상태 컬럼. 발송 이슈에서 정한다.

## 확인된 사실과 제약

- 새 혜택(`new_benefit`)은 목록 쪽 저장에서 대기(`WAITING_CONDITIONS`)로 기록되고, 지원조건 쪽을 저장할 때 판단 가능(`READY`)으로 바뀐다(결정 0042). 같은 실행에서 목록 다음에 지원조건을 받으므로, 보통 수집이 끝날 때 이미 `READY`다. 0042의 영향: "대상 선정은 `READY`인 행만 읽는다."
- `CollectionService.collectAll`은 실행 기록을 저장한 뒤 상태(성공·부분 실패·실패)와 관계없이 `CollectionRunFinished`를 낸다. 리스너는 수집과 같은 스레드에서 차례로 돈다. 프로세스가 수집 도중 죽으면 이벤트가 나지 않는다.
- 같은 이벤트를 `MatchCandidateStore.refresh`가 받는다. 교체에 실패하면 예외를 수집 쪽으로 던진다(결정 0038). 리스너가 예외를 던지면 그 뒤에 등록된 리스너는 돌지 않는다.
- 스케줄러 스레드는 기본값 하나다. 수집(04:00)과 이메일 인증 기록 파기(04:30)가 이 스레드를 나눠 쓴다.
- 조건 비교 판정은 `ConditionMatcher.match(subject, conditions, userType, agencyRegion)`이다. 상세 비교(`MatchService.match`)는 요청마다 DB에서 혜택·지원조건 원문·운영 기관 지역을 읽고, 목록(`MatchService.list`)은 같은 값을 담은 `MatchCandidate`로 판정한다. 결정 0038: 판정에 혜택 쪽 값을 더하면 `MatchCandidate`에도 더한다.
- 사용자 쪽 입력은 `MatchSubject.of(UserProfile, LocalDate)`다. 프로필이 없으면 `null`을 넘겨 모든 값이 미입력이다. `UserProfileRepository`의 `@EntityGraph` 조회는 속성 코드까지 읽어 트랜잭션 밖에서도 쓸 수 있다.
- 조건 비교 결과는 해당(`MATCH`)·확인 필요(`NEEDS_CHECK`)·미해당(`MISMATCH`)이다. 미해당은 개인 대상이 아닌 혜택에만 나온다.
- 알림 설정은 `user_account.new_benefit_notify`다(#106). #106·#107 플랜은 대상 선정에 쓸 인덱스를 이 이슈에서 정하기로 했다.
- 사용자 데이터는 `user_id` FK의 `ON DELETE CASCADE`로 계정과 함께 지워진다(`bookmark`, `user_profile`, `refresh_token`, `password_reset_token`). `AccountService.withdraw`는 계정 행만 지운다. `DEC-AUTH`: 탈퇴하면 미발송 알림을 즉시 삭제한다.
- 북마크는 복합 PK `(user_id, benefit_id)`이고, 엔티티는 `@IdClass`이며 쓰기는 저장소의 네이티브 쿼리로만 한다(`Bookmark`, `BookmarkRepository.upsert`).
- 테스트 fixture C(`O00024000031`, 체육시설 이용요금 감면(경로))는 개인 대상이고, 지원조건이 65\~120세·남녀이며 생애·가구 코드가 여럿 있다. 운영 기관은 용산구시설관리공단(`3020000`, 서울 용산구)이다.
- 테스트 커넥션 풀은 쉬는 컨텍스트가 연결을 1\~2개만 쥔다(결정 0043). 테스트 클래스를 새로 만들어 컨텍스트가 늘어도 된다.
- 다음 마이그레이션 번호는 V16이다.

## 설계 결정

1. **선정은 수집 종료 이벤트(`CollectionRunFinished`)에서 한다.** 2026-11-04 사용자 결정.
   - 실행 상태와 관계없이, `READY`이고 `targets_selected_at`이 비어 있는 새 혜택을 모두 고른다. 이전 실행에서 남은 혜택도 여기서 이어서 처리한다.
   - 리스너는 선정 중 예외를 잡아 로그만 남기고 다시 던지지 않는다. 조건 비교 목록 교체가 선정 실패 때문에 막히지 않게 하기 위해서다. 남은 혜택은 다음 수집이 끝날 때 선정한다.
   - 한계:
     - 수집이나 선정 도중 프로세스가 죽거나 선정이 실패하면, 다음 수집(다음 날 04:00)이 끝난 뒤에 선정한다. 그날 08:00 묶음에서 빠지고 하루 늦게 가지만 잃지는 않는다.
     - 조건 비교 목록 교체가 먼저 돌다 실패하면 선정이 돌지 않는다. 이 경우도 같은 방식으로 다음 수집 뒤에 선정한다.
     - 선정이 끝날 때까지 같은 스케줄러 스레드의 04:30 파기 작업이 기다린다.
   - 대안: 주기 작업(`@Scheduled`). 재시작 뒤 곧바로 이어서 처리하지만, 실행 경로가 수집과 따로 있고 대부분의 실행이 빈 조회다.
   - 대안: 이벤트에 따라잡기 실행(기동 시 또는 발송 전)을 더함. 실행 경로가 둘이 되고, 발송 전 따라잡기는 발송 이슈의 범위다.
2. **선정을 마친 혜택은 `new_benefit.targets_selected_at`에 시각을 남긴다.** 2026-11-04 사용자 결정. 컬럼 이름도 사용자가 정했다.
   - 혜택 하나의 대상 저장과 이 시각 기록을 한 트랜잭션으로 커밋한다. 중단돼도 혜택마다 대상이 전부 있거나 하나도 없고, 다음 실행은 시각이 비어 있는 혜택만 고른다.
   - 대상이 한 명도 없어도 시각을 남긴다. 선정을 마쳤다는 뜻이다.
   - `READY`의 뜻(지원조건 수집됨)을 바꾸지 않고, 선정한 시각이 남는다.
   - #107 설계 5는 알림 쪽이 `new_benefit`을 읽기만 한다고 했다. 그 이슈의 구성 방법이었고, 결정 0042의 본문은 쓰기를 제한하지 않는다. 0042의 영향("대상 선정은 `READY`인 행만 읽는다")도 지키므로 0042를 대체하지 않는다. 시각 기록 메서드는 엔티티가 있는 `collection.domain.NewBenefit`에 둔다.
   - 대안: `status`에 `SELECTED`를 더함. 수집이 기록한 사실과 알림의 진행 상태가 한 컬럼에 섞이고, 0042의 상태 전환을 바꾼다.
   - 대안: 알림 쪽 별도 완료 테이블. 수집 쪽 테이블을 건드리지 않지만 테이블과 엔티티가 하나씩 늘어난다.
   - 기각: 완료 표시 없이 매번 `READY` 전체를 다시 판정하고 PK로 중복만 막음. 선정 뒤 프로필을 바꿔 새로 해당하게 된 사용자가 추가된다.
3. **판정은 혜택 쪽 입력을 혜택마다 한 번 만들고, 알림을 켠 사용자를 묶음으로 읽어 같은 `MatchSubject.of`와 `ConditionMatcher.match`로 한다.** 2026-11-04 사용자 결정.
   - 혜택 쪽 입력은 조건 비교 목록과 같은 `MatchCandidate`다. 새 메서드 `MatchCandidateLoader.load(Long benefitId)`가 혜택 하나로 DB에서 만든다. 판정 입력이 바뀌면 결정 0038에 따라 `MatchCandidate`가 바뀌므로 선정도 따라간다.
   - `MatchCandidateStore.get()`은 쓰지 않는다. 리스너 순서에 따라 교체 전 목록일 수 있고, 그러면 새 혜택이 없다.
   - 사용자는 알림을 켠 계정 ID를 ID 순으로 1,000명씩 읽고, 그 묶음의 프로필을 한 번에 읽는다. 프로필이 없는 사용자도 `MatchSubject.of(null, today)`로 판정한다. 조건 비교와 같게 하려고 따로 거르지 않는다.
   - 기준일(`today`)은 선정 실행마다 한 번 정한다.
   - 판정은 트랜잭션 밖에서 하고, 저장만 혜택마다 짧은 트랜잭션으로 한다. 사용자 수만큼 엔티티가 영속성 컨텍스트에 쌓이지 않는다. `MatchService.list`도 트랜잭션 밖에서 판정한다.
   - 대안: 사용자마다 `MatchService.match` 호출. 경로가 완전히 같지만, 사용자와 혜택 조합마다 조회와 JSON 파싱을 한다.
   - 대안: SQL로 지역 후보를 미리 거름. 지역 규칙이 SQL과 Java 두 곳에 생기고, 필터가 좁으면 '해당'인 사용자를 빠뜨린다.
4. **알림 항목은 `new_benefit_target(user_id, benefit_id)`이고 PK가 두 컬럼이다.**
   - 같은 사용자·혜택은 한 행이다. `user_id`는 `user_account`를 `ON DELETE CASCADE`로, `benefit_id`는 `new_benefit`을 가리킨다. 탈퇴는 북마크와 같이 FK로 지워지므로 `AccountService.withdraw`를 바꾸지 않는다.
   - 시각 컬럼을 두지 않는다. 같은 혜택의 행은 모두 그 혜택의 `targets_selected_at`에 생긴다.
   - 쓰기는 북마크처럼 네이티브 `INSERT`로 한다. `INSERT IGNORE`는 쓰지 않는다. 이미 있는 행에 다시 쓰면 선정을 두 번 한 것이므로 트랜잭션이 실패해야 한다.
   - 엔티티 `NewBenefitTarget`(`@IdClass`)과 저장소는 `kr.govbenefit.notification`에 둔다(#106 설계 5).
   - 발송 상태 컬럼은 두지 않는다. 묶음 발송과 발송 의도(`REQ-NOTIFY-02`)는 발송 이슈에서 정한다.
5. **인덱스를 더하지 않는다.**
   - 선정할 새 혜택은 `new_benefit`에서 찾는다. 행이 월 수십 건이다.
   - 알림을 켠 사용자는 PK 순으로 전부 읽으므로 `new_benefit_notify` 인덱스가 범위를 줄이지 않는다. 선정할 새 혜택이 있을 때만 읽는다.
6. **결정 문서로 올리지 않는다.**
   - 대상 기준(해당만, 알림을 켠 사용자, 선정 뒤 프로필 변경 제외)은 `REQ-NOTIFY-03`·`DEC-NOTIFY`에 있다.
   - 판정 입력을 함께 바꾸는 규칙은 결정 0038의 영향이 이미 다룬다. 0042와 충돌하지 않는다(설계 2).

## 단계별 구현 계획

1. **마이그레이션** `V16__create_new_benefit_target.sql`
   ```sql
   -- 새 혜택 알림 대상을 고른 시각(#117). 비어 있으면 아직 고르지 않았다. 판단 가능(READY)인 행만 채운다.
   ALTER TABLE new_benefit
       ADD COLUMN targets_selected_at DATETIME(6) NULL AFTER status;

   -- 새 혜택 알림 대상(#117, REQ-NOTIFY-03). 알림을 켰고 선정할 때 조건 비교 결과가 '해당'인 사용자와 새 혜택의 조합당 한 행이다.
   -- 탈퇴하면 함께 지워진다(DEC-AUTH).
   CREATE TABLE new_benefit_target (
       user_id    BIGINT NOT NULL,
       benefit_id BIGINT NOT NULL,
       PRIMARY KEY (user_id, benefit_id),
       CONSTRAINT fk_new_benefit_target_user FOREIGN KEY (user_id) REFERENCES user_account (id) ON DELETE CASCADE,
       CONSTRAINT fk_new_benefit_target_new_benefit FOREIGN KEY (benefit_id) REFERENCES new_benefit (benefit_id)
   );
   ```
2. **새 혜택** `collection.domain.NewBenefit`, `collection.repository.NewBenefitRepository`
   - 필드 `LocalDateTime targetsSelectedAt`. 주석: 알림 대상을 고른 시각. 비어 있으면 아직 고르지 않았다.
   - `void markTargetsSelected(LocalDateTime now)`: `targetsSelectedAt`과 `updatedAt`을 `now`로 바꾼다.
   - 저장소: `List<NewBenefit> findAllByStatusAndTargetsSelectedAtIsNullOrderByBenefitId(NewBenefitStatus status)`.
3. **알림 대상** `notification.domain.NewBenefitTarget`, `notification.repository.NewBenefitTargetRepository`
   - 엔티티: `@IdClass(NewBenefitTarget.Key.class)`, `userId`·`benefitId`, `record Key(Long userId, Long benefitId)`. 생성 메서드는 없다(`Bookmark`와 같다).
   - 저장소: `@Modifying` 네이티브 `INSERT INTO new_benefit_target (user_id, benefit_id) VALUES (:userId, :benefitId)`, `int insert(Long userId, Long benefitId)`.
4. **사용자 읽기**
   - `account.repository.UserAccountRepository`: `@Query("select a.id from UserAccount a where a.newBenefitNotify = true and a.id > :afterId order by a.id") List<Long> findNewBenefitNotifyIds(@Param("afterId") Long afterId, Limit limit)`.
   - `profile.repository.UserProfileRepository`: `@EntityGraph(attributePaths = "conditionCodes") List<UserProfile> findAllWithConditionCodesByUserIdIn(List<Long> userIds)`.
5. **판정 입력** `match.service.MatchCandidateLoader`
   - `@Transactional(readOnly = true) MatchCandidate load(Long benefitId)`: `benefitRepository.findById`(없으면 `IllegalStateException`), `sourceRecordRepository.findByServiceIdAndEndpoint(serviceId, SUPPORT_CONDITIONS)`의 파싱 결과(없으면 `null`), `regionService.regions().agencyRegionOf(...)`로 만든다.
   - `load()`와 같은 변환을 쓰도록 `MatchCandidate` 만드는 부분을 private 메서드로 나눈다.
   - 클래스 주석의 "`MatchCandidateStore`만 부른다"를 "`MatchCandidateStore`와 새 혜택 대상 선정이 부른다"로 바꾼다.
6. **대상 선정** `notification.service`
   - `NewBenefitTargetSelector`(트랜잭션 없음)
     - `@EventListener void onCollectionRunFinished(CollectionRunFinished event)`: `selectAll()`을 부르고, `RuntimeException`을 잡아 `log.error("새 혜택 대상 선정 실패 runId={}", ...)`로 남긴다.
     - `void selectAll()`: `today`·`now`를 `Clock`으로 한 번 정하고, `findAllByStatusAndTargetsSelectedAtIsNullOrderByBenefitId(READY)`의 혜택마다 `select`를 부른다.
     - `private void select(Long benefitId, LocalDate today, LocalDateTime now)`
       - `MatchCandidateLoader.load(benefitId)`로 판정 입력을 만든다.
       - `findNewBenefitNotifyIds(afterId, Limit.of(1000))`로 사용자 묶음을 읽고, 묶음의 프로필을 `findAllWithConditionCodesByUserIdIn`으로 읽는다.
       - 사용자마다 `ConditionMatcher.match(MatchSubject.of(profile, today), candidate.conditions(), candidate.userType(), candidate.agencyRegion())`이 `MATCH`이면 ID를 모은다.
       - `NewBenefitTargetWriter.save(benefitId, userIds, now)`를 부르고, `log.info("새 혜택 대상 선정 benefitId={} users={} targets={} elapsedMs={}")`를 남긴다.
     - 클래스 주석: `REQ-NOTIFY-03`, `DEC-NOTIFY`. 판정은 조건 비교와 같은 입력과 함수를 쓴다. 혜택마다 한 번만 고르고, 선정 뒤 프로필 변경은 반영하지 않는다.
   - `NewBenefitTargetWriter`
     - `@Transactional void save(Long benefitId, List<Long> userIds, LocalDateTime now)`: `userIds`마다 `insert`를 부르고, `NewBenefit`을 읽어 `markTargetsSelected(now)`를 부른다.
7. **탈퇴 설명** 탈퇴 삭제 대상에 미발송 알림을 넣는다.
   - `OpenApiConfig.DESCRIPTION`: "계정·프로필·북마크·알림 설정·미발송 알림·토큰을 즉시 지운다".
   - `MeController` 탈퇴 `@Operation` 설명도 같이 고친다.
8. **테스트**
   - `notification.service.NewBenefitTargetSelectionTest`(`@SpringBootTest`, Testcontainers, `Gov24StubServer`). 수집 설정은 `CollectionServiceTest`와 같다(쪽 크기 2, 정기 수집 끔).
     - 기준선: `stub.removeService(C)` 뒤 수집하고, 사용자를 만든 뒤 `stub.reset()`으로 C를 되살려 수집한다. 사용자는 `UserAccountRepository`·`UserProfileRepository`로 만든다.
     - 사용자(모두 C 기준):
       - U1: 알림 켬, 1950년생, 서울 용산구, C의 코드 안의 생애·가구 속성. 해당.
       - U2: U1과 같고 거주지가 서울 강남구. 확인 필요(다른 지역 기관).
       - U3: 알림 켬, 프로필 없음. 확인 필요.
       - U4: U1과 같은 프로필, 알림 끔.
     - 테스트 항목:
       - C가 나타난 수집 뒤 `new_benefit_target`은 (U1, C) 하나이고, C의 `targets_selected_at`이 채워진다.
       - 같은 시점에 U1\~U4마다 `MatchService.match(userId, C)`의 결과를 구한다. 대상에 있는 것은 알림을 켰고 결과가 `MATCH`인 사용자와 정확히 같다. U1이 `MATCH`이고 U2·U3가 `NEEDS_CHECK`인지도 확인해, 픽스처가 의도한 경우를 다루는지 보장한다.
       - 목록의 C 사용자구분을 `법인`으로 바꾸고 수집하면, 대상은 없고 `targets_selected_at`은 채워진다(미해당).
       - C가 있는 지원조건 2쪽이 인증 실패하면, C는 `WAITING_CONDITIONS`이고 대상이 없으며 `targets_selected_at`이 비어 있다. 다음 정상 수집 뒤 대상은 (U1, C)다.
       - 같은 응답으로 다시 수집해도 대상은 한 행이고 `targets_selected_at`은 그대로다.
       - 선정 뒤 U2의 거주지를 용산구로 바꾸고 다시 수집해도 U2는 대상에 없다.
       - 중단 뒤 이어서: 기준선에서 B와 C를 함께 빼고, 되살린 수집에서 B·C를 선정한다. `JdbcTemplate`으로 C의 `targets_selected_at`을 비우고 C의 대상 행을 지워 "B까지 선정하고 멈춘 상태"를 만든다. `selectAll()`을 부르면 C의 대상은 (U1, C)로 다시 생기고, B의 대상 행과 `targets_selected_at`은 그대로다.
   - `account.controller.WithdrawalApiTest`
     - `USER_TABLES`에 `new_benefit_target`을 더한다.
     - 도우미 `saveNewBenefitTarget(userId)`가 수집 실행(`CollectionRun.start`), 테스트 혜택의 `NewBenefit.detect`, 사용자의 대상 행(`JdbcTemplate`)을 저장한다. 저장소의 `insert`는 트랜잭션 안에서만 돌아 테스트에서 쓰지 않는다.
     - 탈퇴 테스트에서 alice의 대상 행을, 다른 사용자에게 영향이 없는지 보는 테스트에서 bob의 대상 행을 저장한다. 두 테스트 모두 `USER_TABLES`를 반복해 확인한다.
   - 기존 테스트는 바꾸지 않고 통과한다. `CollectionServiceTest`의 수집에서도 선정이 돌지만, 알림을 켠 사용자가 없어 대상이 생기지 않는다.

## 완료 조건 매핑

- [ ] 알림을 켠 사용자 중 조건 비교 결과가 '해당'인 사용자만 알림 항목으로 저장된다. '확인 필요'·'해당 없음'인 사용자와 알림을 끈 사용자는 저장되지 않는다. → 설계 3·4. U1\~U4 테스트, 사용자구분 변경 테스트.
- [ ] 저장된 항목은 선정 시점에 같은 사용자가 그 혜택을 조건 비교했을 때의 결과와 같다. → 설계 3. `MatchService.match`와 대조하는 테스트.
- [ ] 대기 상태(지원조건 미수집)인 새 혜택은 선정하지 않고, 판단 가능으로 바뀐 뒤에 선정한다. → 설계 1·2. 지원조건 쪽 실패 뒤 정상 수집 테스트.
- [ ] 수집이나 선정을 다시 실행해도 같은 사용자·혜택의 알림 항목은 한 건만 남는다. → 설계 2·4. 재수집 테스트, 선정 뒤 프로필 변경 테스트.
- [ ] 선정이 도중에 중단된 뒤 다시 실행하면 남은 새 혜택을 빠짐없이 선정하고, 이미 저장한 항목은 중복되지 않는다. → 설계 1·2. 중단 뒤 이어서 테스트.
- [ ] 탈퇴한 사용자의 알림 항목이 삭제된다. → 설계 4. `WithdrawalApiTest`.

## 검증 명령

- `backend/`에서 `./gradlew test`
- 로컬 확인(인증키가 있을 때): 로컬 DB에서 알림을 켠 계정과 프로필을 만들고 `SPRING_PROFILES_ACTIVE=local,collect ./gradlew bootRun`으로 수집한 뒤 다음을 실행한다.
  ```sql
  SELECT nb.benefit_id, nb.status, nb.targets_selected_at, b.service_id, b.name
  FROM new_benefit nb JOIN benefit b ON b.id = nb.benefit_id;
  SELECT t.*, b.service_id FROM new_benefit_target t JOIN benefit b ON b.id = t.benefit_id;
  ```
  다음을 확인한다.
  - `READY`인 새 혜택은 모두 `targets_selected_at`이 채워져 있다.
  - 대상 행의 사용자가 조건 비교 화면에서 그 혜택을 '해당'으로 본다.
  - 로그에 혜택마다 `새 혜택 대상 선정`이 한 줄씩 남는다.

## 후속 제안

- 설계 1의 한계(프로세스 종료·선정 실패 시 하루 늦게 감)를 발송 이슈에서 확인한다. 08:00 발송 전에 미선정 `READY` 혜택을 선정하는 따라잡기를 둘지 그때 정한다. 이 이슈에서는 진행하지 않는다.

## 결정 필요

없음
