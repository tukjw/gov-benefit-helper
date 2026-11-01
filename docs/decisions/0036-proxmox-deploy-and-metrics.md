# 0036. Proxmox 단일 호스트 배포와 운영 지표

- 상태: 대체됨 → [0037](0037-proxmox-deploy-metrics-and-load-test.md)
- 날짜: 2026-10-30
- 출처: #92, [플랜](../plans/92-basic-metrics.md)
- 대체: [0034](0034-proxmox-deploy.md)

## 배경

0034로 운영을 Proxmox 호스트 한 대의 app VM·db VM에 올렸지만, 운영 상태는 상태 확인으로만 볼 수 있었다. `REQ-OPS-02` P0의 기본 지표(요청 지연·오류율, 수집 실행 결과)를 모으려면 앱이 지표를 내보내야 하는데, 0034는 actuator를 Tunnel이 전달하는 앱 포트(8080)에 두어 거기에 지표를 열면 `api.govbenefit.kr`로 공개된다. #92에서 actuator를 관리 포트로 옮기고, 지표 VM에 Prometheus와 Grafana를 두기로 했다. 상태 확인의 위치와 배포 스크립트가 바뀌어 0034를 대체한다. 0034의 나머지 결정은 그대로 옮겨 적는다.

## 결정

Proxmox 호스트 한 대에 app VM, db VM, 지표 VM을 두고, 각 VM에서 Docker Compose로 컨테이너를 띄운다. 앱 이미지는 릴리스 태그로 GHCR에 올리고, 로컬에서 SSH로 실행하는 `scripts/deploy.sh <태그>`로 배포하고 되돌린다. VM 안의 실행 구성은 저장소 `deploy/`에 두고, VM 생성은 문서 절차로 한다. actuator(상태 확인·지표)는 관리 포트 8081에서만 열고, 지표 VM의 Prometheus가 모아 Grafana로 본다.

- 이미지: CI가 `./gradlew bootJar`로 만든 `app.jar`를 `backend/Dockerfile`(`eclipse-temurin:21-jre-noble`, 비루트 사용자)이 담는다. `JAVA_TOOL_OPTIONS`로 힙을 컨테이너 메모리의 75%로, JVM 기본 시간대를 `Asia/Seoul`로 둔다(0002).
- 이미지 워크플로(`.github/workflows/image.yml`): 태그 `vX.Y.Z`를 push하면 `backend/build.gradle`의 `version`과 같은지 확인하고 `ghcr.io/tukjw/gov-benefit-helper:vX.Y.Z`를 올린다. 수동 실행은 `sha-<커밋 7자리>`를 올린다. 운영 배포는 `v*` 태그로만 한다. `latest`는 두지 않는다. 이미지는 공개다.
- app VM(`deploy/app/compose.yaml`): `app`, `cloudflared`, `node-exporter`. 앱 포트 8080은 VM의 `127.0.0.1`에만 열고, 외부 요청은 cloudflared가 Compose 네트워크로 전달한다. 관리 포트 8081은 `127.0.0.1`(배포 스크립트)과 내부 브리지 주소 `10.10.0.11`(Prometheus)에만 연다. 비밀값은 VM의 `app.env`·`cloudflared.env`에만 두고, Compose 치환용 `.env`에는 배포 스크립트가 적는 `APP_TAG`만 둔다.
- db VM(`deploy/db/compose.yaml`): `mysql:8.4`, `node-exporter`, `mysqld-exporter`. 로컬과 같은 문자 집합·정렬·시간대(0001·0002)를 쓰고, 물리 포트가 없는 내부 브리지(`vmbr1`)의 주소에만 연다. `mysqld-exporter`는 지표 전용 계정 `exporter`(`PROCESS`, `REPLICATION CLIENT`, `performance_schema`의 `SELECT`)로 접속한다.
- 지표 VM(`deploy/metrics/`): `prometheus`(15초 간격, 30일 보관, VM의 `127.0.0.1`에만 연다)와 `grafana`(LAN에서 연다). 데이터 소스와 대시보드(앱, VM·MySQL, 수집)는 provisioning으로 저장소에서 불러오고, 화면에서 고치지 않는다.
- 배포 스크립트: 배포할 태그 시점의 `deploy/app/compose.yaml`을 VM에 복사하고, 이미지를 받은 뒤에 태그를 바꿔 교체하고, 관리 포트의 `/actuator/health`가 `UP`이 될 때까지 기다린다. 관리 포트가 없는 이미지(v0.1.0)로 되돌릴 때를 위해 앱 포트의 상태 확인도 본다. 실패해도 자동으로 되돌리지 않는다.
- actuator: `health`와 `prometheus`만 노출하고 `SecurityConfig`의 공개 경로에 둔다. 관리 포트에도 앱의 보안 필터가 적용되기 때문이다. 앱 포트에는 actuator 경로가 없어 404다. 상태 확인의 세부 정보는 보이지 않는다.
- 요청 지연: `http.server.requests`의 히스토그램을 5ms~10s 범위로 내보내 Prometheus에서 백분위수를 계산한다.
- 수집 실행 결과 지표: 실행이 끝날 때 `CollectionMetrics`가 기록한다. `collection_runs_total{status}`, `collection_last_run_status{status}`(마지막 실행 상태면 1), `collection_last_run_finished_seconds`, `collection_last_run_duration_seconds`, `collection_last_run_records{endpoint,result}`. 마지막 실행 지표는 첫 실행이 끝난 뒤에 생기고 재시작하면 다음 실행까지 없다.
- 클라이언트 IP: Tomcat 원격 IP 처리로 `CF-Connecting-IP`를 요청자 주소(`getRemoteAddr`)로 쓴다. 신뢰하는 프록시는 기본값(사설 대역)이다.
- 프론트: Cloudflare Pages가 `main`에서 `frontend/`를 빌드해 `govbenefit.kr`에 둔다. `404.html`이 없어 없는 경로에 루트를 돌려준다(0029).
- 비회원 조건 비교 목록: Cloudflare 요청량 제한 규칙으로 경로 `/api/matches`를 IP 기준으로 제한한다. 기준 값은 [`docs/ops/deploy.md`](../ops/deploy.md)에 둔다.

