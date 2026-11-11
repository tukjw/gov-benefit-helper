# 부하 테스트

부하 생성 VM에서 운영 API의 지연·처리량을 재는 절차다. 구조와 측정 방식은 [결정 0039](../decisions/0039-proxmox-deploy-metrics-and-load-test.md), 기준 측정 결과는 [부하 테스트 기준 측정](../research/load-test-baseline.md)에 있다. 부하 생성 VM은 [Proxmox VM 만들기](proxmox-vm.md)로 먼저 만든다.

테스트 계정 비밀번호와 DB 비밀번호는 이 문서와 저장소에 적지 않는다.

## 구성 요약

| 무엇 | 어디에 |
| --- | --- |
| 부하 생성 | 부하 생성 VM(`govbenefit-loadgen`, `10.10.0.14`)의 k6 컨테이너. 이미지 버전은 `scripts/loadtest/run.sh`에 고정 |
| 실행 위치 | 부하 생성 VM의 저장소(`~/gov-benefit-helper`)에서 `tmux` 안. 이 VM은 전용 키로 app·db·지표 VM에 SSH로 접속한다([Proxmox VM 만들기](proxmox-vm.md) 6). 내 PC의 연결이 끊기거나 내 PC가 꺼져도 측정은 계속된다 |
| 대상 | 운영 app VM의 앱 `http://10.10.0.11:8080`(내부 브리지). Cloudflare를 거치지 않는다 |
| 시나리오 | `scripts/loadtest/k6/`의 `search`(혜택 검색), `detail`(혜택 상세), `guest-match`(비회원 조건 비교 목록), `login`(로그인), `mixed`(혼합) |
| 테스트 계정 | 운영 DB의 `loadtest-0001@loadtest.invalid` ~ `loadtest-1000@loadtest.invalid`. `scripts/loadtest/accounts.sh`로 넣고 지운다. 비밀번호는 부하 생성 VM의 `~/.local/state/gov-benefit-loadtest/password`(권한 600)에만 있다 |
| 서버 지표 | 지표 VM의 Prometheus. `scripts/loadtest/export-metrics.sh`가 단계 구간마다 `scripts/loadtest/queries.tsv`의 쿼리를 받는다 |
| 결과 | 부하 생성 VM 저장소의 `<RESULTS_DIR>/<시각>-<시나리오>/`. `RESULTS_DIR`의 기본값은 `docs/research/load-test-baseline/results`다. 끝나면 내 PC로 가져와 커밋한다 |

## 측정하는 동안 지킬 것

- API를 막는다(`cloudflared` 중지). 그동안 `api.govbenefit.kr`은 응답하지 않고, 프론트는 열려 있지만 API 요청이 실패한다.
- 03:30~05:00과 겹치지 않는다. 04:00 예약 수집이 공공 API를 부른다. `run.sh`는 예상 시간이 이 시간대와 겹치면 시작하지 않는다.
- 배포하지 않는다. app VM에서 서비스 이름 없이 `docker compose up -d`를 실행하지 않는다. 둘 다 `cloudflared`를 다시 띄운다.
- 시나리오에 메일이 나가는 경로(가입·인증번호·비밀번호 재설정)와 수집을 넣지 않는다.

## 1. 준비

1. 시간대를 고른다. Grafana의 `앱` 대시보드에서 최근 7일 요청이 적은 시간을 찾는다. 시나리오 하나가 거친 단계와 세밀 단계를 합쳐 50분 안팎이고, 다섯 시나리오면 4시간 가까이 된다. 시나리오마다 나눠 여러 번 해도 된다.
2. Proxmox 호스트에서 부하 생성 VM을 켠다: `qm start 104`
3. 내 PC에서 부하 생성 VM에 접속해 `tmux`를 열고, 저장소를 최신으로 맞춘다. 2부터 5의 3(API 풀기)까지의 명령은 이 `tmux` 안에서 실행한다.

   ```bash
   ssh govbenefit-loadgen
   tmux new -A -s loadtest   # 연결이 끊긴 뒤 다시 붙을 때도 같은 명령
   cd ~/gov-benefit-helper && git pull
   ```

   `Ctrl+b` 다음 `d`로 `tmux`에서 빠져나와도 실행은 계속된다. 머지 전 브랜치로 잴 때는 `git fetch && git switch <브랜치>`로 바꾼다.

