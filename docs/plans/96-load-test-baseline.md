status: in-progress

# 구현 계획 — #96 부하 생성 VM을 만들고 운영 API의 기준 지연·처리량 측정

브랜치: `chore/96-load-test-baseline` (develop `3317b5d` 기준)

## 목표 및 범위

- 목표: 부하 생성 VM에서 내부 브리지로 운영 app VM의 앱에 직접 부하를 준다. 혜택 검색·상세·비회원 조건 비교 목록·로그인·혼합 시나리오의 지연·처리량·오류율과 한계 지점을 측정 기록 형식으로 남긴다.
- 포함:
  - 부하 생성 VM 생성 절차(`docs/ops/proxmox-vm.md`).
  - app VM 실행 구성에서 앱 포트를 내부 브리지 주소에도 연다(`deploy/app/compose.yaml`).
  - k6 시나리오와 실행·준비·테스트 계정·지표 내보내기 스크립트(`scripts/loadtest/`).
  - 부하 테스트 실행 절차(`docs/ops/load-test.md`): 테스트 계정 넣고 지우기, 시간대, 배포 중지, API 막기와 풀기, 끝난 뒤 정리.
  - 측정 보고서와 원본 결과(`docs/research/load-test-baseline.md`, `docs/research/load-test-baseline/results/`).
  - 결정 문서 0037(0036 대체). `DEC-OPS(배포)`에서 nginx 내부 테스트 환경을 빼고 부하 생성 VM을 더한다.
- 제외:
  - 측정으로 확인된 병목의 개선과 성능 목표 결정. 이슈에서 뺐다. 병목은 새 이슈로 만든다.
  - 비회원 조건 비교 목록의 요청량 제한 기준 값 조정. 2026-10-31에 이슈에서 뺐다. 조정할 때 이 측정의 조건 비교 목록 결과를 쓸 수 있다.
  - 메일이 나가는 경로(가입·인증번호·비밀번호 재설정)와 공공 API를 부르는 수집.
  - 로그인한 사용자의 개인 API(프로필·관심 목록·내 조건 비교). 이슈의 시나리오 목록에 없다.
  - nginx 내부 테스트 환경. 만들지 않는다.
  - 프론트(Cloudflare Pages)와 Cloudflare를 거친 구간의 부하.
  - 부하 VM의 지표를 Prometheus로 모으는 것(설계 11).

## 확인된 사실과 제약

- 앱 포트: `deploy/app/compose.yaml`이 8080을 `127.0.0.1`에만 연다. 내부 브리지(`vmbr1`)에는 관리 포트 8081(`10.10.0.11`)만 열려 있어, 지금은 다른 VM이 앱 API에 닿을 수 없다.
- 배포 스크립트(`scripts/deploy.sh`)는 배포하는 태그 시점의 `deploy/app/compose.yaml`을 VM에 복사하고, 서비스 이름 없이 `docker compose up -d`를 실행한다. 운영은 지금 `v0.1.1`이다. compose 변경은 그것이 들어간 릴리스를 배포해야 반영된다.
- 서비스 이름 없이 `docker compose up -d`를 실행하면 멈춰 둔 `cloudflared`도 다시 뜬다. `cloudflared`는 `app`에 `depends_on`이 있지만, `docker compose up -d app`은 `cloudflared`를 띄우지 않는다.
- 요청자 IP: `server.tomcat.remoteip.remote-ip-header: CF-Connecting-IP`이고 신뢰 프록시는 Spring Boot 기본값(사설 대역)이다. 사설 대역에서 온 요청은 `CF-Connecting-IP` 값이 요청자 주소(`getRemoteAddr`)가 된다. 다른 VM이 게시 포트로 보낸 요청은 출발 주소가 그 VM 주소(`10.10.0.x`)이거나 Docker 게이트웨이 주소인데, 어느 쪽이든 사설 대역이다.
- 로그인 시도 제한(결정 0035, `LoginAttemptLimiter`): `reserve`가 비밀번호 비교 전에 시도를 실패로 기록하고, 비밀번호가 맞으면 `succeeded`가 되돌린다. 그래서 성공하는 로그인도 처리되는 동안은 기록에 남는다. 같은 IP에서 처리 중인 시도가 30개, 같은 (이메일+IP)에서 5개에 차면 429다. 제한기 전체가 `synchronized`다.
- 비밀번호는 `{bcrypt}` 접두사를 붙인 BCrypt 해시다(결정 0013, `PasswordEncoderFactories` 기본, 강도 10). 이메일은 소문자로 저장한다(V4). 가입은 이메일 인증을 거쳐 메일이 나간다(결정 0019).
- 계정 삭제: `refresh_token`·`password_reset_token`·`user_profile`(그 아래 `user_profile_attribute`)·`bookmark`가 `user_account`를 `ON DELETE CASCADE`로 참조한다. `email_verification`에는 FK가 없지만 가입을 거치지 않으면 행이 생기지 않는다. 로그인할 때마다 `refresh_token` 행이 하나 생긴다.
- 메일은 `POST /api/auth/email-verification`, `POST /api/auth/password-reset`에서만 나간다. 발송 성공은 앱 로그에 남지 않고, 실패만 `AccountMailListener`가 남긴다.
- 공공 API는 수집에서만 부른다. 예약 수집은 매일 04:00이며, 실행하면 `수집 시작 runId=` 로그와 `collection_runs_total` 지표가 남는다.
- 대상 API
  - 검색 `GET /api/benefits`: `keyword`(100자, 단어 10개까지), `field`, `agencyType`, `agencySidoCode`, `agencySigunguCode`, `page`, `size`(1~100, 기본 20).
  - 상세 `GET /api/benefits/{serviceId}`.
  - 비회원 조건 비교 목록 `POST /api/matches?regionScope=&page=&size=`, 본문은 `ProfileRequest`(`birthDate`, `gender`, `sidoCode`, `sigunguCode`, `lifeAttributes`, `householdAttributes`). 선택지는 `GET /api/profile/options`, 지역 코드는 `GET /api/benefits/regions`에서 얻는다.
  - 로그인 `POST /api/auth/login`.