## 근거

- **VM 나누기**: 한 VM에 앱과 DB를 두는 쪽이 단순하지만, 나누면 앱만 늘리는 실험을 할 수 있고 측정할 때 앱과 DB의 자원 사용을 구분할 수 있다. 지표 VM도 같은 이유로 따로 둔다. 지표 수집이 측정 대상의 자원을 쓰지 않는다. VM마다 컨테이너가 1~3개라 오케스트레이션은 쓰지 않는다.
- **VM 생성은 문서 절차**: 호스트가 한 대이고 VM을 다시 만들 일이 드물어, Terraform이나 `qm` 스크립트를 유지하는 비용이 얻는 것보다 크다. 다시 만들 때 기억에 남지 않는 것은 VM 안에서 무엇을 어떻게 띄웠는지라서, 그것만 저장소에 둔다.
- **관리 포트 분리**: Tunnel 경로가 `app:8080`뿐이라 8081은 외부에서 닿지 않는다. 막는 설정이 저장소에 있어 테스트로 확인할 수 있다. 같은 포트에서 Cloudflare 규칙으로 막으면 차단이 저장소 밖에만 있고, Prometheus가 읽으려면 8080을 내부 브리지에도 열어야 한다. 같은 포트에서 IP로 제한하면 `CF-Connecting-IP`로 바뀐 요청자 주소와 Docker NAT 때문에 판단이 불안정하다. 상태 확인도 함께 옮겨져 `api.govbenefit.kr/actuator/health`가 없어지지만, 외부 상태 확인을 쓰는 곳은 수동 확인뿐이라 공개 API로 대신한다.
- **Prometheus + Grafana를 지표 VM에**: 표준 구성이고 비용이 없으며 지표가 집 밖으로 나가지 않는다. 부하 테스트에서 같은 스택을 쓴다. Grafana Cloud 무료 플랜은 지표 VM이 없어지지만 시계열·보관 기간 제한이 있고 지표가 외부로 나간다. VictoriaMetrics는 자원을 덜 쓰지만 이 규모에서는 차이가 없다.
- **수집 결과를 Micrometer로**: 앱이 내보내는 다른 지표와 같은 경로로 모인다. 수집할 때 DB의 최신 실행을 읽는 방식은 재시작에 영향받지 않지만 수집 간격마다 DB를 조회하고 지표가 테이블 구조에 묶인다. Grafana가 DB를 직접 조회하면 Grafana에 DB 계정을 주고 지표 VM에서 db VM 3306으로 가는 경로가 생긴다. 재시작으로 비는 마지막 실행 지표는 Prometheus에 남은 값으로 본다. 실행 기록을 저장하기 전에 기록해, DB 장애로 저장에 실패한 실행도 지표에 남는다.
- **히스토그램**: 앱에서 계산한 백분위수는 인스턴스끼리 합칠 수 없어 앱을 늘리는 실험과 맞지 않는다.
- **node_exporter, mysqld_exporter**: Proxmox 내장 지표 서버는 Prometheus로 보내지 않고, `pve-exporter`는 호스트에 설치해야 해 "VM 안의 실행 구성만 저장소에 둔다"에서 벗어난다. exporter 계정 권한으로 연결 수·쿼리 수·느린 쿼리·`max_connections`가 나오는 것을 2026-10-30 로컬 MySQL 8.4와 mysqld_exporter v0.21.0으로 확인했다.
- **Grafana는 LAN, Prometheus는 VM 안**: 공유기 포트포워딩도 Tunnel도 없어 LAN에 연 Grafana는 인터넷에서 닿지 않는다. Grafana는 Compose 네트워크로 Prometheus에 붙으므로 Prometheus를 VM 밖에 열 이유가 없다.
- **대시보드 provisioning**: VM을 다시 만들어도 같은 대시보드가 뜬다. 커뮤니티 대시보드는 쓰지 않는 패널이 대부분이라 필요한 패널만 직접 만들었다.
- **지표 스택 이미지 버전 고정**: cloudflared와 달리 오래돼도 동작이 멈추지 않고, 버전이 바뀌면 대시보드와 설정이 깨질 수 있다.
- **SSH 스크립트 배포**: 집 네트워크라 GitHub Actions 러너가 들어올 수 없다. 셀프 호스트 러너는 운영할 것이 하나 늘어난다.
- **실행 구성을 태그 시점으로 복사**: 되돌릴 때 이미지와 실행 구성이 어긋나지 않는다.
- **자동으로 되돌리지 않음**: 새 이미지가 마이그레이션을 이미 적용했으면 이전 이미지가 새 스키마로 뜨지 않을 수 있다. 무엇으로 되돌릴지는 사람이 보고 정한다.
- **CI에서 jar를 만든 뒤 담기**: Dockerfile 안에서 Gradle을 돌리면 CI의 Gradle 캐시를 따로 챙겨야 한다. `bootBuildImage`는 Dockerfile이 없지만 JVM 옵션과 시간대를 확인하기 어렵다.
- **태그와 버전 대조**: 버전 반영 PR(AGENTS.md의 릴리스 절차)을 빠뜨린 태그가 다른 버전의 이미지를 만들지 않게 한다.
- **`sha-*` 이미지**: 첫 릴리스 전에 VM, Tunnel, 비밀값을 끝까지 확인할 수 있다. `workflow_dispatch`는 기본 브랜치(`develop`)에 워크플로가 있어야 실행된다.
- **`CF-Connecting-IP`**: Cloudflare가 값을 덮어써 전달하고 값이 하나다. `X-Forwarded-For`는 목록에서 몇 번째를 믿을지 정해야 한다. 앱 포트가 VM 안에서만 열려 있어 이 헤더를 보낼 수 있는 곳은 cloudflared와 VM 자신뿐이다. Tomcat이 처리하므로 IP를 쓰는 코드는 `getRemoteAddr()`만 읽으면 된다.
- **cloudflared 버전을 고정하지 않음**: Cloudflare는 1년이 지난 cloudflared를 지원하지 않는다. 배포 스크립트는 `app`만 받으므로 배포할 때 cloudflared가 바뀌지는 않는다.
- **Tunnel 경로는 대시보드에**: 원격 관리 Tunnel은 토큰 하나로 동작한다. 설정 파일 방식은 자격 증명 파일을 VM에 따로 옮겨야 한다.
- **Pages를 `main`에서 빌드**: `main`은 배포된 상태라는 규칙과 맞는다. 미리보기 배포(`*.pages.dev`)는 쿠키와 CORS가 맞지 않아(DEC-APP) 끈다. 없는 경로에 루트를 돌려주는 동작은 2026-10-28에 Cloudflare 문서(Serving Pages)로 확인했다.
- **요청량 제한을 Cloudflare에서**: 앱 안에서 제한하면 코드와 카운터 저장소가 필요하고, 요청이 앱까지 들어온 뒤에야 거절한다. 2026-10-28 Cloudflare 문서(Rate limiting rules)로 확인한 무료 플랜 조건은 규칙 1개, 기준 IP, 기간 10초, 차단 10초, 조건에 쓸 수 있는 필드는 경로뿐이다. 메서드로 나눌 수 없어 CORS 사전 요청(OPTIONS)도 같은 경로로 세어진다. CORS 설정에 사전 요청 캐시 시간이 없어 조회마다 사전 요청이 붙을 수 있으므로, 처음 기준은 실제 조회 기준 약 20회가 되게 10초에 40회로 잡았다. 측정 전이라 넉넉하게 잡은 값이다.