## 2. 테스트 계정 넣기

부하 생성 VM의 저장소에서:

```bash
scripts/loadtest/accounts.sh seed
```

`accounts 1000`, `refresh_tokens 0`이 나온다. 비밀번호 파일이 이미 있다고 나오면 지난 측정의 계정이 남은 것이니 `accounts.sh cleanup`을 먼저 실행한다.

## 3. API 막기

```bash
ssh govbenefit-app 'cd /opt/gov-benefit/app && docker compose stop cloudflared'
```

확인:

- `curl -s -o /dev/null -w '%{http_code}\n' https://api.govbenefit.kr/api/profile/options`가 `200`이 아니다. Tunnel 연결이 끊겨 Cloudflare가 오류를 돌려준다.
- `curl -s -o /dev/null -w '%{http_code}\n' http://10.10.0.11:8080/api/profile/options`가 `200`이다. 닿지 않으면 app VM의 실행 구성이 앱 포트를 내부 브리지에 열기 전 것이다. 결정 0037이 들어간 릴리스를 배포한 뒤 다시 한다.

## 4. 시나리오 실행

부하 생성 VM의 저장소에서 실행한다. 다섯 시나리오를 차례로 잴 때는 하나로 끝난다.

```bash
scripts/loadtest/run.sh all
```

시나리오를 나눠 잴 때는 이름만 준다: `scripts/loadtest/run.sh search`. 순서는 `search` → `detail` → `guest-match` → `login` → `mixed`이고, `all`은 하나가 실패하면 남은 시나리오를 실행하지 않고 알려 준다. `login`과 `mixed`는 2의 테스트 계정이 있어야 한다. 시나리오 하나는 50분 안팎, `all`은 4시간 가까이 걸린다.

시나리오마다 `run.sh`가 단계를 정한다.

1. 시작 속도부터 두 배씩 올려(거친 단계) 한계 단계를 찾는다. 상한은 초당 1,280건(`MAX_RATE`)이다. 워밍업은 시작 속도로 한다.

   | 시나리오 | 시작 속도(초당) |
   | --- | --- |
   | `search`, `detail`, `login` | 5 |
   | `guest-match` | 1. 요청마다 지원조건 원문과 혜택 전체를 읽어 판정해 다른 경로보다 훨씬 무겁다(로컬에서 동시 1개일 때 p50 640ms) |
   | `mixed` | 2. 조건 비교가 20%다 |

2. 한계 단계가 나오면 마지막 정상 속도로 워밍업 길이(3분)만큼 회복한 뒤, 그 사이를 25% 간격으로 다시 잰다(세밀 단계, 정수로 반올림). 여기서 한계 단계가 나오면 그 단계가 최종 한계다. 사이에 정수가 없으면(예: 1과 2) 건너뛴다. 기준 p95와 입력은 거친 단계와 같다.
3. 한계 단계는 다음 중 하나가 처음 나타난 단계다. 판정 결과는 `meta.json`의 `limit`에 남는다.
   - 응답 수가 목표의 95% 미만이거나, 못 보낸 요청이 1 이상이다.
   - 오류율이 1%를 넘는다.
   - p95가 기준(첫 단계의 p95)의 2배를 넘고, 늘어난 폭이 50ms 이상이다.

단계가 끝날 때마다 한 줄이 나온다. Ctrl+C로 멈추면 결과를 가져오지 못하니 쓰지 않는다.