- 프론트는 비회원 조건 비교 목록을 화면에 들어올 때와 지역 범위·쪽 번호가 바뀔 때마다 부른다(`frontend/src/pages/match/matchRoute.ts`). 브라우저는 그때마다 CORS 사전 요청을 붙일 수 있다(결정 0036). 부하 도구는 사전 요청을 보내지 않는다.
- Cloudflare 요청량 제한: 경로 `/api/matches`, IP별 10초에 40회, 넘으면 10초 차단(`docs/ops/deploy.md` 7). 무료 플랜이라 조건으로 쓸 수 있는 필드는 경로뿐이다.
- 지표: Prometheus는 15초 간격으로 수집하고 지표 VM의 `127.0.0.1:9090`에만 열려 있어 `ssh -L`로 읽는다. 앱 지연 히스토그램은 5ms~10s 범위다. 관리 포트 요청도 `http_server_requests`에 `uri="/actuator/prometheus"`로 잡힌다.
- 자원: app VM 4 vCPU(C1·C2)·4GB(컨테이너 한도 3g, 힙 75%), db VM 2 vCPU(C3)·4GB(버퍼 풀 1G). Hikari 풀과 Tomcat 스레드는 기본값(10, 200)이다. 물리 코어 C5~C7이 비어 있다.
- 측정 기록 형식은 [`docs/product/development.md`](../product/development.md#측정-기록-형식)에 있다. 기존 조사는 스크립트를 `scripts/research/gov24/`에, 결과를 `docs/research/gov24-api-data/results/`에 두었다.
- 로컬 도구: macOS의 `htpasswd`가 BCrypt(`-B`, `$2y$`)를 만든다. Spring Security의 BCrypt 비교는 `$2a`·`$2b`·`$2y`를 모두 받는다. 로컬 k6는 v2.3.0이다.

## 설계 결정

1. **부하 생성 도구는 k6다.** → 결정 문서 0037
   - 도착률 실행기(`constant-arrival-rate`)는 응답이 느려져도 정해진 요청 속도를 유지하므로, 대기 시간까지 지연에 들어간다. `http_req_duration`에는 기본으로 실패 요청도 들어간다.
   - 부하 VM에서 `grafana/k6` 컨테이너로 실행하고 이미지 버전을 고정한다(구현할 때의 최신 안정 버전). 다른 VM처럼 Docker만 설치하면 되고, 버전이 스크립트에 남는다.
   - 대안: Gatling(Java DSL). 백엔드와 언어가 같지만 부하 VM에 JVM이 필요하고, 생성기 자체도 워밍업·GC 영향을 받는다. Gradle 프로젝트를 따로 둬야 한다.
   - 대안: JMeter는 XML 시나리오와 스레드 모델, Locust는 3코어에서 생성기가 먼저 병목이 될 수 있다는 문제가 있다. vegeta는 계정을 바꿔 가며 보내는 시나리오나 혼합 비율을 표현하기 번거롭다.
2. **부하 생성 VM을 따로 만든다.** → 결정 문서 0037
   - VMID 104 `gbh-loadgen`, 6 vCPU(물리 코어 C5~C7 통째), 4GB(`--balloon 0`), 32GB. `vmbr0`(LAN, SSH·이미지 받기)와 `vmbr1` `10.10.0.14`에 붙인다.
   - `--onboot 0`으로 평소에는 꺼 두고 측정할 때만 켠다. 측정하지 않을 때 앱 포트에 닿는 VM이 하나 줄어든다.
3. **앱 포트 8080을 내부 브리지 주소(`10.10.0.11`)에도 상시 연다.** → 결정 문서 0037
   - compose `ports`에 `10.10.0.11:8080:8080`을 더한다. 내부 브리지에는 물리 포트가 없으므로 LAN·인터넷에서는 여전히 닿지 않는다.
   - 결과: 내부 브리지의 VM(db·지표·부하)이 앱 포트에 닿는다. 이 VM들은 사설 대역이라 `CF-Connecting-IP`로 요청자 주소를 정할 수도 있다. 결정 0037에 한계로 적는다.
   - 대안: 측정할 때만 여는 override 파일. 열고 닫을 때마다 app 컨테이너를 다시 만들어야 하고, `deploy.sh`가 기본 compose로 덮어써 상태가 어긋나기 쉽다.
   - 대안: SSH 터널. 암호화와 연결 하나로 묶는 처리가 지연·처리량을 오염시킨다.
   - 대안: app VM 안에서 부하 생성. 앱과 코어를 나눠 쓰므로 이슈에서 배제했다.
4. **첫 측정은 이 브랜치의 app 실행 구성을 손으로 반영한 뒤 한다.**
   - 측정 결과가 이 이슈의 PR에 들어가야 한다. 포트 변경을 먼저 릴리스하면 PR을 둘로 나누고 릴리스를 하나 더 해야 한다. 측정 동안은 API를 막으므로(설계 6), app 컨테이너를 다시 만들어도 사용자가 받는 영향이 늘지 않는다.
   - 절차(이 플랜에만 둔다): API를 막은 뒤 이 브랜치의 `deploy/app/compose.yaml`을 app VM에 복사하고 `docker compose up -d app`을 실행한다. `.env`의 `APP_TAG`(`v0.1.1`)는 그대로 둔다. 측정 기록에 이미지 태그와 실행 구성의 커밋을 함께 적는다.
   - 이 PR이 들어간 다음 릴리스를 `deploy.sh`로 배포하면, 같은 실행 구성이 태그 시점으로 복사돼 상태가 맞춰진다. 그 전에 `v0.1.1`로 다시 배포하면 포트가 닫힐 뿐이다.
   - 대안: PR을 둘로 나눈다(구성 → 릴리스 → 측정 → 결과). 한 이슈의 결과가 두 PR에 나뉘고, 릴리스가 하나 더 필요하다.
5. **모든 요청에 가상 사용자별 `CF-Connecting-IP`를 붙인다.** → 결정 문서 0037
   - 값은 벤치마크용 대역 `198.18.0.0/15`에서 가상 사용자 번호로 정한다(`198.18.<번호/256>.<번호%256>`). 신뢰 프록시 대역 밖이라 그대로 요청자 주소가 된다.
   - 헤더가 없으면 모든 요청이 부하 VM 주소 하나에서 온 것으로 보인다. 그러면 로그인 단계에서 처리 중인 시도가 30개를 넘는 순간부터 429가 섞이고, 한계 지점이 CPU가 아니라 제한 수치로 잡힌다.
   - 로그인 계정도 가상 사용자 번호로 정해, 같은 (이메일+IP)에서 동시에 처리되는 시도가 하나를 넘지 않게 한다.
   - 대안: 측정하는 동안 `auth.login-attempt.*` 값을 완화한다. 운영 설정을 바꾸고 재시작을 두 번 해야 하며, 측정 대상이 운영 설정과 달라진다.
6. **측정하는 동안 app VM의 `cloudflared`를 멈춰 API를 막고, 시간대도 고른다.** → 결정 문서 0037
   - 막을 때는 `docker compose stop cloudflared`, 풀 때는 `docker compose start cloudflared`를 실행한다. Tunnel 연결이 끊기면 Cloudflare가 오류 응답을 돌려준다. Cloudflare 대시보드를 바꾸지 않고, 측정과 같은 VM에서 명령 하나로 되돌린다.
   - 실제 요청이 측정에 섞이지 않으므로, 앱이 받은 요청 수와 k6가 보낸 요청 수를 맞춰 볼 수 있다. 한계 단계에서 실제 사용자가 느린 응답이나 오류를 받지도 않는다. 막는 동안 프론트(Pages)는 열려 있고 API 요청만 실패한다.
   - 시간대: 04:00 예약 수집과 겹치지 않게 03:30~05:00을 피한다. 그 밖에서 Grafana로 최근 7일 중 요청이 적은 시간을 고른다. 막기 직전 1시간의 실제 요청량을 기록한다.
   - 측정하는 동안 배포하지 않고, app VM에서 서비스 이름 없이 `docker compose up -d`를 실행하지 않는다. 둘 다 `cloudflared`를 다시 띄운다.
   - 대안: Cloudflare WAF 규칙으로 막는다. 대시보드에서 규칙을 만들고 지우는 절차가 늘어나고, 측정 절차와 실행 위치가 갈린다.
7. **요청 속도 단계마다 k6를 한 번씩 실행한다.** → 결정 문서 0037(측정 방식)
   - 단계 하나는 `constant-arrival-rate`로 3분이다. 첫 단계 앞에 낮은 속도로 3분 워밍업을 두고, 워밍업 결과도 남기되 기준값에서는 뺀다. 3분이면 Prometheus 수집이 12번 들어간다.
   - 먼저 요청 속도를 두 배씩 올리는 거친 단계(예: 5→10→20→40→80→160)로 한계 단계(설계 8)를 찾는다. 그다음 마지막 정상 단계와 한계 단계 사이를 25% 간격으로 다시 잰다. 한계 단계를 넘어서는 더 올리지 않는다.
   - 단계마다 k6 요약을 `handleSummary`로 JSON에 쓰고, 시작·끝 시각을 남긴다. 요약과 서버 지표 구간이 단계와 1:1로 맞는다.
   - 가상 사용자가 모자라 정해진 속도로 보내지 못한 요청은 `dropped_iterations`로 남는다. 이것도 한계 신호로 본다.
   - 대안: 한 번의 실행에 모든 단계를 넣는다(`ramping-arrival-rate`). 단계별 백분위수를 요약에서 나누기 어렵다.
8. **한계 판정 기준**: 다음 중 하나가 처음 나타난 단계의 요청 속도를 "한계가 보이기 시작하는 요청 속도"로 적는다. → 결정 문서 0037(측정 방식)
   - 처리량(응답 수/초)이 목표 속도의 95% 미만이거나 `dropped_iterations`가 1 이상이다.
   - 오류율(2xx가 아닌 응답과 연결 실패)이 1%를 넘는다.
   - p95가 가장 낮은 단계의 p95의 2배를 넘고, 늘어난 폭이 50ms 이상이다. 폭 조건은 수 ms짜리 경로가 흔들림만으로 걸리지 않게 하려는 것이다.
   - 성능 목표가 아니라, 시나리오끼리와 나중의 개선 측정을 같은 잣대로 비교하기 위한 기준이다.
9. **시나리오와 입력 데이터**
   - 모든 시나리오는 `http://10.10.0.11:8080`으로 보내고, 2xx를 성공으로 본다. 요청마다 `name` 태그(`search`, `detail`, `guest-match`, `login`)를 붙여 혼합 시나리오에서도 경로별로 나눠 본다.
   - 검색: 검색어 목록(분야·대상 단어 30개 안팎과 빈 검색어) 중 하나, 쪽 1~5, 크기 20으로 보낸다. 요청의 30%에는 운영 기관 시도 필터를 더한다.
   - 상세: 준비 단계에서 검색 API로 받은 서비스 ID 1,000개 중 무작위로 고른다.
   - 비회원 조건 비교 목록: 준비 단계에서 `/api/profile/options`와 `/api/benefits/regions`로 만든 프로필 200개 중 하나를 쓴다. 지역 범위는 기본값, 쪽은 1~3이다.
   - 로그인: 가상 사용자 번호에 해당하는 테스트 계정(설계 10)으로 성공하는 로그인만 보낸다.
   - 혼합: 요청 수 기준으로 검색 40%, 상세 35%, 비회원 조건 비교 목록 20%, 로그인 5%다. 실사용 표본이 아직 적어, 공개 화면 위주로 잡은 가정 비율이다. 측정 직전 최근 7일 실사용의 경로별 비율도 함께 기록한다.
   - 준비 데이터(`service-ids.json`, `profiles.json`)는 측정을 시작할 때 만들고 결과 디렉터리에 함께 남긴다. 다시 측정할 때 같은 파일을 넘기면 같은 입력으로 잰다.
10. **테스트 계정은 SQL로 직접 넣고 지운다.**
    - 이메일은 `loadtest-0001@loadtest.invalid`부터 `loadtest-1000@loadtest.invalid`까지다. `.invalid`는 예약된 최상위 도메인이라 실제 주소와 겹치지 않는다. 소문자로 넣는다.
    - 비밀번호는 측정할 때마다 무작위로 만들고, 저장소와 문서에 적지 않는다. 내 PC의 저장소 밖 파일(권한 600)에 두고, 정리할 때 지운다. 해시는 `htpasswd -nbBC 10`으로 만들어 `{bcrypt}`를 앞에 붙인다. 앱과 같은 강도(10)여야 로그인 비용이 실제 계정과 같다. 모든 계정이 같은 해시를 쓴다.
    - SQL은 db VM의 `mysql` 컨테이너 안에서 컨테이너 환경 변수(`MYSQL_USER`, `MYSQL_PASSWORD`, `MYSQL_DATABASE`)로 접속해 실행한다. 내 PC에 DB 비밀번호가 필요 없다.
    - 지울 때는 `user_account`에서 테스트 이메일 패턴만 지운다. 나머지 데이터는 CASCADE로 함께 지워진다. 지운 뒤 패턴에 맞는 계정 수가 0인지 센다.
    - 대안: 앱에 시드용 프로필을 둔다. 운영 이미지에 테스트용 코드가 들어가고 릴리스가 필요하다.
    - 대안: 가입 API를 쓴다. 인증 메일이 나간다.
11. **결과 저장 위치와 서버 지표**
    - 스크립트는 `scripts/loadtest/`(기존 `scripts/research/gov24/`와 같은 층), 원본 결과는 `docs/research/load-test-baseline/results/<시각>-<시나리오>/`에 둔다. 보고서는 `docs/research/load-test-baseline.md`(측정 기록 형식), 절차는 `docs/ops/load-test.md`다.
    - 서버 지표는 단계 구간마다 Prometheus `query_range`(15초 간격) 결과를 JSON으로 저장한다. 쿼리 목록은 `scripts/loadtest/queries.tsv`에 둔다. Grafana 스크린샷은 보고서에 필요한 것만 보조로 넣는다.
    - 부하 VM의 CPU는 단계마다 `vmstat`으로 남겨, 생성기가 병목이 아니었음을 보인다. Prometheus 수집 대상에 더하면 평소 꺼 둔 VM이 `DOWN`으로 남아, "수집 대상이 모두 UP"이라는 확인(`docs/ops/metrics.md`)과 어긋난다.
    - k6의 요청별 원본 출력은 커서 저장소에 두지 않고, 단계 요약만 둔다.
12. **결정 문서는 0036을 대체하는 0037 하나로 쓴다.**
    - 0036의 "앱 포트 8080은 VM의 `127.0.0.1`에만 연다"가 바뀐다. 0037은 0036의 유효한 내용에 다음을 더한다: 앱 포트의 내부 브리지 개방, 부하 생성 VM, 부하 테스트 방식(운영 VM에 직접, 측정 중 Tunnel 중지, 요청자 IP 헤더, 단계와 한계 판정). 0036은 상태 줄만 `대체됨 → 0037`로 바꾼다.
    - #92처럼 코드·설정·운영 문서·제품 문서에서 0036을 가리키던 곳을 0037로 바꾼다. 채택된 다른 결정 문서와 지난 플랜의 본문은 고치지 않는다.

## 단계별 구현 계획

1. **플랜 상태**: 착수할 때 이 파일의 `status`를 `in-progress`로 바꾼다.
2. **app 실행 구성** `deploy/app/compose.yaml`(설계 3): `app.ports`에 `10.10.0.11:8080:8080`을 더한다. 주석에 부하 VM이 읽는다는 것과 결정 0037을 적고, 기존 8080 주석을 고친다.
3. **부하 테스트 스크립트** `scripts/loadtest/`
   - `k6/lib.js`: 기본 주소(`BASE_URL`, 기본값 `http://10.10.0.11:8080`), `clientIp(vu)`(설계 5), 환경 변수 `RATE`·`DURATION`·`MAX_VUS`로 만드는 `constant-arrival-rate` 옵션, 요약 JSON을 쓰는 `handleSummary`.
   - `k6/search.js`, `k6/detail.js`, `k6/guest-match.js`, `k6/login.js`, `k6/mixed.js`: 설계 9의 시나리오. 입력 파일은 `SharedArray`로 읽는다.
   - `k6/data/keywords.json`: 검색어 목록.
   - `prepare.py`: 부하 VM에서 실행한다. 검색 API로 서비스 ID 1,000개를 받고, 선택지·지역 API로 프로필 200개를 만들어 JSON으로 쓴다. 검색 응답의 전체 건수(데이터 규모)도 남긴다. 표준 라이브러리만 쓴다.
   - `run.sh <시나리오> <요청 속도…>`: 내 PC에서 실행한다.
     - k6 스크립트를 부하 VM에 복사하고, 준비 데이터가 없으면 `prepare.py`를 실행한다.
     - 워밍업 뒤 요청 속도마다 k6를 실행하면서 `vmstat`을 함께 남기고, 단계별 시작·끝 시각을 기록한다.
     - 결과를 결과 디렉터리로 가져오고 `export-metrics.sh`를 부른다.
     - 실행 정보(`meta.json`)를 함께 쓴다: app VM의 `APP_TAG`, app VM `compose.yaml`의 해시와 로컬 커밋, VM 사양, 데이터 규모, 단계·지속 시간·워밍업, k6 이미지 버전.
   - `export-metrics.sh <결과 디렉터리>`: `meta.json`의 단계 구간마다 `queries.tsv`의 쿼리를 `ssh -L`로 연 Prometheus의 `query_range`에 보내고, `prometheus/<단계>/<이름>.json`으로 저장한다.
   - `queries.tsv`(이름, PromQL): 다음 쿼리를 둔다.
     - 앱: 경로별 요청 수·상태별 비율·서버 쪽 p50/p95/p99, JVM 힙·GC 일시 정지·스레드, `process_cpu_usage`, Hikari 활성·대기 연결과 획득 시간.
     - VM: VM별 CPU·메모리·디스크 I/O.
     - MySQL: 초당 쿼리, 연결 수, 실행 중 스레드, 느린 쿼리.
     - 확인용: `collection_runs_total` 증가량, 메일 경로(`/api/auth/email-verification`, `/api/auth/password-reset`)의 요청 수.
   - `accounts.sh seed|cleanup|count`, `sql/seed-accounts.sql`, `sql/cleanup-accounts.sql`, `sql/count-accounts.sql`(설계 10).
     - `seed`는 비밀번호와 해시를 만들고 계정 1,000개를 넣는다. 재귀 CTE를 쓰므로 `cte_max_recursion_depth`를 세션에서 올린다.
     - `cleanup`은 계정을 지우고 비밀번호 파일도 지운다.
4. **운영 문서**
   - `docs/ops/proxmox-vm.md`(설계 2):
     - 구성 그림과 사양 표에 부하 VM을 넣고, "C5~C7은 부하 테스트에 쓴다"를 부하 VM으로 고친다.
     - "4. VM 만들기"에 VMID 104(`--affinity` 예: `5,6,7,13,14,15`, `--onboot 0`), "5. VM 안 준비"에 `/opt/gov-benefit/loadtest`, "6. SSH 설정"에 `govbenefit-loadgen`을 더한다.
     - "7. 확인": 부하 VM에서 `curl http://10.10.0.11:8080/api/profile/options`가 200이다(이 변경이 들어간 실행 구성 뒤). LAN의 다른 기기에서는 app VM의 LAN 주소로 8080에 닿지 않는다(기존).
   - `docs/ops/load-test.md`(새 문서):
     - 구성 요약.
     - 준비: 시간대 고르기, 배포 중지, 직전 1시간 실사용 요청량 기록, 부하 VM 켜기(`qm start 104`).
     - 테스트 계정 넣기, API 막기와 외부 확인(200이 아님).
     - 시나리오별 실행: 거친 단계 → 한계 판정 → 세밀 단계.
     - 끝난 뒤 순서: 테스트 계정 지우기와 수 0 확인 → API 풀기와 외부 200 확인 → 부하 VM 끄기 → 메일·수집이 없었다는 확인(지표 파일, 측정 구간의 앱 로그, Resend 대시보드의 발송 기록). 계정을 먼저 지워 외부에서 테스트 계정으로 로그인할 수 있는 시간을 없앤다.
     - 중간에 끊겼을 때: 계정 지우기와 API 풀기를 먼저 한다.
     - 결과 커밋과 보고서 갱신.
   - `docs/ops/deploy.md`: 구성 요약에 "앱 포트는 내부 브리지에도 열려 부하 VM이 닿는다"를 넣고, "그 밖의 작업"에 부하 테스트 절차 링크를 단다.
   - `docs/README.md`: 문서 안내 표에 부하 테스트 절차를 더한다.
5. **결정과 제품 문서**
   - `docs/decisions/0037-proxmox-deploy-metrics-and-load-test.md`(설계 12). 0036 상태 줄과 `docs/decisions/README.md` 목록을 고친다. 0036 참조를 0037로 바꾼다. 대상은 `backend/`·`deploy/`·`scripts/`·`.github/workflows/image.yml`의 주석과 `docs/ops/`·`docs/product/` 문서다.
   - `docs/product/development.md`
     - `DEC-OPS(배포)` 행: 프론트의 "내부 테스트용은 Proxmox VM의 nginx에 둔다"와 "내부 테스트 환경은 부하 테스트와 함께 만든다"를 뺀다. 부하 생성 VM과 부하 테스트 방식, 그 이유와 한계, 0037 링크를 더한다.
     - 변경 기록에 "DEC-OPS 중 부하 테스트 환경을 2026-11-xx에 정하고 내부 테스트 환경을 만들지 않기로 했다. 기록은 #96에서 했다."를 더한다.
     - "아직 결정할 사항"의 `DEC-OPS` 행에서 "성능 목표는 부하 테스트로 기준값을 잰 뒤 정한다"에 보고서 링크를 단다.
6. **로컬 확인**: 아래 검증 명령을 실행한다.
7. **[사용자] 부하 VM 만들기**: `docs/ops/proxmox-vm.md`대로 만든다.
8. **[사용자] 측정**: `docs/ops/load-test.md`대로 측정한다. 단, API를 막은 직후 설계 4의 손 반영을 한다. 시나리오는 검색 → 상세 → 비회원 조건 비교 목록 → 로그인 → 혼합 순서다. 한 시나리오는 30분 안팎이라 전체로 API를 3시간 가까이 막는다. 시나리오 단위로 나눠 여러 번 막고 풀어도 된다. 결과 디렉터리는 작업 트리에 남긴다.
9. **보고서** `docs/research/load-test-baseline.md`
   - 측정 기록 형식의 1~4·6을 쓴다. 5(대안과 개선 후 결과)는 기준 측정이라 해당 없음으로 둔다.
   - 시나리오별 단계 표: 목표 속도, 처리량, p50/p95/p99(실패 포함), 오류율, `dropped_iterations`, app·db CPU, Hikari 대기, MySQL 초당 쿼리, 부하 VM CPU.
   - 한계 지점과 그 근거(어느 자원이 먼저 찼는지).
   - 메일·공공 API 호출이 없었다는 근거와 계정 정리 근거.
10. **검증과 PR**: 검증 명령이 통과하면 마지막 커밋에서 `status: done`으로 바꾸고 PR을 올린다. 측정이 PR 전에 끝나므로 PR 본문은 `Closes #96`이다. 머지 뒤 다음 릴리스를 배포하면 실행 구성이 태그 시점으로 맞춰진다는 것(설계 4)을 PR 본문에 적는다.

## 제안 (이 이슈에서 하지 않음)

- **CORS 사전 요청 캐시**: CORS 설정에 `Access-Control-Max-Age`를 두면 브라우저의 사전 요청이 줄어, 요청량 제한 규칙이 실제 조회 수에 가깝게 센다. CORS 설정 변경이라 요청량 제한 기준 값을 조정할 때 함께 검토한다.

## 완료 조건 매핑

| 이슈 완료 조건 | 충족 방법 |
| --- | --- |
| 부하 생성 VM의 요청이 운영 app VM의 앱에 직접 닿고, Cloudflare 요청량 제한에 걸리지 않는다. | 설계 3·4·6. 측정 중 `cloudflared`를 멈춰 Cloudflare를 거치는 경로가 없다. 앱의 `/api/matches` 요청 수가 k6가 보낸 수와 맞고, 10초에 40회를 넘는 단계에서도 429가 0건인 것을 결과로 보인다 |
| LAN과 인터넷에서는 여전히 앱 포트에 직접 접근할 수 없다. | 설계 3. 8080은 `127.0.0.1`과 물리 포트 없는 `vmbr1` 주소에만 연다. `docs/ops/proxmox-vm.md` 7의 확인(LAN의 다른 기기에서 app VM의 LAN 주소 8080에 닿지 않음)을 측정 전에 한다 |
| 시나리오마다 요청 속도를 단계적으로 올리며 다음을 기록한다: p50·p95·p99 지연(실패 요청 포함), 처리량, 오류율, 한계가 보이기 시작하는 요청 속도. | 설계 7·8·9, 단계 3·9. 단계별 k6 요약과 보고서의 시나리오별 표 |
| 측정마다 이미지 태그, VM 사양, 데이터 규모, 요청 분포·지속 시간·워밍업을 함께 기록한다. 같은 시간대의 app·db VM CPU, JVM, DB 커넥션 풀, MySQL 지표도 근거로 남긴다. | 설계 11, 단계 3의 `meta.json`과 `export-metrics.sh`·`queries.tsv` |
| 문서 절차대로 같은 시나리오를 다시 실행할 수 있다. | 단계 4의 `docs/ops/load-test.md`와 `scripts/loadtest/`. 준비 데이터를 결과에 남겨 같은 입력으로 다시 잰다 |
| 측정 중에 메일 발송과 공공 API 호출이 일어나지 않는다. | 시나리오에 메일 경로와 수집이 없고(설계 9), 03:30~05:00을 피한다(설계 6). 측정 구간의 메일 경로 요청 수와 `collection_runs_total` 증가량이 0이고, 앱 로그에 `수집 시작`이 없으며, Resend 발송 기록이 없다 |
| 측정이 끝나면 운영 DB에 테스트 계정과 그 계정의 데이터가 남지 않는다. | 설계 10. `accounts.sh cleanup` 뒤 `count`가 0이고, 관련 데이터는 CASCADE로 지워진다 |

## 검증 명령

```bash
bash -n scripts/loadtest/run.sh scripts/loadtest/export-metrics.sh scripts/loadtest/accounts.sh
python3 -I -m py_compile scripts/loadtest/prepare.py
APP_TAG=v0.0.0 docker compose -f deploy/app/compose.yaml config -q --no-env-resolution
for f in scripts/loadtest/k6/*.js; do
  docker run --rm -v "$PWD/scripts/loadtest/k6:/k6:ro" grafana/k6:<고정 버전> inspect "/k6/$(basename "$f")"
done
cd backend && ./gradlew test   # 0036 → 0037 주석 변경
```

로컬 앱(`SPRING_PROFILES_ACTIVE=local ./gradlew bootRun`)과 로컬 MySQL로 한 번씩 돌려 본다.

- 로컬 MySQL에 테스트 계정 SQL을 넣는다(`docker compose -f docker-compose.local.yml exec -T mysql ...`).
- `prepare.py`와 각 시나리오를 `BASE_URL=http://host.docker.internal:8080`, 낮은 속도로 30초 돌린다. 오류율이 0인지, 로그인에서 429가 없는지 본다.
- 정리 SQL을 실행한 뒤 계정 수가 0이고 `refresh_token`이 남지 않는지 본다.
- 로컬 DB에 혜택이 없으면 수집을 한 번 돌린다.

## 결정 필요

없음. 1차 검토에서 권장안(k6, 앱 포트 상시 내부 브리지 개방, 가상 사용자별 요청자 IP 헤더, SQL 테스트 계정, Prometheus 원본 저장, 결정 0037로 0036 대체)으로 정했다. 시간대를 고르는 것에 더해 측정 중에는 `cloudflared`를 멈춰 API를 막기로 했다. 요청량 제한 기준 값 조정은 이슈에서 뺐다(2026-10-31).