## 적용 위치

- `backend/Dockerfile`, `backend/.dockerignore`, `backend/build.gradle`(`spring-boot-starter-actuator`, `micrometer-registry-prometheus`, `bootJar`의 `app.jar`)
- `backend/src/main/resources/application.yaml`(`server.forward-headers-strategy`, `server.tomcat.remoteip.remote-ip-header`, `management.*`)
- `backend/src/main/java/kr/govbenefit/common/security/SecurityConfig.java`(`/actuator/health`, `/actuator/prometheus`)
- `backend/src/main/java/kr/govbenefit/collection/service/CollectionMetrics.java`, `CollectionService.java`
- `backend/src/test/java/kr/govbenefit/common/ActuatorEndpointTest.java`, `ClientIpTest.java`, `backend/src/test/java/kr/govbenefit/collection/service/CollectionServiceTest.java`, `CollectionFaultToleranceTest.java`
- `.github/workflows/image.yml`
- `deploy/app/`, `deploy/db/`, `deploy/metrics/`
- `scripts/deploy.sh`
- 절차: [`docs/ops/proxmox-vm.md`](../ops/proxmox-vm.md), [`docs/ops/deploy.md`](../ops/deploy.md), [`docs/ops/metrics.md`](../ops/metrics.md)

## 영향