```
rate-40: 목표 40/s, 응답 7202건(39.9/s), p50 21.3ms, p95 48.0ms, p99 75.2ms, 오류율 0.00%, 못 보낸 요청 0
한계 단계: rate-80(p95가 기준(18ms)의 2배 초과).
세밀 단계: 50 60 70(/s). 마지막 정상 속도(40/s)로 3분 회복한 뒤 잰다.
...
한계: rate-60(p95가 기준(18ms)의 2배 초과)
```

`run.sh`가 하는 일:

- 시작 전 확인: 예약 수집 시간대와 겹치지 않는지, app VM의 `cloudflared`가 멈춰 있는지 본다.
- 준비: k6 시나리오를 부하 생성 VM의 작업 디렉터리 `/opt/gov-benefit/loadtest/`에 복사하고, `prepare.py`로 입력 데이터(서비스 ID 1,000개, 프로필 200개)를 만든다.
- 실행: 워밍업 뒤 요청 속도마다 k6를 3분씩 실행하고 `vmstat`을 함께 남긴다. 모든 요청에 가상 사용자별 `CF-Connecting-IP`를 붙인다. 위 1~3대로 단계를 정한다.
- 기록: 결과를 저장소의 결과 디렉터리에 모은다. 이미지 태그, 실행 구성 해시, VM 사양, 혜택 수, 단계를 `meta.json`에 쓰고, 같은 구간의 서버 지표와 앱 로그 확인 결과를 저장한다.

| 결과 파일 | 내용 |
| --- | --- |
| `meta.json` | 실행 환경과 단계별 시작·끝 시각(epoch 초) |
| `inputs.json` | 입력 데이터. `INPUTS`로 다시 쓸 수 있다 |
| `warmup.json`, `recovery.json`, `rate-<N>.json` | 단계별 k6 요약(`recovery`는 세밀 단계 앞 회복). 지연은 실패 요청을 포함하고, `{name:…}` 하위 지표가 경로별 값이다 |
| `vmstat-<단계>.txt` | 부하 생성 VM의 CPU(생성기가 병목이 아니었다는 근거). 단계 중 `id`(유휴)가 20% 아래로 떨어지면 생성기가 병목일 수 있다. 그 단계는 버리고 부하 생성 VM의 vCPU를 늘려(`qm set 104 --cores 4 --affinity <C5·C6 스레드>`) 다시 잰다 |
| `prometheus.json` | 같은 구간의 서버 지표. 단계 이름(측정 전체 구간은 `all`) → 쿼리 이름 → Prometheus `query_range` 응답 원본으로 묶었고, 쿼리 하나가 한 줄이다. 쿼리 이름은 `scripts/loadtest/queries.tsv`에 있다 |
| `app-log-check.txt` | 측정 구간의 앱 로그에서 `수집`·`메일`을 찾은 결과 |

내 PC의 저장소 루트에서 실행해도 같은 일을 한다. 이때는 부하 생성 VM의 명령을 SSH로 실행하므로 내 PC가 측정 내내 켜져 있어야 한다.

`run.sh`가 중간에 실패하면 그때까지의 결과를 가져온다. 서버 지표만 받지 못했으면 `scripts/loadtest/export-metrics.sh <결과 디렉터리>`로 다시 받는다.

## 5. 끝난 뒤

순서대로 한다. 계정을 먼저 지워, API가 열린 뒤 테스트 계정으로 로그인할 수 있는 때가 없게 한다.

1. 테스트 계정을 지운다: `scripts/loadtest/accounts.sh cleanup`. `accounts 0`, `refresh_tokens 0`이 나오고 비밀번호 파일이 지워진다.
2. 앱 상태를 본다.

   ```bash
   ssh govbenefit-app 'curl -s http://127.0.0.1:8081/actuator/health'   # {"status":"UP"}
   ssh govbenefit-app 'cd /opt/gov-benefit/app && docker compose logs --since 4h app | grep -c OutOfMemoryError'   # 0
   ```

   `UP`이 아니거나 `OutOfMemoryError`가 있으면 앱만 다시 띄우고 `UP`을 기다린다: `ssh govbenefit-app 'cd /opt/gov-benefit/app && docker compose restart app'`. 서비스 이름을 빼지 않는다.
