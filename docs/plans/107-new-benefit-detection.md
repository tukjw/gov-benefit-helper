status: done

# 구현 계획 — #107 수집에서 새로 등록된 혜택을 감지해 기록

브랜치: `feat/107-new-benefit-detection` (develop `f43a512` 기준)

## 목표 및 범위

- 목표: 수집에서 출처 서비스 ID가 우리 DB에 처음 저장된 혜택을 새 혜택으로 기록하고, 지원조건 수집 여부에 따라 대기·판단 가능 상태를 둔다(`REQ-NOTIFY-03` P0, `DEC-NOTIFY(새 혜택 알림)`).
- 포함:
  - `new_benefit` 테이블(V14)과 엔티티·저장소(`kr.govbenefit.collection`).
  - 목록 쪽 저장에서 새 혜택 기록, 지원조건 쪽 저장에서 판단 가능 전환(`CollectionPageWriter`).
  - 최초 전체 수집(기준선) 판정.
  - 결정 문서 0042.
  - 수집 테스트.
- 제외(이슈 본문):
  - 대상 사용자 선정과 발송 의도 저장(후속 이슈). 판단 가능 행을 읽는 조회와 인덱스도 그때 정한다.
  - 기존 혜택의 조건이 바뀌어 새로 해당하게 된 경우(`REQ-NOTIFY-03` P1).
- 제외(이 플랜):
  - 기존 혜택의 소급 기록. 기능 이전부터 있던 혜택은 새 혜택이 아니다.
  - 새 혜택 건수의 지표·로그. 어느 수집에서 감지했는지는 감지 실행 ID로 확인한다.

## 확인된 사실과 제약

