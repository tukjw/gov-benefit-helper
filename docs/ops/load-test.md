# 부하 테스트

부하 생성 VM에서 운영 API의 지연·처리량을 재는 절차다. 구조와 측정 방식은 [결정 0037](../decisions/0037-proxmox-deploy-metrics-and-load-test.md), 기준 측정 결과는 [부하 테스트 기준 측정](../research/load-test-baseline.md)에 있다. 부하 생성 VM은 [Proxmox VM 만들기](proxmox-vm.md)로 먼저 만든다.

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
| 결과 | 부하 생성 VM 저장소의 `docs/research/load-test-baseline/results/<시각>-<시나리오>/`. 끝나면 내 PC로 가져와 커밋한다 |

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

부하 생성 VM의 저장소에서 시나리오마다 실행한다.

```bash
scripts/loadtest/run.sh <시나리오> <요청 속도(초당)>...
```

1. 두 배씩 올리는 거친 단계로 한계 단계를 찾는다. 시작 단계는 시나리오마다 다르다.

   | 시나리오 | 거친 단계 예 | 워밍업 |
   | --- | --- | --- |
   | `search`, `detail` | `5 10 20 40 80 160` | 기본(초당 5건) |
   | `login` | `5 10 20 40 80` | 기본 |
   | `guest-match` | `1 2 4 8` | `WARMUP_RATE=1` |
   | `mixed` | `2 4 8 16 32` | `WARMUP_RATE=2` |

   - 비회원 조건 비교 목록은 요청마다 지원조건 원문과 혜택 전체를 읽어 판정해 다른 경로보다 훨씬 무겁다. 로컬에서는 동시 1개일 때 p50이 640ms였다.
   - `login`과 `mixed`는 2의 테스트 계정이 있어야 한다.
2. 단계가 끝날 때마다 한 줄이 나온다. 다음 중 하나가 처음 나타난 단계가 한계 단계다. `run.sh`가 단계마다 계산해, 한계 단계가 나오면 남은 단계를 실행하지 않고 멈춘다. 판정 결과는 `meta.json`의 `limit`에 남는다. Ctrl+C로 멈추면 결과를 가져오지 못하니 쓰지 않는다.
   - 응답 수가 목표의 95% 미만이거나, 못 보낸 요청이 1 이상이다.
   - 오류율이 1%를 넘는다.
   - p95가 기준(첫 단계의 p95)의 2배를 넘고, 늘어난 폭이 50ms 이상이다.

   ```
   rate-40: 목표 40/s, 응답 7202건(39.9/s), p50 21.3ms, p95 48.0ms, p99 75.2ms, 오류율 0.00%, 못 보낸 요청 0
   한계 단계: rate-80(p95가 기준(18ms)의 2배 초과). 남은 단계는 실행하지 않는다.
   ```

3. 마지막 정상 단계와 한계 단계 사이를 25% 간격으로 다시 잰다. 거친 단계와 같은 잣대로 판정하도록 거친 단계 첫 단계의 p95를 `BASE_P95_MS`로 넘기고, 같은 입력으로 재도록 첫 결과의 `inputs.json`을 넘긴다. 워밍업은 마지막 정상 단계 속도로 한다.

   ```bash
   scripts/loadtest/run.sh search 5 10 20 40 80 160
   # 거친 단계에서 40이 마지막 정상 단계, 80이 한계 단계였다면
   BASE_P95_MS=18 WARMUP_RATE=40 INPUTS=docs/research/load-test-baseline/results/<첫 결과>/inputs.json \
     scripts/loadtest/run.sh search 50 60 70
   ```

`run.sh`가 하는 일:

- 시작 전 확인: 예약 수집 시간대와 겹치지 않는지, app VM의 `cloudflared`가 멈춰 있는지 본다.
- 준비: k6 시나리오를 부하 생성 VM의 작업 디렉터리 `/opt/gov-benefit/loadtest/`에 복사하고, `prepare.py`로 입력 데이터(서비스 ID 1,000개, 프로필 200개)를 만든다.
- 실행: 워밍업 뒤 요청 속도마다 k6를 3분씩 실행하고 `vmstat`을 함께 남긴다. 모든 요청에 가상 사용자별 `CF-Connecting-IP`를 붙인다. 한계 단계가 나오면 멈춘다.
- 기록: 결과를 저장소의 결과 디렉터리에 모은다. 이미지 태그, 실행 구성 해시, VM 사양, 혜택 수, 단계를 `meta.json`에 쓰고, 같은 구간의 서버 지표와 앱 로그 확인 결과를 저장한다.

| 결과 파일 | 내용 |
| --- | --- |
| `meta.json` | 실행 환경과 단계별 시작·끝 시각(epoch 초) |
| `inputs.json` | 입력 데이터. `INPUTS`로 다시 쓸 수 있다 |
| `warmup.json`, `rate-<N>.json` | 단계별 k6 요약. 지연은 실패 요청을 포함하고, `{name:…}` 하위 지표가 경로별 값이다 |
| `vmstat-<단계>.txt` | 부하 생성 VM의 CPU(생성기가 병목이 아니었다는 근거). 단계 중 `id`(유휴)가 20% 아래로 떨어지면 생성기가 병목일 수 있다. 그 단계는 버리고 부하 생성 VM의 vCPU를 늘려(`qm set 104 --cores 4 --affinity <C5·C6 스레드>`) 다시 잰다 |
| `prometheus/<단계>/*.json`, `prometheus/all/*.json` | 같은 구간의 서버 지표(Prometheus `query_range` 응답 원본) |
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
   mkdir -p docs/research/load-test-baseline/results
   scp -r 'govbenefit-loadgen:gov-benefit-helper/docs/research/load-test-baseline/results/*' docs/research/load-test-baseline/results/
   ssh govbenefit-loadgen 'rm -rf gov-benefit-helper/docs/research/load-test-baseline/results'
   ```

5. Proxmox 호스트에서 부하 생성 VM을 끈다: `qm shutdown 104`
6. 메일 발송과 공공 API 호출이 없었는지 확인한다.
   - 결과 디렉터리마다 `app-log-check.txt`에 머리줄 말고 내용이 없다.
   - `prometheus/all/collection_runs.json`과 `prometheus/all/mail_requests.json`의 값이 구간 처음과 끝에서 같거나, 결과가 비어 있다(앱을 띄운 뒤 한 번도 없었다).
   - Resend 대시보드의 Emails에 측정 시간대의 발송 기록이 없다.
7. 결과 디렉터리와 [보고서](../research/load-test-baseline.md)를 커밋해 PR로 올린다.

## 중간에 끊겼을 때

내 PC의 연결만 끊겼으면 측정은 `tmux` 안에서 계속되고 있다. `ssh govbenefit-loadgen` 뒤 `tmux new -A -s loadtest`로 다시 붙는다. `run.sh`가 실패했거나 부하 생성 VM이 꺼졌으면 5의 1~3(계정 지우기, 앱 상태 확인, API 풀기)을 먼저 한다. 부하 생성 VM에 k6 컨테이너가 남았으면 `docker ps`로 보고 `docker stop <컨테이너>`로 멈춘다.

## 다시 잴 때

성능 개선 전후를 비교할 때는 같은 스크립트·단계·판정 기준으로 잰다(결정 0037). 같은 입력으로 재려면 `INPUTS`로 기준 측정의 `inputs.json`을 넘긴다. 데이터 규모(혜택 수)가 달라졌으면 보고서에 함께 적는다.