3. API를 푼다: `ssh govbenefit-app 'cd /opt/gov-benefit/app && docker compose start cloudflared'`. `curl -s -o /dev/null -w '%{http_code}\n' https://api.govbenefit.kr/api/profile/options`가 `200`이다.
4. 결과를 내 PC로 가져온다. 내 PC의 저장소 루트에서 실행하고, 가져온 뒤 부하 생성 VM의 결과는 지운다(다음 측정 결과와 섞이지 않게).

   ```bash
   results=docs/research/load-test-baseline/results   # RESULTS_DIR을 줬으면 그 값
   mkdir -p "$results"
   scp -r "govbenefit-loadgen:gov-benefit-helper/$results/*" "$results/"
   ssh govbenefit-loadgen "rm -rf gov-benefit-helper/$results"
   ```

5. Proxmox 호스트에서 부하 생성 VM을 끈다: `qm shutdown 104`
6. 메일 발송과 공공 API 호출이 없었는지 확인한다.
   - 결과 디렉터리마다 `app-log-check.txt`에 머리줄 말고 내용이 없다.
   - `prometheus.json`의 `all.collection_runs`와 `all.mail_requests` 값이 구간 처음과 끝에서 같거나, 결과가 비어 있다(앱을 띄운 뒤 한 번도 없었다). `jq -c '.all.collection_runs.data.result, .all.mail_requests.data.result' <결과 디렉터리>/prometheus.json`으로 본다.
   - SES 콘솔(서울 리전) **Account dashboard**의 발송 지표나 CloudWatch `AWS/SES` 네임스페이스의 `Send` 지표에 측정 시간대의 발송이 없다.
7. 결과 디렉터리와 보고서(기준 측정은 [부하 테스트 기준 측정](../research/load-test-baseline.md))를 커밋해 PR로 올린다.

## 중간에 끊겼을 때

내 PC의 연결만 끊겼으면 측정은 `tmux` 안에서 계속되고 있다. `ssh govbenefit-loadgen` 뒤 `tmux new -A -s loadtest`로 다시 붙는다. `run.sh`가 실패했거나 부하 생성 VM이 꺼졌으면 5의 1~3(계정 지우기, 앱 상태 확인, API 풀기)을 먼저 한다. 부하 생성 VM에 k6 컨테이너가 남았으면 `docker ps`로 보고 `docker stop <컨테이너>`로 멈춘다.

## 다시 잴 때

성능 개선 전후를 비교할 때는 같은 스크립트·단계·판정 기준으로 잰다(결정 0039). 기준 측정과 같은 단계로 재려면 속도를 직접 준다: `scripts/loadtest/run.sh search 5 10 20 40`. 이때는 정한 단계만 재고, 한계 단계가 나오면 멈춘다. 같은 입력으로 재려면 `INPUTS`로 기준 측정의 `inputs.json`을 넘긴다. 데이터 규모(혜택 수)가 달라졌으면 보고서에 함께 적는다. 개선 측정의 결과는 `RESULTS_DIR`로 그 작업의 보고서 옆에 둔다(예: `RESULTS_DIR=docs/research/guest-match-throughput/results`).

## 알림 발송 장애 측정