- 앱에 새 환경 변수가 생기면 `deploy/app/app.env.example`과 VM의 `app.env`에 더한다.
- 클라이언트 IP가 필요한 코드는 `HttpServletRequest.getRemoteAddr()`를 쓴다. 헤더를 직접 읽지 않는다.
- actuator 엔드포인트를 더 열면 관리 포트에만 열리고, `SecurityConfig`의 공개 경로에 넣어야 토큰 없이 읽힌다. 운영 정보가 드러나는 엔드포인트는 열지 않는다.
- 지표 이름이나 태그를 바꾸면 `deploy/metrics/grafana/dashboards/*.json`의 쿼리를 함께 고친다. 새 지표를 대시보드에 넣을 때도 JSON을 커밋한다.
- 수집 실행 흐름을 바꿔도 실행이 끝날 때 `CollectionMetrics.record`가 불리게 둔다.
- 새 수집 대상(VM, exporter)은 `deploy/metrics/prometheus.yml`에 더하고, 내부 브리지 주소에만 연다.
- 운영 이미지의 JVM 옵션은 `backend/Dockerfile`의 `JAVA_TOOL_OPTIONS`에서 바꾼다.
- 마이그레이션은 직전 릴리스 이미지가 새 스키마에서도 뜨도록 쓰는 것이 좋다. 그렇지 않은 변경은 되돌리기 절차에 영향을 적는다.
- 요청량 제한의 기준 값은 부하 테스트 결과로 조정하고 `docs/ops/deploy.md`에서 고친다.
- 배포 스크립트의 앱 포트 상태 확인은 관리 포트가 없는 이미지로 되돌릴 일이 없어지면 지운다.
