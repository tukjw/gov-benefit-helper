status: in-progress

# 구현 계획 — #141 배포할 때 새 혜택 알림이 두 번 나가지 않게 발송 종료 처리 개선

브랜치: `improve/141-notify-send-graceful-shutdown` (develop `6cc8bf3` 기준)

## 목표 및 범위

- 목표: 앱이 종료 신호를 받으면 새 혜택 알림 발송이 다음 메일을 보내지 않고, 보내던 메일은 결과를 기록한 뒤 종료 대기 안에 멈춘다. 발송 중 배포 3번에서 중복 접수가 0건인지 운영 VM에서 다시 잰다.
- 포함:
  - 발송 작업(`NewBenefitDigestSender`)의 종료 신호 처리와 테스트.
  - 결정 문서 0049(발송의 종료 처리).
  - 집계 스크립트(`count.py`)에 회차별 "명령 뒤 발송 요약 로그까지 걸린 시간" 추가.
  - `deploy` 시나리오 재측정과 보고서(`docs/research/notify-send-graceful-shutdown.md`), 원본 결과.
- 제외(이슈 본문):
  - 강제 종료와 응답 유실에서 생기는 중복(결정 0048이 받아들인 한계).
  - #137 보고서 7절의 다른 후보(발송 속도, 스케줄러 스레드 점유, SES 하루 할당량, 계정 메일 실패).
  - `deploy` 말고 다른 시나리오의 재측정.
- 제외(이 플랜):
  - 종료 대기 시간 설정 변경. Spring 종료 단계 제한(30초)과 `stop_grace_period`(40초)는 그대로 둔다(설계 3).
  - 묶기·수집 등 다른 예약 작업의 종료 처리.
  - `scripts/deploy.sh`, `run.sh`, 가짜 SES 수정. 측정 조건을 #137과 같게 둔다.

## 확인된 사실과 제약