운영 앱이 합성 사용자에게 새 혜택 알림을 보내는 동안 장애를 주입해 유실·중복·복구 시간을 재는 절차다(#137, 플랜 [`docs/plans/137-notify-send-fault-baseline.md`](../plans/137-notify-send-fault-baseline.md), 보고서 [`docs/research/notify-send-fault-baseline.md`](../research/notify-send-fault-baseline.md)). 위의 API 부하 테스트와 따로 하며, 부하 생성 VM에서 사람이 지켜보지 않고 돌린다(00:00 이후 시작, 7시간 안팎). 스크립트는 `scripts/loadtest/notify/`에 있다.

| 무엇 | 어디에 |
| --- | --- |
| 가짜 SES | `fake_ses.py`. `run.sh`가 시나리오마다 부하 생성 VM에서 새로 띄운다. 앱이 부르는 주소는 `10.10.0.14:8025`, 관리 경로는 `127.0.0.1:8026`. 지연은 실제 SES 사전 측정의 표본(`docs/research/notify-send-fault-baseline/results/ses-probe/latency-ms.txt`), 속도 제한은 초당 14건·용량 145건 |
| 측정용 실행 구성 | `compose.notify-fault.yaml`. `switch.sh on`이 app VM에 복사하고 app만 다시 만든다. 가짜 SES 주소, 가짜 접근 키, 수집·묶기·인증 기록 파기 끄기. `app.env`는 고치지 않는다 |
| 합성 데이터 | `data.sh`. 가짜 새 혜택 3건(`new_benefit.created_at = 2000-01-01`), 사용자 1만 명(`notify-00001@loadtest.invalid` \~), 사용자마다 발송 대기 묶음 하나. 시나리오마다 넣고 지운다 |
| 시나리오 | `run.sh all`이 `outage-1h` → `normal` → `kill` → `deploy` → `response-loss` → `outage-10m` → `account-mail` → `notify-off`를 차례로 잰다. 값은 `run.sh` 머리말 |
| 결과 | `docs/research/notify-send-fault-baseline/results/<시각>-all/<시나리오>/`, 요약은 `<시각>-all/finish.txt` |

지킬 것:

- 00:00 이후에 시작한다. 합성 묶음은 그날 24:00까지만 보내므로, `run.sh`는 23:50 안에 끝나지 않을 수 있으면 시작하지 않는다. 예약 작업을 끄므로 04:00 수집, 08:00 묶기와는 겹쳐도 된다. 그날 수집은 하루 늦게 반영된다.
- 측정하는 동안 배포하지 않는다. `scripts/deploy.sh`와 서비스 이름 없는 `docker compose up -d`는 덮어쓰기 파일 없이 app을 다시 만들어 실제 SES로 보낸다.
- 합성 데이터가 0건이 아니면 실제 SES 설정으로 되돌리지 않는다. 남은 `.invalid` 주소로 실제 SES가 보내면 반송이 생겨 SES 계정 평판이 떨어진다. `switch.sh off`가 이를 확인한다.

### 준비(20분 정도)

1. SES 콘솔(서울 리전) **Account dashboard**에서 하루 할당량과 초당 한도를 적어 둔다.
2. 내 PC의 저장소 루트에서 app VM의 실제 SES 지연을 잰다. 메일박스 시뮬레이터로만 100통을 보내고, 운영 앱 이미지와 `app.env`를 일회용 컨테이너로 쓰므로 키는 VM 밖으로 나오지 않는다. 실행 중인 앱은 건드리지 않는다.

   ```bash
   scripts/loadtest/notify/ses-probe/ses-probe.sh app-vm
   ```

   `[latency]`의 p50이 2026-11-09 내 PC 값(131.5ms)과 20% 넘게 다르면 지연 표본을 바꾸고 커밋·push한다: `scripts/loadtest/notify/ses-probe/ses-probe.sh latency <결과 디렉터리>/results.jsonl`
3. 부하 생성 VM을 켜고 접속해 `tmux`를 열고 저장소를 맞춘다(1. 준비의 2\~3과 같다). 머지 전이면 브랜치로 바꾼다.
4. Tunnel을 멈춘다(3. API 막기와 같다): `ssh govbenefit-app 'cd /opt/gov-benefit/app && docker compose stop cloudflared'`
5. 측정용 실행 구성으로 바꾼다. app이 UP이 되면 합성 사용자 1명의 묶음을 가짜 SES가 접수하는지 보고 지운다.

   ```bash
   scripts/loadtest/notify/switch.sh on
   ```

6. 100명으로 짧게 확인한다. `[normal]` 요약에 묶음 100건 `ACCEPTED`, 못 받은 묶음 0, 중복 0이 나오면 된다. `응답 → 다음 요청` p50이 50ms를 넘으면 가짜 SES의 응답이 늦게 나가고 있는 것이니 시작하지 않는다. 막 다시 만든 app이라 본 측정보다 느리게 나온다. 2026-11-10의 이 확인은 Nagle과 지연 ACK가 있던 첫 측정에서 73.6ms, 고친 뒤 35.1ms였다.

   ```bash
   USERS=100 IDLE_END_S=30 SKIP_METRICS=1 RESULTS_DIR=/tmp/notify-check scripts/loadtest/notify/run.sh normal
   ```

7. 측정을 시작하고 `tmux`에서 빠져나온다(`Ctrl+b` 다음 `d`). 7시간 안팎 걸린다.

   ```bash
   scripts/loadtest/notify/run.sh all
   ```

`run.sh all`은 끝나거나 중간에 실패하면 스스로 정리한다: 합성 데이터 지우기 → 0건 확인 → `switch.sh off`(기본 실행 구성으로 app 재생성, 실행 구성 파일 해시와 `APP_TAG`를 측정 전과 비교) → Tunnel 켜기. 합성 데이터가 남으면 측정용 실행 구성과 Tunnel 정지를 그대로 두고 멈춘다. 시나리오가 예상 시간의 2배를 넘기면 그 상태로 기록하고 다음 시나리오로 넘어간다.

### 끝난 뒤

1. `finish.txt`를 본다. 남은 합성 데이터가 모두 0이고, `switch.sh off`가 `yes`, Tunnel이 실행 중이어야 한다. 시나리오별 집계도 여기 있다.

   ```bash
   cat docs/research/notify-send-fault-baseline/results/*-all/finish.txt
   ```

2. `curl -s -o /dev/null -w '%{http_code}\n' https://api.govbenefit.kr/api/profile/options`가 `200`이다.
3. SES 콘솔 **Account dashboard**의 발송 지표나 CloudWatch `AWS/SES`의 `Send`에 측정 시간대(사전 측정 100통 제외)의 발송이 없다.
4. 결과를 내 PC로 가져와 커밋하고, 부하 생성 VM의 결과는 지운다. 내 PC의 저장소 루트에서 실행한다. `results/` 아래에는 저장소가 관리하는 `ses-probe/`도 있으므로 `results/` 전체가 아니라 측정한 디렉터리만 지정한다.

   ```bash
   results=docs/research/notify-send-fault-baseline/results
   run=<시각>-all   # finish.txt가 있는 디렉터리 이름
   scp -r "govbenefit-loadgen:gov-benefit-helper/$results/$run" "$results/"
   ssh govbenefit-loadgen "rm -rf gov-benefit-helper/$results/$run"
   ```

| 결과 파일(시나리오마다) | 내용 |
| --- | --- |
| `meta.json` | 이미지 태그, 실행 구성 해시, 시나리오 값, 시각(시작, 데이터 넣음, 첫 요청, 끝), 끝난 이유(`done`·`timeout`) |
| `fake-ses.jsonl.gz` | 가짜 SES가 받은 요청 전체(도착 시각, 수신 주소, 종류, 결과, 응답 시각) |
| `digests.tsv` | 끝났을 때 합성 묶음의 상태, 시도 횟수, 마지막 오류, 접수 시각 |
| `injections.tsv` | 주입 시각. 강제 종료·배포는 회차별 명령·완료·UP 시각 |
| `notify-off.tsv`, `k6-summary.json` | 알림을 끈 사용자와 UPDATE 전후 시각, 계정 메일 요청 k6 요약 |
| `app-log.txt.gz` | 측정 구간의 앱 로그(배포로 지워진 이전 컨테이너 로그 포함) |
| `prometheus.json` | 같은 구간의 서버 지표. `scheduled_task_*`가 예약 작업 실행 기록이다 |
| `counts.json`, `counts.txt` | `count.py`의 집계. 다시 셀 때: `python3 scripts/loadtest/notify/count.py <결과 디렉터리>` |

### 시나리오 하나만 다시 잴 때

`run.sh <시나리오>`는 그 시나리오의 합성 데이터만 정리하고, 실행 구성과 Tunnel은 되돌리지 않는다. 부하 생성 VM의 `tmux`에서 차례로 한다(준비의 3\~5를 먼저 한다).

```bash
scripts/loadtest/notify/run.sh kill
scripts/loadtest/notify/switch.sh off
ssh govbenefit-app 'cd /opt/gov-benefit/app && docker compose start cloudflared'
```

결과는 `results/<시각>-<시나리오>/`에 생긴다. 가져오기와 지우기는 끝난 뒤의 4와 같다. 개선 전후를 비교하는 측정은 `RESULTS_DIR`로 그 작업의 보고서 옆에 둔다(예: `RESULTS_DIR=docs/research/notify-send-graceful-shutdown/results scripts/loadtest/notify/run.sh deploy`). 회차별 집계의 "멈춤 요약 로그"는 명령 뒤 이전 앱이 `interrupted=true` 발송 요약을 남기기까지 걸린 시간이다(결정 0049).

### 자동 정리가 멈췄을 때

`finish.txt`에 합성 데이터가 남았다고 나오거나, `run.sh`가 강제로 끝나(부하 생성 VM 재시작 등) `finish.txt`가 없을 때 부하 생성 VM의 저장소에서 차례로 한다.

1. 남은 가짜 SES와 k6를 멈춘다: `pkill -f fake_ses.py; docker stop notify-account-mail`
2. 합성 데이터를 지우고 모두 0인지 본다: `scripts/loadtest/notify/data.sh cleanup`
3. 기본 실행 구성으로 되돌린다: `scripts/loadtest/notify/switch.sh off`
4. Tunnel을 켠다: `ssh govbenefit-app 'cd /opt/gov-benefit/app && docker compose start cloudflared'`

`switch.sh status`로 지금 실행 구성, Tunnel, 덮어쓰기 파일을 볼 수 있다.

### 로컬 리허설

스크립트를 고쳤으면 운영에서 재기 전에 로컬에서 끝까지 돌려 본다. 로컬 compose MySQL이 떠 있어야 한다.

```bash
scripts/loadtest/notify/rehearsal/setup.sh /tmp/notify-rehearsal   # 지금 코드로 이미지를 만들고 app을 띄운다
# setup.sh가 출력한 export 줄을 실행한 뒤
scripts/loadtest/notify/switch.sh on
(cd /tmp/notify-rehearsal && docker compose stop cloudflared)
USERS=300 IDLE_END_S=40 EXPECTED_MIN=6 KILLS=2 KILL_MIN_S=10 KILL_MAX_S=15 DEPLOY_AT_MIN=0.3 \
  OUTAGE_LONG_MIN=1 OUTAGE_SHORT_MIN=0.5 MAIL_MINUTES=1 PEAK_START_MIN=0 PEAK_MIN=1 PEAK_RATE=5 \
  NOTIFY_OFF_USERS=30 NOTIFY_OFF_BATCHES=5 NOTIFY_OFF_WINDOW_MIN=1 RESULTS_DIR=/tmp/notify-rehearsal/results \
  scripts/loadtest/notify/run.sh all
```

끝나면 `cd /tmp/notify-rehearsal && docker compose down`으로 리허설 app을 내린다.
