status: draft

# 구현 계획 — #137 새 혜택 알림 발송에 장애를 주입해 유실·중복 기준값 측정

브랜치: `test/137-notify-send-fault-baseline` (develop `6c62717` 기준)

## 목표 및 범위

- 목표: 운영 VM에서 가짜 SES로 새 혜택 알림 1만 통을 보내며 장애를 주입하고, 유실·중복 접수·끝나지 않은 묶음·최종 실패와 복구 시간의 기준값을 [측정 기록 형식](../product/development.md#측정-기록-형식)에 맞춰 남긴다(`REQ-NOTIFY-02` P1의 접수 뒤 응답 유실 테스트, 운영 작업 O4의 알림 작업자 부분).
- 포함:
  - 가짜 SES, 측정용 실행 구성, 합성 데이터 SQL, 시나리오 실행·집계 스크립트(`scripts/loadtest/notify/`).
  - 실제 SES 사전 측정 프로그램과 그 결과(2026-11-09). 가짜 SES의 응답 지연과 속도 제한의 근거다.
  - 측정 보고서(`docs/research/notify-send-fault-baseline.md`)와 원본 결과.
  - 부하 테스트 절차(`docs/ops/load-test.md`)에 알림 발송 측정 절차 추가.
- 제외(이슈 본문):
  - 측정에서 확인한 문제의 개선. 문제별로 후속 이슈를 만든다.
  - 여러 작업자의 동시 점유(`REQ-NOTIFY-02` P1).
  - 대상 선정과 묶기의 소요 시간(#138).
- 제외(이 플랜):
  - 운영 코드 변경. 예약 작업 로그 추가도 하지 않는다(설계 9).
  - `scripts/deploy.sh` 수정. 배포 시나리오는 측정 스크립트가 app만 다시 만든다(설계 6).
  - app VM 바깥 송신 차단(방화벽). 가짜 접근 키로 대신한다(설계 2).
  - 응답이 아예 오지 않는 장애(연결·응답 시간 초과). 후속 이슈도 만들지 않는다(2026-11-09 사용자 결정).

## 확인된 사실과 제약

- 발송 구조(결정 0048, `NewBenefitDigestSender`):
  - 30초 cron으로 돌고 공용 스케줄러 스레드 하나에서 실행한다. 한 실행은 시작 시각까지 차례가 된 `PENDING` 묶음을 ID 순으로 모두 다룬다. 실행 중에 재시도 차례가 된 묶음은 그 실행이 끝난 뒤 다음 실행이 읽는다.
  - SES 호출 직전에 "앞 호출을 시작한 뒤 100ms"까지 기다린다(`awaitTurn`). 준비·SES 호출·기록의 합이 100ms를 넘으면 초당 10건이 나오지 않는다.
  - 재시도 간격은 1분·5분·30분·2시간·6시간이고 묶은 날 24:00(KST)까지만 보낸다. 08:00에 시작하면 첫 묶음의 마지막 시도는 16:36이다.
  - SES가 접수한 뒤 기록이 커밋되기 전에 끝나면 그 묶음은 `PENDING`으로 남아 다시 보내진다(설계 3의 한계).
  - 종료할 때 Spring은 실행 중인 폴링을 30초 기다린 뒤 interrupt한다. 운영 compose의 `stop_grace_period`는 40초다.
- SES 클라이언트(`SesAccountMailSender`, 결정 0047): SDK 재시도 없음, 연결 5초·응답 10초 제한. 429와 5xx, 연결 실패(시간 초과 포함)는 일시 실패, 그 밖의 서비스 오류는 재시도 불가다.
- 엔드포인트는 `SesProperties.endpoint`(`auth.mail.ses.endpoint`)로 바꾼다. `application.yaml`에 키는 없지만 환경 변수 `AUTH_MAIL_SES_ENDPOINT`로 바인딩된다(측정 준비에서 로컬로 확인한다).
- 실제 SES 사전 측정(2026-11-09, 내 PC, 메일박스 시뮬레이터 `success@simulator.amazonses.com`, 앱과 같은 클라이언트 설정):
  - 차례로 100건(초당 5건): 첫 요청 360.4ms, p50 131.5ms, p90 207.1ms, p99 223.2ms, 최소 86.9ms. 100ms를 넘은 비율은 74%다.
  - 동시 12개로 60건: 0.58초(초당 약 103건)에 모두 성공. p50 95.1ms.
  - 초당 30건으로 20초(600건): 9.05초까지 272건이 모두 성공했고, 그 뒤 188건이 실패했다. 실패는 모두 `429 TooManyRequestsException`, 메시지 `Maximum sending rate exceeded.`다. 막힌 뒤 성공은 초당 평균 12.8건이고, 초마다 2\~28건으로 고르지 않다. 초당 14건 토큰 버킷으로 보면 용량은 약 145건이다. 이 실행의 성공 지연은 p50 93.6ms, p90 130.6ms였다.
  - AWS 개발자 안내서는 속도 초과를 `ThrottlingException`(공통 오류 표의 HTTP 400)이라고 적지만, SES v2의 실제 응답은 429다. 지금 분류(일시 실패)가 맞다.
  - 실측 지연으로 보면 발송 속도는 초당 7\~9건이다. 1만 통 한 바퀴는 약 19\~28분이다. 결정 0048의 "초당 10건이면 1,000통에 약 100초"와 다르다.
- 메일박스 시뮬레이터로 보낸 메일은 초당 한도에는 잡히고 하루 할당량·평판 지표에는 잡히지 않으며, 일반 메일과 같이 과금된다(AWS 개발자 안내서).
- SES 계정의 초당 한도는 14건이다(2026-11-06 사용자 확인). 하루 할당량은 확인하지 않았다. 측정 준비에서 SES 콘솔 **Account dashboard**로 확인한다.
- 운영 실행 구성(`deploy/app/compose.yaml`): 서비스 `app`은 `env_file: app.env`, `restart: unless-stopped`다. `scripts/deploy.sh`는 서비스 이름 없이 `docker compose up -d`를 실행해 `cloudflared`도 띄운다.
- 앱 컨테이너는 `java -jar app.jar`가 PID 1이다(`backend/Dockerfile`). 로컬 Docker로 확인한 결과:
  - `docker kill --signal KILL`로 죽이면 수동 중지로 처리돼 `unless-stopped`가 다시 띄우지 않는다.
  - `docker run --rm --pid=host alpine kill -KILL <호스트 PID>`로 죽이면 Docker가 다시 띄운다(`RestartCount` 1). docker 그룹 권한만 있으면 되고 sudo가 필요 없다.
- 스키마:
  - `user_account(email, password_hash, new_benefit_notify, created_at, …)`. 묶음·알림 항목·프로필 등은 계정을 `ON DELETE CASCADE`로 참조한다.
  - `new_benefit_digest(user_id, digest_date, status, attempt_count, next_attempt_at, last_error, accepted_at, created_at)`, 사용자·날짜당 하나.
  - `new_benefit_target(user_id, benefit_id, digest_id)`. `benefit_id`는 `new_benefit(benefit_id)`를 참조하므로 알림 항목에는 `new_benefit` 행이 있어야 한다. 이 FK에는 `ON DELETE CASCADE`가 없다.
  - `new_benefit(benefit_id, detected_run_id, status, targets_selected_at, created_at, updated_at)`. 기본 키가 `benefit_id`이고 `benefit(id)`·`collection_run(id)`를 참조한다. 앱은 이 표를 수집(새로 만든 혜택 저장, `WAITING_CONDITIONS` 갱신)과 대상 선정(`READY`이고 `targets_selected_at`이 빈 행)에서만 읽는다.
  - `email_verification`은 FK가 없고 앱이 행을 지우지 않는다(04:30 파기 작업만 지운다).
- 예약 작업 설정: 수집은 `gov24.schedule.enabled`(`@ConditionalOnBooleanProperty`), 묶기는 `notification.new-benefit.digest-cron`, 인증 기록 파기는 `auth.email-verification.purge-cron`이다. cron 값 `-`는 그 작업을 끈다. `LoginAttemptLimiter.evictExpired`(15분 `fixedDelay`, 로그 없음)는 끌 수 없다.
- 가입 인증번호 요청(`POST /api/auth/email-verification`, `{"email", "ageConfirmed": true}`)은 같은 이메일에 60초에 한 번, 24시간에 10번까지다. 발송은 커밋 뒤 별도 스레드에서 하고 응답은 실패해도 204다. 실패하면 앱 로그에 `가입 인증번호 메일을 보내지 못했습니다`가 남는다.
- 운영에는 아직 실제 사용자가 없다(2026-11-09 사용자 확인). 측정 중 API 중단과 실제 사용자 묶음은 고려하지 않는다.
- 부하 테스트 관례(결정 0039, `docs/ops/load-test.md`): 부하 생성 VM(`govbenefit-loadgen`, `10.10.0.14`)의 `tmux`에서 실행한다. 측정 동안 `cloudflared`를 멈춘다. DB 작업은 `accounts.sh`처럼 db VM의 mysql 컨테이너 안에서 앱 계정으로 한다. 서버 지표는 `export-metrics.sh`가 `steps.tsv` 구간으로 받는다.

## 설계 결정

1. **가짜 SES는 Python 표준 라이브러리 서버로 만든다.** 2026-11-09 사용자 결정.
   - `scripts/loadtest/notify/fake_ses.py`. 부하 생성 VM에서 `python3`로 직접 띄우고 내부 브리지 주소(`10.10.0.14:8025`)에만 연다.
   - `POST /v2/email/outbound-emails`만 받는다. 성공은 `200 {"MessageId": …}`, 오류는 `x-amzn-ErrorType` 헤더와 `{"message": …}` 본문이다(`SesStubServer`와 같은 형식).
   - 응답 지연: 실제 SES 사전 측정의 성공 지연(700건)에서 무작위로 골라 그만큼 늦게 응답한다. 시드를 고정한다.
   - 속도 제한: 초당 14건이 차는 토큰 버킷, 용량 145건. 넘으면 `429 TooManyRequestsException`, 메시지 `Maximum sending rate exceeded.`로 응답한다(실측).
   - 장애 주입은 관리 경로(`/_control/*`)로 켜고 끈다. 시나리오 스크립트가 발송 시작을 보고 시각을 맞추기 위해서다.
   - 요청마다 도착 시각, 수신 주소, 결과, 응답 시각을 JSONL로 남긴다. 유실과 중복은 이 기록으로 센다(이슈).
   - 대안: 테스트의 `SesStubServer`를 실행 파일로 만듦. 빌드 구성과 VM의 JDK가 필요하고, 구간 장애·속도 제한·파일 기록은 어차피 새로 짠다.
   - 대안: WireMock. 토큰 버킷 속도 제한이 없어 확장이 필요하다.
2. **앱의 SES 요청은 측정용 compose 덮어쓰기 파일과 가짜 접근 키로 돌린다.**
   - `scripts/loadtest/notify/compose.notify-fault.yaml`을 app VM의 `/opt/gov-benefit/app/`에 복사하고, 측정 동안 app은 `docker compose -f compose.yaml -f compose.notify-fault.yaml up -d app`으로 띄운다.
   - 덮어쓰기 파일은 `environment`로 `AUTH_MAIL_SES_ENDPOINT=http://10.10.0.14:8025`, 가짜 `SES_ACCESS_KEY_ID`·`SES_SECRET_ACCESS_KEY`를 준다. `environment`가 `env_file`보다 우선한다. 설정을 잘못해 실제 SES에 닿아도 인증에서 거절돼 메일이 나가지 않는다.
   - 같은 파일에서 수집·묶기·인증 기록 파기를 끈다(`GOV24_SCHEDULE_ENABLED=false`, 묶기·파기 cron `-`). 2026-11-09 사용자 결정. 04:00 수집이 발송과 같은 스케줄러 스레드를 붙잡아 측정값이 틀어지지 않게 하고, 묶기가 합성 사용자를 건드리지 않게 하기 위해서다. 그날 수집은 하루 늦게 반영되고 파기도 하루 미뤄진다. 환경 변수 이름과 `-`로 꺼지는지는 로컬 리허설에서 확인한다(단계 9).
   - `app.env`는 고치지 않는다. 측정 전후로 `app.env`·`compose.yaml` 해시와 `APP_TAG`를 남겨 같은지 본다.
   - Docker의 자동 재시작은 컨테이너 설정을 그대로 쓰므로 덮어쓰기가 유지된다.
   - 되돌리기: `docker compose up -d app`(기본 파일만). 덮어쓰기 파일을 지운다.
   - 대안: `app.env`를 직접 고침. 비밀값 파일을 손으로 고치고 되돌려야 한다.
   - 대안: app VM 바깥 송신 차단. 확실하지만 운영 VM 방화벽을 바꿨다가 되돌려야 한다.
3. **합성 데이터는 시나리오마다 새로 넣고 지운다.**
   - 계정 1만 개: `notify-00001@loadtest.invalid` \~ `notify-10000@loadtest.invalid`, `new_benefit_notify = TRUE`. `password_hash`는 BCrypt 형식이 아닌 값(`{bcrypt}notify-fault-no-login`)이라 로그인할 수 없다.
   - 묶음: 계정마다 하나. `digest_date`는 그날, `status = 'PENDING'`, `attempt_count = 0`, `next_attempt_at`·`created_at`은 넣는 시각.
   - 가짜 새 혜택 3건: 2026-11-09 사용자 결정. 운영 데이터에 `READY` 새 혜택이 있는지와 상관없이 같은 조건으로 재기 위해서다.
     - `new_benefit`에 아직 없는 `benefit` 중 ID 순 앞 3건을 골라 `status = 'READY'`, `targets_selected_at`은 넣는 시각, `detected_run_id`는 가장 최근 `collection_run`으로 넣는다. 대상 선정이 이 행을 고르지 않는다.
     - `created_at`·`updated_at`은 `2000-01-01 00:00:00`으로 넣어 측정용 행을 표시한다. 지울 때 이 값으로 찾는다.
     - 알림 항목은 묶음마다 이 3건이다. 메일 본문은 실제 혜택 이름으로 만들어진다.
   - 지우기: 계정을 지우면 묶음·알림 항목이 함께 지워진다. 그 뒤 가짜 새 혜택(`created_at = '2000-01-01 00:00:00'`)과 계정 메일 시나리오의 `email_verification` 행(`notify-mail-%@loadtest.invalid`)을 지운다. 가짜 새 혜택은 FK에 `ON DELETE CASCADE`가 없으므로 알림 항목을 먼저 지운다.
   - 대안: 운영 `new_benefit`의 `READY` 행을 그대로 참조함. 측정하는 날 그런 행이 없으면 잴 수 없고, 날마다 조건이 달라진다.
   - 대안: 가입 API와 묶기로 만듦. 범위 밖인 묶기와 메일 발송이 끼어들고 느리다.
4. **실제 SES 사전 측정은 결과와 프로그램을 저장소에 남긴다.**
   - 2026-11-09 결과(지연, 몰아 보내기, 초당 30건 20초)를 `docs/research/notify-send-fault-baseline/results/ses-probe/`에 둔다. 가짜 SES의 지연 표본은 이 파일에서 만든다.
   - 프로그램은 `scripts/loadtest/notify/ses-probe/`(`SesRealProbe.java`와 실행 스크립트)에 둔다. 측정 구간 밖에서만 실행한다.
   - 측정하는 날 시작 전에 app VM에서 같은 프로그램으로 차례로 100건(초당 5건)을 다시 잰다(2026-11-09 사용자 결정). 내 PC와 인터넷 경로가 다를 수 있어서다. 이 실행 시각은 측정 구간과 따로 적는다. p50이 내 PC 값(93.6\~131.5ms)과 20% 넘게 다르면 가짜 SES 지연 표본을 app VM 값으로 바꾼다.
   - app VM에는 JDK가 없으므로 앱 이미지와 같은 런타임(`eclipse-temurin:21-jre-noble`) 컨테이너에서 `--env-file app.env`로 실행한다. 키는 컨테이너 환경 변수로만 들어가고 화면과 파일에 남지 않는다.
5. **시나리오와 값.** 2026-11-09 사용자 결정. 모든 시나리오는 1만 통이고, 시각은 발송 시작(가짜 SES의 첫 요청) 기준이다.

   | 시나리오 | 주입 | 예상 소요 |
   | --- | --- | --- |
   | `outage-1h` | 0\~60분 동안 모든 요청에 `500 InternalFailure` | 약 2시간 |
   | `normal` | 없음 | 약 25분 |
   | `kill` | 90\~150초 무작위 간격(시드 고정)으로 10번 SIGKILL. 그중 2번은 가짜 SES가 요청을 붙잡은 채 죽인다 | 약 40분 |
   | `deploy` | 5분·12분·19분에 같은 이미지로 app만 다시 만든다 | 약 35분 |
   | `response-loss` | 100번째 요청마다(100건) 기록한 뒤 15초 뒤에 200 | 약 45분 |
   | `outage-10m` | 0\~10분 동안 `500 InternalFailure` | 약 40분 |
   | `account-mail` | 처음부터 끝까지 계정 메일 초당 1건, 5\~7분에는 초당 10건 | 약 30분 |
   | `notify-off` | 1,000명(시드 고정)의 알림을 발송 중 무작위 시각에 DB에서 끈다 | 약 30분 |

   - 몇 시간 장애는 1시간으로 줄인다(2026-11-09 사용자 결정). 하루 안에 끝내기 위해서다. 이슈 본문의 시나리오도 "1시간 동안의 SES 장애"로 고쳤다. 1시간 장애에서는 첫 시도·1분 뒤·5분 뒤 재시도의 일부가 실패하고 5분 뒤·30분 뒤 재시도에서 회복된다. 2시간·6시간 뒤 재시도와 최종 실패는 실측하지 않고, 재시도 간격으로 계산해 보고서에 적는다.
   - 응답 유실의 15초는 앱의 응답 제한(10초)보다 길다. 한 건마다 발송이 10초 멈춘다.
   - 붙잡은 채 죽이기: 가짜 SES가 다음 요청을 기록하고 응답하지 않는 동안 스크립트가 app을 죽이고, 그 뒤 붙잡은 요청을 놓는다. SES 접수 뒤 기록 전 종료를 매번 재현한다.
   - 계정 메일 초당 10건은 알림(초당 7\~9건)과 합쳐 한도를 넘는다. 버킷 용량(145건)을 다 쓰면 알림과 계정 메일이 함께 429를 받는다.
   - 알림 끄기는 DB를 직접 바꾼다. 발송 직전 확인(준비 단계)은 DB 값만 읽으므로 API를 거친 것과 같다. 끈 시각은 `UPDATE`를 커밋한 시각으로 남긴다.
   - 대안: 알림 끄기를 API로 함. 합성 계정에 비밀번호와 로그인 준비가 필요하다.
6. **강제 종료는 호스트 PID로 SIGKILL하고, 배포는 app만 다시 만든다.**
   - 강제 종료: `docker run --rm --pid=host alpine:3.20 kill -KILL "$(docker inspect -f '{{.State.Pid}}' <app 컨테이너>)"`. OOM·크래시처럼 Docker가 다시 띄우므로, 재개 시간에 자동 재시작이 들어간다. 가짜 SES가 최근 2초 안에 요청을 받은 때만 죽인다(발송 중).
   - 배포: `docker compose -f compose.yaml -f compose.notify-fault.yaml up -d --force-recreate app`. 같은 이미지라 이미지 받기 시간은 없다. 종료 신호 → 30초 대기 → interrupt 흐름을 그대로 재현한다.
   - `scripts/deploy.sh`는 쓰지 않는다. `cloudflared`를 띄우고 덮어쓰기 파일 없이 app을 다시 만들어 실제 SES로 보낸다.
   - 대안: `docker kill`. 자동 재시작이 걸리지 않아 스크립트가 다시 띄워야 한다.
   - 대안: 다른 이미지 태그로 교체. 버전 차이가 결과에 섞인다.
7. **측정은 하룻밤에 무인으로 한다.** 2026-11-09 사용자 결정.
   - 00:00 이후에 시작한다. 합성 묶음의 기한이 그날 24:00이므로 자정을 넘기지 않기 위해서다. 예약 작업을 끄므로(설계 2) 04:00·08:00을 피할 필요가 없다.
   - 순서: `outage-1h` → `normal` → `kill` → `deploy` → `response-loss` → `outage-10m` → `account-mail` → `notify-off`. 합쳐 약 7시간 반이다.
   - 시나리오는 합성 묶음에 `PENDING`이 없고 가짜 SES가 2분 동안 요청을 받지 않으면 끝난다. 예상 소요의 두 배가 지나도 끝나지 않으면 그 상태로 기록하고 다음으로 넘어간다. 모두 두 배가 걸려도 15시 무렵 끝나므로 따로 멈추는 시각은 두지 않는다.
   - 사람이 지켜보지 않으므로 `run.sh all`은 끝나거나 중간에 실패하면 스스로 정리한다. 정리 → 합성 데이터 0건 확인 → `switch.sh off` → `cloudflared` 시작 순서다.
   - 합성 데이터가 0건이 아니면 `switch.sh off`를 하지 않고 측정 설정 그대로 멈춘 뒤 이유를 남긴다. 남은 합성 묶음(`.invalid` 주소)이 실제 SES로 나가면 반송이 생겨 SES 계정 평판이 떨어지기 때문이다.
   - 대안: 낮에 사람이 지켜보며 실행. 예약 작업을 켠 채로 04:00·08:00을 피해야 하고, 하루를 비워야 한다.
   - 대안: 이틀(긴 장애 3시간). 6시간 공백까지 실측하지만 측정 준비·정리가 두 번이다.
8. **센 값의 정의.** `count.py`가 가짜 SES 기록과 측정 끝의 묶음 상태를 수신 주소로 맞춰 센다.
   - 접수: 가짜 SES가 받아 들인 요청. `accepted`(200), `accepted_late`(늦게 200), `accepted_held`(붙잡힌 뒤 앱이 죽음)다.
   - 유실: 보내야 할 묶음(취소되지 않은 합성 묶음) 중 접수가 0번인 것.
   - 중복 접수: 같은 수신 주소의 두 번째 이후 접수.
   - 끝나지 않은 묶음: 측정 끝에 `PENDING`. 최종 실패: `FAILED`.
   - 재개 시간: 강제 종료·배포 명령 시각부터 그 뒤 가짜 SES의 첫 요청까지.
   - 총 소요 시간: 첫 요청부터 마지막 접수까지.
   - 계정 메일 실패: `notify-mail-*` 수신 주소 중 접수가 0번인 요청.
   - 알림을 끈 뒤 나간 알림: 끈 시각보다 늦게 도착한 그 사용자의 접수.
9. **다른 예약 작업이 밀린 시간은 지표로 재고, 없으면 발송 로그로 추정한다.**
   - 준비에서 관리 포트(`/actuator/prometheus`)에 `tasks_scheduled_execution` 지표가 있는지 본다. 있으면 `queries.tsv`에 `evictExpired`의 실행 수를 더해 실행 간격으로 잰다.
   - 없으면 발송 요약 로그의 `elapsedMs`(한 실행이 스레드를 붙잡은 시간)로 상한을 적는다. 운영 코드에 로그를 더하지 않는다.
   - 수집·묶기·파기는 꺼 두므로(설계 2) 이 작업들과 발송이 스레드를 다투는 경우는 재지 않는다. 보고서의 한계에 적고, 발송 한 실행이 스레드를 붙잡는 시간으로 그 작업들이 밀릴 수 있는 상한을 적는다.
10. **10만 통은 잰 처리량으로 환산한다.** `normal`의 총 소요 시간 × 10과 그동안 스레드를 붙잡는 시간을 적고, SES 콘솔의 하루 할당량과 비교한다.

## 단계별 구현 계획

1. **가짜 SES** `scripts/loadtest/notify/fake_ses.py`
   - 인자: `--host`, `--port`, `--log <JSONL>`, `--latency <표본 파일>`, `--seed`, `--bucket-capacity 145`, `--bucket-rate 14`.
   - `TokenBucket.take(now) -> bool`, `LatencySampler.sample() -> float`, `Faults`(현재 장애 상태).
   - `POST /v2/email/outbound-emails`: 기록 → 토큰 확인(없으면 429) → 장애 상태(500, 붙잡기, 늦은 응답) → 지연 표본만큼 대기 → 응답. 응답을 쓰다 연결이 끊기면 `client_gone`을 남긴다.
   - 관리 경로: `POST /_control/outage {"status":500,"code":"InternalFailure","on":true|false}`, `POST /_control/late {"every":100,"seconds":15}`, `POST /_control/hold-next`, `POST /_control/release`, `GET /_control/stats`(받은 수, 마지막 요청 시각, 붙잡힌 수). 관리 경로는 `127.0.0.1`에서만 받는다.
   - 로그 레코드: `arrived`, `to`, `result`(`accepted`·`accepted_late`·`accepted_held`·`rate_limited`·`outage`), `responded`, `client_gone`.
2. **실제 SES 사전 측정** `scripts/loadtest/notify/ses-probe/`
   - `SesRealProbe.java`: 앱과 같은 클라이언트 설정. 기본 모드(차례로 100건 + 동시 60건)와 `MODE=sustained`(초당 N건 M초). 시뮬레이터 주소로만 보낸다. 속도 초과·연결 오류가 아닌 실패면 멈춘다.
   - `ses-probe.sh local`: `backend/`의 런타임 클래스패스를 init script로 구해 `java`로 실행한다. SES 값은 환경 변수로만 받는다.
   - `ses-probe.sh app-vm`: 프로그램을 컴파일하고 필요한 jar와 함께 `build/ses-probe/`에 모아 app VM의 `/opt/gov-benefit/loadtest/ses-probe/`에 복사한 뒤, `docker run --rm --env-file /opt/gov-benefit/app/app.env eclipse-temurin:21-jre-noble`로 실행하고 결과를 가져온다(설계 4).
   - 2026-11-09 결과를 `docs/research/notify-send-fault-baseline/results/ses-probe/`로 옮기고, `latency-ms.txt`(성공 지연 700건)를 만든다.
3. **합성 데이터** `scripts/loadtest/notify/sql/`, `scripts/loadtest/notify/data.sh`
   - `seed.sql`: 설계 3. 가짜 새 혜택 3건을 넣고, 재귀 CTE로 계정 1만 개, 묶음, 알림 항목을 넣는다.
   - `cleanup.sql`: 합성 계정, 가짜 새 혜택, `notify-mail-%@loadtest.invalid`의 `email_verification` 행을 이 순서로 지운다.
   - `count.sql`: 합성 계정·합성 묶음·가짜 새 혜택·계정 메일 인증 기록 수. 정리 뒤 모두 0이어야 한다.
   - `status.sql`: 합성 묶음의 수신 주소·상태·시도 횟수·`last_error`·`accepted_at`을 TSV로 뽑는다.
   - `notify-off.sql`: 정해진 사용자의 알림을 끄고 커밋 시각을 돌려준다.
   - `data.sh <seed | cleanup | count | status | notify-off <이메일 목록>>`: `accounts.sh`의 `run_sql`과 같은 방식(`LOADTEST_DB=local` 지원).
4. **측정용 실행 구성** `scripts/loadtest/notify/compose.notify-fault.yaml`, `scripts/loadtest/notify/switch.sh`
   - 덮어쓰기 파일: 설계 2.
   - `switch.sh on`: 해시·`APP_TAG` 기록 → 파일 복사 → 덮어쓰기로 app 재생성 → 상태 `UP` 대기 → 가짜 SES가 요청을 받는지 확인(합성 묶음 1건을 넣어 접수를 보고 지운다).
   - `switch.sh off`: 기본 파일로 app 재생성 → 덮어쓰기 파일 삭제 → 해시·`APP_TAG`가 측정 전과 같은지 확인.
5. **시나리오 실행** `scripts/loadtest/notify/run.sh <시나리오 | all>`
   - 시작 전 확인: `cloudflared`가 멈춰 있다, 시각이 00:00 이후다, `switch.sh on` 상태다(예약 작업 끔 포함), 합성 데이터가 0건이다.
   - 끝나거나 실패하면(`trap`) 설계 7의 정리 순서를 따른다. 결과 디렉터리에 `finish.txt`(끝난 시각, 끝난 이유, 정리 결과, `switch.sh off`·Tunnel 상태)를 남긴다.
   - 시나리오마다: 가짜 SES 재시작(새 로그) → `data.sh seed` → 첫 요청을 기다림 → 설계 5의 주입 → 끝 조건 대기 → `data.sh status` → 앱 로그(발송 요약·실패·계정 메일 실패) 저장 → `steps.tsv`를 쓰고 `export-metrics.sh` → `count.py` → `data.sh cleanup`.
   - 주입 함수: `kill_app`(설계 6), `deploy_app`, `outage_window`, `late_every`, `hold_and_kill`, `notify_off`, `account_mail`(k6).
   - 결과 디렉터리: `<RESULTS_DIR>/<시각>-<시나리오>/`(기본 `docs/research/notify-send-fault-baseline/results`). 파일: `meta.json`(이미지 태그, 실행 구성 해시, 시나리오 값, 주입 시각), `fake-ses.jsonl`, `digests.tsv`, `injections.tsv`, `app-log.txt`, `prometheus.json`, `counts.json`.
6. **계정 메일 요청** `scripts/loadtest/notify/k6/account-mail.js`: `constant-arrival-rate` 두 구간(설계 5). 이메일은 `notify-mail-<번호>@loadtest.invalid`로 매번 다르게, `CF-Connecting-IP`는 가상 사용자별. 이미지 버전은 `scripts/loadtest/run.sh`와 같다.
7. **집계** `scripts/loadtest/notify/count.py <결과 디렉터리>`: 설계 8의 값을 `counts.json`과 사람이 읽는 표로 낸다. 강제 종료·배포는 회차별 재개 시간과 그 회차 앞뒤의 중복을 따로 적는다.
8. **지표** `scripts/loadtest/queries.tsv`: 설계 9에서 지표가 있으면 `scheduled_tasks`(범위 `step`)를 더한다. 앱 재시작 확인용 `app_start_time`(`process_start_time_seconds`)을 더한다.
9. **로컬 리허설**: 로컬 compose MySQL과 `local` 프로필 앱(`AUTH_MAIL_SENDER=ses`, `AUTH_MAIL_SES_ENDPOINT=http://127.0.0.1:8025`)으로 합성 200명, 시나리오마다 짧은 값으로 `run.sh`를 끝까지 돌린다. 덮어쓰기·강제 종료는 로컬 Docker로 만든 앱 이미지로 확인한다. 엔드포인트 환경 변수 바인딩과 429·500·늦은 응답의 분류를 앱 로그로 본다. 예약 작업 끄기는 묶기·파기 cron을 1분 뒤 시각으로 준 실행과 `-`로 준 실행을 비교해 확인한다. 중간 실패 뒤 자동 정리와 합성 데이터가 남았을 때 멈추는지도 본다.
10. **운영 측정**: 설계 7의 일정. 00:00 무렵 시작 전에 SES 하루 할당량을 콘솔에서 적고, `switch.sh on` 전에 `ses-probe.sh app-vm`으로 지연을 다시 잰다(설계 4). `cloudflared` 정지 → `switch.sh on` → 합성 100명으로 짧은 확인 → 부하 생성 VM의 `tmux`에서 `run.sh all`. 아침에 `finish.txt`를 보고, SES 콘솔·CloudWatch `Send`에서 측정 시간대 발송이 없는지 확인한다.
11. **보고서** `docs/research/notify-send-fault-baseline.md`: 측정 기록 형식 1\~6. 규모 가정(사용자 10만 명 중 1만 명, 근거 `docs/research/gov24-api-data.md` §7), 실제 SES 사전 측정(내 PC와 app VM), 시나리오별 표, 회차별 표, 10만 통 환산과 할당량 비교, 1시간 장애에서 실측하지 않은 재시도의 계산, 실제 SES로 나간 요청이 없었다는 근거, 정리 결과, 한계(예약 작업을 꺼 경합을 재지 않음, 가짜 새 혜택 3건 고정), 확인된 문제(후속 이슈 후보).
12. **절차** `docs/ops/load-test.md`: "알림 발송 장애 측정" 절. 준비(00:00 이후, 할당량 기록, app VM에서 SES 지연 재측정, Tunnel 정지, 가짜 SES 시작, `switch.sh on`, 짧은 확인), 실행(`tmux`에서 `run.sh all`), 끝난 뒤(`finish.txt`, SES 콘솔 확인), 자동 정리가 멈췄을 때(합성 데이터를 지우고 0건을 확인한 뒤 `switch.sh off`와 Tunnel 시작).

## 완료 조건 매핑

- [ ] 측정하는 동안 실제 SES로 나간 요청이 없었다는 확인 결과가 남아 있다. → 설계 2. 단계 4·10·11(가짜 SES 사전 수신, 해시 비교, SES 콘솔·CloudWatch).
- [ ] 시나리오마다 유실, 중복 접수, 끝나지 않은 묶음, 최종 실패 건수가 있다. 가짜 SES 기록과 묶음 상태를 대조해 센 값이다. → 설계 5·8. 단계 5·7.
- [ ] 강제 종료와 배포 시나리오에는 회차별 결과와, 다시 시작한 뒤 발송이 재개될 때까지 걸린 시간이 있다. → 설계 6·8. 단계 5·7.
- [ ] 정상 발송의 총 소요 시간과 그동안 다른 예약 작업이 밀린 시간이 있고, 10만 통일 때의 환산값이 있다. → 설계 9·10. 단계 8·11.
- [ ] 긴 SES 장애 시나리오에서 기한 안에 접수된 묶음 수와 최종 실패한 묶음 수가 있다. → 설계 5(`outage-1h`)·8. 단계 11.
- [ ] 발송 중 실패한 계정 메일 수와, 알림을 끈 뒤 나간 알림 수가 있다. → 설계 5·8. 단계 5·6·7.
- [ ] 측정이 끝난 뒤 운영 DB에 합성 사용자, 묶음, 가짜 새 혜택이 남아 있지 않고, 앱 설정과 Tunnel이 측정 전과 같다. → 설계 2·3·7. 단계 3·4·5·10.
- [ ] 결과가 측정 기록 형식에 맞춰 `docs/research/`에 있고, 같은 조건으로 다시 잴 수 있는 스크립트와 절차가 저장소에 있다. → 설계 4. 단계 1\~8·11·12.

## 검증 명령

- `python3 -m py_compile scripts/loadtest/notify/fake_ses.py scripts/loadtest/notify/count.py`
- `bash -n scripts/loadtest/notify/*.sh`
- 로컬 리허설(단계 9): `docker compose -f docker-compose.local.yml up -d` 뒤 `LOADTEST_DB=local scripts/loadtest/notify/run.sh all`(리허설 값). `counts.json`의 유실·중복이 주입한 대로 나오는지 본다(정상 0, 응답 유실은 늦은 응답 수만큼 중복).
- 운영 코드는 바꾸지 않는다. `backend/`에서 `./gradlew test`가 통과하는지만 본다.

## 결정 필요

없음. 아래는 2026-11-09에 사용자가 정했다.

1. 가짜 SES는 Python 표준 라이브러리 서버이고, 지연·속도 제한은 실제 SES 사전 측정값을 따른다(설계 1).
2. 몇 시간 장애는 1시간으로 줄이고 하루에 측정한다. 이슈 본문도 고쳤다(설계 5·7).
3. 시나리오 값은 설계 5의 표다.
4. 응답이 아예 오지 않는 장애는 넣지 않고 후속 이슈도 만들지 않는다.
5. 측정하는 날 시작 전에 app VM에서 SES 지연을 다시 잰다(설계 4).
6. 00:00 이후에 무인으로 돌리고, 측정하는 동안 수집·묶기·인증 기록 파기를 끄고, 가짜 새 혜택을 쓴다. 실제 사용자가 없으므로 실제 사용자 대비 장치는 두지 않는다. 이슈 본문의 측정 환경도 고쳤다(설계 2·3·7).