- 지금 종료 순서(Spring Framework 7.0.9 `ExecutorConfigurationSupport`·`ExecutorLifecycleDelegate` 소스, #137 `deploy`의 `app-log.txt.gz`):
  1. `ContextClosedEvent`에서 스케줄러가 `executor.shutdown()`을 불러 새 폴링을 막는다. 실행 중인 `sendDue()`는 계속 돈다.
  2. 생명주기 종료 단계에서 스케줄러가 실행 중인 작업이 끝나기를 30초까지 기다린다. 작업에는 아무 신호도 가지 않는다. 로그에 회차마다 `Shutdown phase 1073741823 ends with 1 bean still running after timeout of 30000ms: [taskScheduler]`가 남았다.
  3. 빈을 없애는 단계에서 `shutdownNow()`가 interrupt를 건다. 같은 때에 JPA와 커넥션 풀이 닫힌다.
- 발송 루프는 `awaitTurn()`의 `Thread.sleep`에서만 interrupt를 본다. #137 실측에서 이 대기에 걸린 요청은 약 1%다(보고서 5.6절). SES 응답이 100ms보다 길면 대기가 없다.
- AWS SDK(2.57.9)는 응답을 받은 뒤에도 interrupt를 확인해 예외를 던진다(`AfterTransmissionExecutionInterceptorsStage`의 `InterruptMonitor.checkInterrupted`). `SesAccountMailSender`는 `SdkClientException`을 일시 실패로 바꾼다. 그래서 interrupt로 멈추면 SES가 접수한 메일이 실패로 처리될 수 있다. `deploy` 2회차 로그에서 종료 대기 직후 `writer.fail`이 불리고 `EntityManagerFactory is closed`로 실패한 것이 이 경로로 보이지만, 로그만으로는 확정하지 못했다.
- 보내던 메일 한 건이 끝나는 데 걸리는 최대 시간은 연결 5초 + 응답 10초(`auth.mail.ses`) + 기록 트랜잭션이다. 30초 안이다.
- 운영은 관리 포트(8081)를 따로 쓰므로 자식 컨텍스트가 있다. 자식 컨텍스트의 이벤트는 부모 컨텍스트의 리스너에도 전달된다(Spring의 이벤트 전파). `ExecutorConfigurationSupport`도 `event.getApplicationContext() == this.applicationContext`로 자기 컨텍스트만 본다.
- `count.py`는 발송 요약 로그를 `... interrupted=(true|false) elapsedMs=...` 형식으로 읽어 `중단` 횟수를 센다. 회차별 "명령 뒤 마지막 요청"은 이미 낸다(#137 `deploy`: 30.57\~30.65초).
- develop은 `v0.1.5` 뒤로 #137만 더해졌고 `backend/src/main` 변경이 없다(`git diff --stat v0.1.5 origin/develop -- backend/src/main`이 비어 있다). 이 브랜치의 이미지는 `v0.1.5`에 이번 변경만 더한 것이고 마이그레이션이 없어, 측정 뒤 `v0.1.5`로 되돌려도 스키마가 맞는다.
- 이미지 워크플로(`image.yml`)는 수동 실행하면 `sha-<커밋 7자리>` 이미지를 만든다. `scripts/deploy.sh sha-<7자리>`로 배포할 수 있다(`docs/ops/deploy.md`).
- 시나리오 하나만 다시 재는 절차가 있다(`docs/ops/load-test.md`의 "시나리오 하나만 다시 잴 때"). `RESULTS_DIR`로 결과 위치를 바꾼다.
- 공유 테스트 컨텍스트에 `ContextClosedEvent`를 실제로 발행하면 Spring 스케줄러가 종료에 들어가 다른 테스트가 깨진다.

## 설계 결정

1. **종료 신호는 `ContextClosedEvent` 리스너로 받는다.** 결정 문서 [0049](../decisions/0049-new-benefit-digest-send-shutdown.md)로 올린다(2026-11-11 사용자 결정).
   - `NewBenefitDigestSender`에 `volatile boolean stopping`을 두고, `@EventListener`가 자기 컨텍스트의 `ContextClosedEvent`에서 `true`로 바꾼다. 한번 켜지면 끄지 않는다.
   - 종료의 가장 앞 단계라 스케줄러가 기다리기 시작하기 전에 신호가 닿는다.
   - 제외: `SmartLifecycle` 구현. 실행 상태 추적이 더 필요하고, 스케줄러가 이미 실행 중인 작업을 기다리므로 얻는 것이 적다.
   - 제외: interrupt 확인. 30초 뒤에야 오고, SDK가 접수된 메일을 실패로 바꿀 수 있다(확인된 사실).
   - 제외: 스케줄러 설정(`await-termination`), 실행 단위 제한, 전용 스레드, 점유 상태, 배포 절차에서 처리. 단독으로 완료 조건을 못 채우거나 이슈가 제외한 범위다(0049의 근거에 적는다).
2. **묶음마다 준비(`prepare`) 전에 확인하고, 보내던 메일은 끊지 않는다.**
   - `stopping`이면 그 묶음부터 다루지 않고 루프를 나온다. 이미 SES 호출에 들어간 묶음은 응답과 기록(`accept`/`fail`)까지 마친다.
   - 남은 묶음은 `PENDING`으로 남아 새 앱의 첫 폴링이 보낸다(결정 0048과 같다).
3. **종료 대기 시간은 바꾸지 않는다.** 보내던 한 건은 최대 15초 남짓이라 30초 안에 끝난다. 루프가 반환하면 스케줄러의 대기가 바로 풀려 종료가 이어진다.
4. **요약 로그 형식을 유지한다.** 종료 신호로 멈춘 실행은 기존 필드 `interrupted=true`로 남긴다. `count.py`의 정규식과 #137 결과의 집계가 그대로 맞는다. 다룬 묶음이 0건이면 지금처럼 로그를 남기지 않는다.
5. **테스트는 발송기를 직접 만들어 확인한다**(2026-11-11 사용자 결정).
   - 가짜 발송기가 첫 묶음을 보내는 도중 종료 리스너를 부른다. 공유 컨텍스트의 `sender` 빈에는 손대지 않는다.
   - 실제 컨텍스트 종료에서 이벤트가 닿는지는 로컬 리허설(단계 6)과 VM 재측정(단계 7)으로 확인한다.
   - 제외: 별도 컨텍스트를 실제로 닫는 테스트. 컨텍스트와 커넥션 풀이 하나 더 생기고(결정 0043), 발송을 붙잡아 두는 타이밍 제어가 필요하다.
6. **결정 문서는 0049를 새로 두고 0048은 채택으로 둔다**(2026-11-11 사용자 결정). 0048의 결정(상태 값, 재시도 규칙, 폴링 구조)은 바뀌지 않고, 0048이 한계로 적은 종료 동작을 0049가 새로 정한다. 0048 본문은 고치지 않는다.
7. **재측정은 브랜치의 확인용 이미지로 한다**(2026-11-11 사용자 결정).
   - `image.yml`을 이 브랜치로 수동 실행해 `sha-<7자리>` 이미지를 만들고, 그 태그로 운영 app을 바꾼 뒤 `deploy` 시나리오만 잰다. 끝나면 `v0.1.5`로 되돌린다.
   - #137과 같은 조건: 사용자 1만 명, `SEED=137`, `DEPLOY_AT_MIN="5 12 19"`, 같은 가짜 SES 지연 표본(`latency-ms.txt`). SES 지연은 다시 재지 않는다.
   - VM에서 실행하는 명령(이미지 빌드, `scripts/deploy.sh`, `switch.sh`, `run.sh`)은 사용자가 실행한다. 에이전트는 명령을 제시하고 결과를 정리한다.
   - 제외: 머지·릴리스 뒤 측정. 결과를 담을 PR이 하나 더 필요하다.

## 단계별 구현 계획

### 1. 발송 작업의 종료 신호 처리

`backend/src/main/java/kr/govbenefit/notification/service/NewBenefitDigestSender.java`

- 생성자에 `ApplicationContext`를 더한다.
- 필드 `private volatile boolean stopping`.
- `@EventListener public void onContextClosed(ContextClosedEvent event)`: `event.getApplicationContext() == applicationContext`일 때만 `stopping = true`.
- `sendDue()`: 묶음 루프의 첫 줄에서 `stopping`이면 `interrupted = true`로 두고 루프를 나온다. 쪽 조회 전의 `while (!interrupted)`는 그대로 쓴다.
- 클래스 주석에 종료 처리(결정 0049)를 적는다. `awaitTurn()`은 고치지 않는다.

### 2. 테스트

`backend/src/test/java/kr/govbenefit/notification/service/NewBenefitDigestSendTest.java`

- `발송_중에_앱이_종료되면_보내던_묶음은_기록하고_다음_묶음은_보내지_않는다`
  - 사용자 둘의 묶음을 만든다. `RecordingAccountMailSender`를 감싼 발송기가 첫 발송을 기록한 직후 `onContextClosed(new ContextClosedEvent(applicationContext))`를 부른다.
  - 이 발송기로 `new NewBenefitDigestSender(...)`를 만들어 `sendDue()`를 부른다.
  - 확인: 발송 1건. 첫 묶음 `ACCEPTED`·`attempt_count` 1·`accepted_at` 기록. 둘째 묶음 `PENDING`·`attempt_count` 0. 요약 로그에 `accepted=1`과 `interrupted=true`(`OutputCaptureExtension`).
  - 이어서 공유 컨텍스트의 `sender.sendDue()`(새 앱)를 부르면 둘째 묶음만 나가 발송이 모두 2건이고 주소가 겹치지 않는다.
- `다른_컨텍스트의_종료_이벤트에는_멈추지_않는다`
  - 새 `GenericApplicationContext`의 `ContextClosedEvent`를 넘긴 뒤 `sendDue()`가 두 묶음을 모두 보낸다.

### 3. 결정 문서

- `docs/decisions/0049-new-benefit-digest-send-shutdown.md`: 템플릿대로 쓴다. 배경은 0048의 한계와 #137 5.3절, 결정은 설계 1\~3, 근거는 확인된 사실과 제외한 대안, 영향은 "발송 루프를 고치거나 스레드를 늘릴 때 묶음마다 종료 신호를 확인하고 보내던 메일을 끊지 않는다"이다.
- `docs/decisions/README.md` 목록에 0049를 더한다. 0048의 상태는 그대로 둔다.

### 4. 집계 스크립트

`scripts/loadtest/notify/count.py`

- 앱 로그 줄의 시각을 읽어, 회차마다 명령 시각(`t_cmd`)과 완료 시각(`t_done`) 사이의 `interrupted=true` 요약 로그까지 걸린 시간을 `rounds[].stopSummaryAfterCmdSeconds`로 낸다. 없으면 `null`이다.
- `print_summary`의 회차 줄에 `멈춤 요약 로그 N초`를 더한다.
- #137 결과로 다시 세면 이 값만 `null`로 더해지고 다른 값은 그대로인지 본다(#137 결과 파일은 커밋하지 않는다).

### 5. 로컬 검증

- `backend/`에서 `./gradlew test`.
- `python3 -m py_compile scripts/loadtest/notify/count.py`.

### 6. 로컬 리허설

`docs/ops/load-test.md`의 "로컬 리허설"대로 지금 코드의 이미지로 app을 띄우고 `deploy`만 돌린다.

```bash
scripts/loadtest/notify/rehearsal/setup.sh /tmp/notify-rehearsal
# setup.sh가 출력한 export 줄을 실행한 뒤
scripts/loadtest/notify/switch.sh on
(cd /tmp/notify-rehearsal && docker compose stop cloudflared)
USERS=300 IDLE_END_S=40 EXPECTED_MIN=6 DEPLOY_AT_MIN=0.3 RESULTS_DIR=/tmp/notify-rehearsal/results \
  scripts/loadtest/notify/run.sh deploy
```

- 기대: 중복 접수 0, 못 받은 묶음 0, `중단 1`, "명령 뒤 마지막 요청"과 "멈춤 요약 로그"가 몇 초 안, 앱 로그에 `still running after timeout`이 없다.
- 기대와 다르면 VM 측정으로 넘어가지 않는다.

### 7. 운영 VM 재측정(사용자 실행)

1. 브랜치를 push하고 확인용 이미지를 만든다: `gh workflow run image.yml --ref improve/141-notify-send-graceful-shutdown`, `gh run watch`.
2. 내 PC에서 `scripts/deploy.sh sha-<7자리>`로 운영 app을 바꾼다. 측정 전 태그(`v0.1.5`)를 적어 둔다.
3. 부하 생성 VM의 `tmux`에서 저장소를 이 브랜치로 맞추고 Tunnel을 멈춘 뒤 측정한다(`docs/ops/load-test.md` 준비 3\~6, SES 지연 재측정은 하지 않는다).

   ```bash
   scripts/loadtest/notify/switch.sh on
   USERS=100 IDLE_END_S=30 SKIP_METRICS=1 RESULTS_DIR=/tmp/notify-check scripts/loadtest/notify/run.sh normal
   RESULTS_DIR=docs/research/notify-send-graceful-shutdown/results scripts/loadtest/notify/run.sh deploy
   scripts/loadtest/notify/switch.sh off
   ssh govbenefit-app 'cd /opt/gov-benefit/app && docker compose start cloudflared'
   ```

4. 내 PC에서 `scripts/deploy.sh v0.1.5`로 되돌린다. `APP_TAG`가 측정 전과 같고 `https://api.govbenefit.kr/api/profile/options`가 200인지 본다.
5. CloudWatch(서울 리전) `AWS/SES`의 `Send`에서 측정 시간대에 실제로 나간 메일이 0통인지 본다.
6. 결과 디렉터리를 내 PC로 가져오고 부하 생성 VM의 결과를 지운다(`docs/ops/load-test.md` 끝난 뒤 4와 같다).

### 8. 보고서

`docs/research/notify-send-graceful-shutdown.md`. [측정 기록 형식](../product/development.md#측정-기록-형식)을 따른다.

- 요약, 측정 조건(이미지 태그, 커밋, 일시, #137과 같은 값), 결과, 정합성(실제 SES 0통, 합성 데이터 0건, 실행 구성 해시와 `APP_TAG`, Tunnel), 한계.
- 회차별 표에 #137 값과 이번 값을 나란히 둔다: 명령 → 이전 앱의 마지막 요청, 명령 → 멈춤 요약 로그, 명령 → UP, 명령 → 재개, 중복.
- 시나리오 합계(못 받은 묶음, 중복 접수, 끝나지 않은 묶음, 최종 실패, 총 소요)도 #137 `deploy`와 나란히 둔다.

### 9. 마무리

- `docs/ops/load-test.md`의 "시나리오 하나만 다시 잴 때"에 개선 측정은 `RESULTS_DIR`로 그 작업의 보고서 옆에 둔다는 한 줄을 더한다.
- 이 플랜의 완료 조건 매핑을 채우고 `status`를 `done`으로 바꾼 뒤 PR을 올린다.

## 완료 조건 매핑

- [ ] 발송 중 배포 3번(#137 `deploy`와 같은 조건)에서 중복 접수가 0건이다. 가짜 SES 기록과 묶음 상태를 대조해 센 값이다. → 설계 1·2·7. 단계 1·7·8.
- [ ] 같은 측정에서 못 받은 묶음, 끝나지 않은 묶음, 최종 실패가 모두 0건이다. → 설계 2. 단계 7·8.
- [ ] 회차마다 이전 앱이 종료 대기 안에 발송을 멈췄다는 근거가 있다. 멈추기 전에 남긴 발송 요약 로그와, 종료 신호 뒤 이전 앱의 마지막 요청 시각이다. → 설계 4. 단계 4·7·8.
- [ ] 회차별로 다시 UP까지 걸린 시간과 발송이 재개될 때까지 걸린 시간이 #137 값과 함께 있다. → 단계 8.
- [ ] 발송 중에 앱이 종료되면 보내던 묶음은 결과가 기록되고 다음 묶음은 보내지 않는다는 것을 테스트로 확인한다. → 설계 5. 단계 2.
- [ ] 측정하는 동안 실제 SES로 나간 요청이 없었다는 확인 결과가 있다. 측정이 끝난 뒤 합성 데이터는 0건이고, 앱 설정과 Tunnel은 측정 전과 같다. → 설계 7. 단계 7(4·5)·8.

## 검증 명령

- `backend/`에서 `./gradlew test`
- `python3 -m py_compile scripts/loadtest/notify/count.py`
- `python3 scripts/loadtest/notify/count.py docs/research/notify-send-fault-baseline/results/20261111T004126-all/deploy`(#137 값이 그대로인지 보고, 바뀐 결과 파일은 되돌린다)
- 로컬 리허설(단계 6)
- 운영 VM 재측정(단계 7) 뒤 `python3 scripts/loadtest/notify/count.py docs/research/notify-send-graceful-shutdown/results/<시각>-deploy`

## 결정 필요

없음. 아래는 2026-11-11에 사용자가 정했다.

1. 종료 신호를 받아 발송 루프가 스스로 멈추는 방식으로 하고, 신호는 `ContextClosedEvent` 리스너로 받는다(설계 1).
2. 재측정은 브랜치의 확인용 이미지(`sha-<7자리>`)로 하고, 끝나면 `v0.1.5`로 되돌린다(설계 7).
3. 결정 문서는 0049를 새로 두고 0048은 채택으로 둔다(설계 6).
4. 테스트는 발송기를 직접 만들어 확인하고, 실제 컨텍스트 종료는 리허설과 재측정으로 확인한다(설계 5).