- 수집 순서는 목록 → 상세 → 지원조건이다(`SourceEndpoint`). 쪽마다 `CollectionPageWriter.write`가 한 트랜잭션으로 커밋하고, `CollectionService.collectAll`에는 트랜잭션이 없다. 실행이 도중에 실패하거나 중단돼도 저장한 쪽은 남는다(결정 0007).
- `benefit` 행은 `saveBenefits`에서 목록에 처음 나온 서비스 ID에만 `Benefit.create`로 생긴다. 이미 있으면 `confirm`으로 내용과 정규화 값만 바꾼다. 수집은 행을 지우지 않고, 목록에서 빠지면 `last_seen_at`만 멈춘다(결정 0023). `benefit.service_id`에는 `uk_benefit_service_id`가 있다.
- 상세·지원조건 원문(`source_record`)은 목록에 없는 서비스 ID도 저장한다. 원문이 목록보다 먼저 저장될 수 있다.
- 지원조건 쪽은 지금 원문만 저장하고, `countUnlinked`가 `findBenefits`로 그 쪽의 혜택을 읽어 목록에 없는 ID를 센다.
- 데이터 검증([보고서 §3](../research/gov24-api-data.md#3-세-경로의-연결), [§8](../research/gov24-api-data.md#8-갱신과-삭제)):
  - 세 경로의 서비스 ID는 10,941건 모두 같았다.
  - 관찰 기간에 새로 나타난 16건 중 15건이 예전에 등록된 서비스라, 등록일시로는 새 혜택을 가릴 수 없다.
  - 2건이 목록에서 빠졌다가 다시 나타났다.
- 경로 상태(`collection_run_step.status`): 모든 쪽을 저장해야 `SUCCEEDED`다. 실패한 쪽이 있거나 중단되면 `PARTIAL`, 1쪽을 받지 못하면 `FAILED`이고, 프로세스가 죽으면 `RUNNING`으로 남는다.
- `CollectionRunFinished` 리스너(`MatchCandidateStore`)는 수집과 같은 스레드에서 돈다. 이 플랜은 리스너를 더하지 않는다.
- 테스트 DB 정리는 `information_schema`에서 테이블 목록을 읽어 새 테이블도 비운다(결정 0033).
- 테스트는 MySQL 컨테이너 하나를 함께 쓰고(결정 0033), Spring 컨텍스트마다 커넥션 풀(최대 10개)이 생긴다. 지금 `./gradlew test`에서 컨텍스트가 18개 떠 MySQL 기본 연결 한도(151)에 닿아 있다. 테스트 클래스를 새로 만들어 컨텍스트를 하나 더 띄우자 다른 테스트가 `Too many connections`로 실패했다(#107 작업 중 확인).
- 다음 마이그레이션 번호는 V14, 다음 결정 번호는 0042이다.
- 알림 설정과 후속 대상 선정·발송은 `kr.govbenefit.notification`에 둔다([#106 플랜](106-new-benefit-notify-setting.md) 설계 5).
- 혜택 상세처럼 `benefit_id`를 직접 넣는 PK는 `BenefitDetail`이 이미 쓴다(`@Id`, 생성 전략 없음).

## 설계 결정

1. **새 혜택은 목록 쪽 저장 트랜잭션 안에서 `benefit` 행을 만들 때 함께 기록한다.** 2026-11-03 사용자 결정.
   - 혜택과 기록이 함께 커밋되고 함께 롤백된다. 실행이 도중에 실패해도 저장된 새 혜택의 기록은 빠지지 않는다. 다시 실행하면 행이 이미 있어 또 기록하지 않는다.
   - 정규화 규칙이 바뀌거나 목록에서 빠졌다가 다시 나타나면 `confirm` 경로를 타므로 기록되지 않는다.
   - 기준은 `benefit` 행 생성이다. 상세·지원조건 원문은 목록보다 먼저 저장될 수 있어 기준으로 쓰지 않는다.
   - 대안: 수집 종료 이벤트에서 일괄 감지. 수집 코드는 그대로지만, 실행 도중 프로세스가 죽으면 이벤트가 나지 않는다. 기록이 빠지거나, 따라잡을 커서를 따로 둬야 하고 그때 감지 실행이 저장한 실행과 달라진다.
   - 대안: `benefit.created_run_id` 컬럼만 두고 조회할 때 계산. 변경은 가장 작지만, 후속 이슈의 처리 상태를 둘 곳이 없어 결국 테이블이 생긴다.
2. **목록 경로를 끝까지 성공한 실행이 없으면 새 혜택을 기록하지 않는다(기준선).** 2026-11-03 사용자 결정.
   - 출처 목록 전체를 한 번 담은 뒤부터 감지한다. 빈 DB의 첫 실행이 목록 도중에 실패해도, 다음 실행에서 남은 혜택을 새 혜택으로 기록하지 않는다.
   - 쪽에 새로 만든 혜택이 있을 때만 `SERVICE_LIST` 경로의 `SUCCEEDED` 기록이 있는지 확인한다. 진행 중인 실행의 목록 경로는 `RUNNING`이라 결과가 실행 안에서 바뀌지 않는다.
   - 운영 DB에는 이미 성공한 실행이 있어 배포 직후부터 기록한다. 기능 이전 혜택은 소급하지 않으므로 기록되지 않는다.
   - 한계: 새 환경의 첫 실행이 부분 실패하면, 목록을 끝까지 받기 전에 새로 나온 혜택도 기록하지 않는다. 잘못 알리는 것보다 놓치는 쪽을 택한다.
   - 대안: 실행 시작 때 혜택이 0건이면 그 실행을 기준선으로 봄. 첫 실행이 부분 실패하면 다음 실행에서 남은 혜택 전부를 새 혜택으로 기록한다.
   - 대안: 설정값으로 감지를 켬. 새 환경에서 켜는 것을 잊으면 대량 오알림이 난다.
3. **새 혜택은 대기(`WAITING_CONDITIONS`)로 만들고, 지원조건 쪽을 저장할 때 그 쪽의 대기 혜택을 판단 가능(`READY`)으로 바꾼다.** 2026-11-03 사용자 결정.
   - 같은 실행에서 목록 다음에 지원조건을 받으므로 보통 그 실행 안에서 바뀐다. 지원조건 경로가 실패하거나 중단되면 대기로 남고, 다음 수집에서 그 쪽을 저장할 때 바뀐다.
   - 지원조건 원문이 목록보다 먼저 있던 경우도 같은 실행에서 다시 받으므로, 만들 때 원문을 따로 확인하지 않는다.
   - 대안: 수집 종료 때 일괄 전환. 고칠 곳이 한 곳이지만 설계 1의 이벤트 대안과 같은 문제가 있고, 실행 도중 프로세스가 죽으면 다음 날까지 늦어진다.
   - 대안: 상태 없이 조회할 때 지원조건 원문이 있는지로 판단. 언제 판단 가능해졌는지 남지 않고, 후속 이슈의 처리 상태와 따로 관리해야 한다.
4. **테이블은 `new_benefit`이고, 키는 `benefit_id`(PK, `benefit.id` FK), 감지 실행은 `detected_run_id`(`collection_run.id` FK)다.**
   - `benefit_detail`·`bookmark`와 같은 참조 방식이다(결정 0023). 혜택 행을 지우지 않는다는 전제를 FK가 지킨다.
   - 지원조건 쪽은 이미 그 쪽의 혜택을 읽으므로(`findBenefits`) `benefit_id`로 찾는다.
   - 상태로 찾는 인덱스는 두지 않는다. 행이 월 수십 건이고, 후속 대상 선정에서 조회 방식과 함께 정한다.
5. **엔티티와 저장소는 `kr.govbenefit.collection`에 둔다.**
   - 수집이 남긴 사실이고, 쓰는 곳은 `CollectionPageWriter`뿐이다. 후속 대상 선정(`notification`)은 읽기만 한다. `match`처럼 다른 패키지가 `collection`에 의존하는 방향을 지킨다.
   - 대안: `notification`에 둠. 수집이 알림 패키지에 의존하게 되고, 후속 이슈가 수집 이벤트를 받으면 두 패키지가 서로 의존한다.
6. **결정 문서 0042로 올린다.** 후속 대상 선정과 발송이 새 혜택의 뜻, 기준선, 상태 전환을 따라야 한다.

## 단계별 구현 계획

1. **마이그레이션** `V14__create_new_benefit.sql`
   ```sql
   -- 수집에서 처음 저장된 혜택(결정 0042). 목록 경로를 끝까지 성공한 실행이 생긴 뒤에 만든 혜택만 있다.
   -- status: WAITING_CONDITIONS(지원조건 원문을 아직 받지 않음), READY(대상 판단 가능)
   CREATE TABLE new_benefit (
       benefit_id      BIGINT      NOT NULL,
       detected_run_id BIGINT      NOT NULL,
       status          VARCHAR(20) NOT NULL,
       created_at      DATETIME(6) NOT NULL,
       updated_at      DATETIME(6) NOT NULL,
       PRIMARY KEY (benefit_id),
       CONSTRAINT fk_new_benefit_benefit FOREIGN KEY (benefit_id) REFERENCES benefit (id),
       CONSTRAINT fk_new_benefit_run FOREIGN KEY (detected_run_id) REFERENCES collection_run (id)
   );
   ```
2. **도메인** `collection.domain`
   - `NewBenefitStatus`: `WAITING_CONDITIONS`, `READY`.
   - `NewBenefit` 엔티티: `benefitId`(`@Id`), `detectedRunId`, `status`, `createdAt`, `updatedAt`.
     - `static NewBenefit detect(Long benefitId, Long runId, LocalDateTime now)`: 대기로 만든다.
     - `void markReady(LocalDateTime now)`: 판단 가능으로 바꾸고 `updatedAt`을 바꾼다.
3. **저장소**
   - `collection.repository.NewBenefitRepository`: `List<NewBenefit> findAllByBenefitIdInAndStatus(List<Long> benefitIds, NewBenefitStatus status)`.
   - `CollectionRunStepRepository`: `boolean existsByEndpointAndStatus(SourceEndpoint endpoint, CollectionStatus status)`.
4. **쪽 저장** `collection.service.CollectionPageWriter`
   - `saveBenefits`에 `runId`를 넘긴다. `benefitRepository.saveAll(created)` 뒤, `created`가 비어 있지 않고 `existsByEndpointAndStatus(SERVICE_LIST, SUCCEEDED)`이면 만든 혜택마다 `NewBenefit.detect`를 저장한다.
   - `countUnlinked`를 `linkConditions(byServiceId, now)`로 바꾼다. `findBenefits`로 읽은 혜택 ID로 대기인 새 혜택을 찾아 `markReady`하고, 목록에 없는 ID 수를 돌려준다.
   - 클래스 주석에 새 혜택 기록과 판단 가능 전환을 적는다.
5. **결정 문서** `docs/decisions/0042-new-benefit-detection.md`(템플릿을 따른다)
   - 결정: 새 혜택은 목록 경로를 끝까지 성공한 실행이 있은 뒤 목록 쪽 저장에서 처음 만든 `benefit` 행이다. 같은 트랜잭션에서 대기로 기록하고, 지원조건 쪽을 저장할 때 판단 가능으로 바꾼다. 기존 혜택은 소급하지 않는다.
   - 근거: 설계 1\~5의 이유와 대안.
   - 영향:
     - 후속 대상 선정은 판단 가능 행만 읽는다.
     - 혜택 행을 지우거나 다시 만드는 작업, 증분 수집, 경로 순서 변경이 생기면 이 결정을 다시 본다.
   - `docs/decisions/README.md`의 목록 표에 0042 행을 더한다.
6. **테스트** `collection.service.CollectionServiceTest`에 더한다
   - 새 테스트 클래스를 만들지 않는다. 컨텍스트가 하나 늘면 테스트 MySQL 연결 한도를 넘는다(확인된 사실). 같은 stub 서버와 쪽 크기 2를 쓴다.
   - fixture는 A·B·C다. 기준선 실행은 `stub.removeService(C)` 뒤에 수집하고, `stub.reset()`으로 C를 되살려 새 혜택을 만든다.
   - 테스트 항목:
     - 빈 DB에서 첫 수집을 하면 `new_benefit`이 비어 있고, 목록 경로는 `SUCCEEDED`다.
     - 기준선 뒤 C가 나타난 수집에서 C가 하나 기록된다. 감지 실행은 그 실행이고 상태는 `READY`다. 같은 응답으로 다시 수집해도 행은 하나이고 감지 실행 ID도 그대로다.
     - 기준선 뒤 `JdbcTemplate`으로 B의 정규화 값(`apply_period_rule` 등)을 다른 값으로 바꾸고 수집하면, B의 값은 다시 계산되고 `new_benefit`은 비어 있다.
     - 기준선 뒤 B를 빼고 수집한 다음 되살려 수집해도 `new_benefit`은 비어 있다.
     - 기준선 뒤 C가 나타난 수집에서 상세 1쪽이 인증 실패(401)하면 실행이 중단돼 지원조건을 받지 않는다. 실행은 `PARTIAL`이고 C는 `WAITING_CONDITIONS`로 기록된다.
     - 기준선 뒤 C가 나타난 수집에서 C가 있는 지원조건 2쪽이 인증 실패하면 C는 `WAITING_CONDITIONS`다. 다음 정상 수집에서 `READY`가 되고, 감지 실행 ID는 처음 실행 그대로다.
     - 첫 실행에서 목록 2쪽이 비어(`API_TRUNCATED`) 목록 경로가 `PARTIAL`이면, 다음 수집에서 C가 처음 저장돼도 `new_benefit`은 비어 있다.
   - 기존 수집 테스트는 바꾸지 않고 통과한다.

## 완료 조건 매핑

- [ ] 혜택이 하나도 없는 상태에서 시작한 최초 수집으로 저장된 혜택은 새 혜택으로 기록되지 않는다. → 설계 2. 빈 DB 첫 수집 테스트, 목록 부분 실패 뒤 수집 테스트.
- [ ] 이후 수집에서 처음 나타난 서비스 ID는 새 혜택으로 한 번만 기록되고, 수집을 다시 실행해도 중복 기록되지 않는다. → 설계 1·4. C가 나타난 수집과 재수집 테스트.
- [ ] 정규화 규칙을 바꾼 뒤 재수집해도 기존 혜택이 새 혜택으로 기록되지 않는다. → 설계 1. 정규화 값 변경 테스트.
- [ ] 목록에서 빠졌다가 다시 나타난 혜택은 새 혜택으로 기록되지 않는다. → 설계 1. B를 빼고 되살리는 테스트.
- [ ] 지원조건 없이 저장된 새 혜택은 대기로 남고, 이후 수집에서 지원조건이 들어오면 판단 가능 상태로 바뀐다. → 설계 3. 지원조건 쪽 실패 뒤 정상 수집 테스트.
- [ ] 수집이 도중에 실패해도 처음 저장된 혜택이 새 혜택 기록에서 빠지지 않는다. → 설계 1. 상세 인증 실패로 중단된 실행 테스트.

## 검증 명령

- `backend/`에서 `./gradlew test`
- 로컬 확인(인증키가 있을 때): `SPRING_PROFILES_ACTIVE=local,collect ./gradlew bootRun`으로 수집한 뒤 다음을 실행한다.
  ```sql
  SELECT COUNT(*) FROM collection_run_step WHERE endpoint = 'SERVICE_LIST' AND status = 'SUCCEEDED';
  SELECT nb.*, b.service_id, b.name FROM new_benefit nb JOIN benefit b ON b.id = nb.benefit_id;
  ```
  다음을 확인한다.
  - 기존 로컬 DB에서는 이번 수집 전부터 있던 혜택이 `new_benefit`에 없다.
  - 기록된 행은 이번 수집에서 처음 저장된 혜택뿐이다.

## 후속 제안

- 테스트 컨텍스트 수가 MySQL 연결 한도에 닿아 있다. 새 테스트 클래스가 하나 늘 때마다 실패할 수 있으므로, 테스트 커넥션 풀 크기나 테스트 MySQL의 연결 한도를 정하는 작업은 #113에서 한다. 이 이슈에서는 진행하지 않는다.

## 결정 필요

없음
