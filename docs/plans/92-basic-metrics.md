status: done

# 구현 계획 — #92 운영 환경의 기본 지표를 수집하고 대시보드로 확인

브랜치: `chore/92-basic-metrics` (develop `449fd63` 기준)

## 목표 및 범위

- 목표: 운영 app의 요청 지연·오류율·JVM·DB 커넥션 풀 지표와 수집 실행 결과, app·db VM과 MySQL 지표를 지표 VM의 Prometheus로 모아 Grafana 대시보드로 본다. 앱 지표와 Grafana·Prometheus는 인터넷에서 닿지 않는다.
- 포함:
  - 앱: Prometheus 형식 지표 노출, 관리 포트 분리, 요청 지연 히스토그램, 수집 실행 결과 지표.
  - app VM·db VM 실행 구성에 `node_exporter`, db VM에 `mysqld_exporter` 추가.
  - 지표 VM 실행 구성(`deploy/metrics/`): Prometheus, Grafana, 데이터 소스·대시보드 provisioning.
  - 배포 스크립트의 상태 확인 주소 변경.
  - 지표 VM 생성 절차(`docs/ops/proxmox-vm.md`)와 지표 스택 운영 절차(`docs/ops/metrics.md`), 배포 문서 갱신.
  - 결정 문서 0036(0034 대체), `DEC-OPS(관측)` 기록, `DEC-OPS` 남은 항목·O3·`REQ-OPS-02` 갱신.
- 제외:
  - 부하 테스트 환경과 내부 테스트 환경. 이슈에서 뺐다.
  - 경보(Alertmanager, Grafana 경보)와 연속 수집 실패 경보. `REQ-OPS-02` P1이다.
  - 로그 수집(Loki 등), 기능별 지표(알림·캐시). `REQ-OPS-02` P1이다.
  - Proxmox 호스트 자체의 지표(`pve-exporter`), 컨테이너별 지표(cAdvisor). 완료 조건은 VM 단위다.
  - 외부에서 Grafana에 접속하는 경로(Tailscale, Cloudflare Access).

## 확인된 사실과 제약

- 앱 설정(`application.yaml`)
  - actuator는 `health`만 노출하고, `SecurityConfig.PUBLIC_PATHS`에 `/actuator/health`가 있다(결정 0034). 관리 포트는 따로 없어 앱 포트 8080에서 열린다.
  - Prometheus 레지스트리 의존성이 없다. `spring-boot-starter-actuator`만 있다(Spring Boot 4.1.1).
  - DB 커넥션 풀은 HikariCP(기본)다. actuator가 있으면 `hikaricp_*`, `jvm_*`, `http_server_requests_*`를 자동으로 만든다.
- 외부 노출: cloudflared가 `api.govbenefit.kr` → `http://app:8080`으로 전달한다(Tunnel 경로는 Cloudflare 대시보드). 8080에 있는 경로는 모두 외부에 열린다.
- 상태 확인을 쓰는 곳
  - `scripts/deploy.sh`가 app VM 안에서 `http://127.0.0.1:8080/actuator/health`의 `UP`을 기다린다. 스크립트는 현재 작업 트리의 것이 돌고, 실행 구성(`deploy/app/compose.yaml`)만 배포하는 태그 시점으로 복사한다.
  - `docs/ops/deploy.md`의 "5. 확인 배포"가 `https://api.govbenefit.kr/actuator/health`로 Tunnel까지 확인한다.
  - 되돌리기 확인(#87 완료 조건 3)은 다음 릴리스에서 `v0.1.0`으로 되돌려 한다. `v0.1.0` 이미지는 상태 확인을 8080에만 연다.
- 배포 스크립트는 app VM의 `.env`를 `APP_TAG=<태그>` 한 줄로 덮어쓴다. app VM의 Compose 치환 값은 `APP_TAG`밖에 둘 수 없다.
- 수집
  - `CollectionService.collectAll()`이 실행 하나를 끝까지 돌리고 `CollectionRun.finish(status, …)`로 상태(`SUCCEEDED`·`PARTIAL`·`FAILED`)를 정한다. 경로별 처리 건수(`pageCount`, `receivedCount`, `createdCount`, `changedCount`, `unchangedCount`, `quarantinedCount`, `unlinkedCount`)는 `CollectionRunStep`에 있다. 경로는 `SourceEndpoint` 세 개다.
  - 인증키가 없으면 `IllegalStateException`을 던지고 실행 기록을 만들지 않는다. 실행 기록을 처음 저장하지 못하면 예외가 그대로 나간다. 그 밖의 예외는 `collectEndpoints`가 잡아 `FAILED`로 끝낸다.
  - 마지막 `runRepository.save(run)`이 DB 오류로 실패하면 예외가 스케줄러 밖으로 나간다.
  - 예약 수집은 하루 한 번(04:00)이다.
- 네트워크(`docs/ops/proxmox-vm.md`): `vmbr1` `10.10.0.0/24`에 app `.11`, db `.12`. 물리 코어 C4~C7은 부하 테스트와 지표 수집 VM 몫으로 비워 두었다. 공유기는 포트포워딩을 하지 않는다(외부 노출은 Tunnel뿐).
- 테스트
  - `HealthEndpointTest`는 MockMvc로 `/actuator/health`를 확인한다. `ClientIpTest`는 실제 Tomcat(`RANDOM_PORT`)에 `/actuator/health`를 보내 200을 기대한다.
  - Spring Boot 테스트는 지표 내보내기를 기본으로 끄고 메모리 레지스트리만 둔다. Prometheus 출력까지 보려면 테스트에서 따로 켜야 한다(어노테이션 이름은 구현할 때 Boot 4 기준으로 확인).
    - (구현 중 확인) Boot 4에서는 내보내기를 끄는 설정이 `spring-boot-micrometer-metrics-test` 모듈에 있고 이 저장소에는 그 모듈이 없어, 테스트에서도 `/actuator/prometheus`가 그대로 나온다. 어노테이션을 더하지 않았다.
  - 테스트 컨텍스트는 MySQL 컨테이너 하나를 공유한다(결정 0033). 카운터는 같은 컨텍스트를 쓰는 테스트끼리 누적된다.

## 설계 결정

1. **관측 도구는 지표 VM의 Prometheus + Grafana다.** → 결정 문서 0036, `DEC-OPS(관측)`
   - 대안: Grafana Cloud 무료 플랜과 Alloy 에이전트. 지표 VM이 없어지지만 무료 플랜의 시계열·보관 기간 제한이 있고, 지표가 집 밖으로 나간다. 부하 테스트 때 같은 스택을 집 안에서 써야 한다.
   - 대안: Prometheus 대신 VictoriaMetrics. 자원을 덜 쓰지만 이 규모에서는 차이가 없고 표준에서 벗어난다.
   - 지표 VM: VMID 103 `gbh-metrics`, 2 vCPU(물리 코어 C4) / 4GB / 32GB, `vmbr0`(LAN, 이미지 받기·Grafana 접속)와 `vmbr1` `10.10.0.13`.
   - Prometheus는 15초 간격으로 수집하고 30일 보관한다(`--storage.tsdb.retention.time=30d`). 하루 한 번인 수집 결과를 한 달 치 보기 위해서다.
2. **앱 지표와 상태 확인은 관리 포트 8081에서만 연다.** → 결정 문서 0036
   - `management.server.port: 8081`, `management.endpoints.web.exposure.include: health,prometheus`. 8080에는 actuator 경로가 없어 `api.govbenefit.kr/actuator/*`는 404다.
   - compose는 8081을 `127.0.0.1:8081`(배포 스크립트용)과 `10.10.0.11:8081`(Prometheus용)에만 연다. app VM은 `.env`에 `APP_TAG`밖에 둘 수 없어 주소를 compose에 적는다(db VM의 `DB_BIND`와 다른 이유).
   - cloudflared는 Compose 네트워크에서 `app:8081`에 닿을 수는 있지만 Tunnel 경로가 `app:8080`뿐이라 외부로 열리지 않는다.
   - 관리 포트에도 앱의 보안 필터 체인이 적용되므로 `/actuator/prometheus`를 `PUBLIC_PATHS`에 더한다. 8080에서는 처리할 경로가 없어 404다. (구현 중 확인) 관리 포트에 필터가 적용되지 않으면 더하지 않는다. 단계 5의 테스트로 판단한다. → `/actuator/prometheus`를 빼면 `ActuatorEndpointTest`의 지표 테스트가 실패해 필터가 적용됨을 확인했다. 더한다.
   - 결과: 외부에서 보던 `https://api.govbenefit.kr/actuator/health`가 없어진다. 외부 상태 확인을 쓰는 곳은 배포 문서의 수동 확인뿐이라 공개 API(`/api/profile/options`)의 200으로 바꾼다. 상태 확인을 앱 포트에 따로 남기면(`/livez` 등 추가 경로) 공개 경로가 하나 더 생기고 얻는 것이 없다.
   - 대안: 같은 포트에서 Cloudflare 규칙으로 `/actuator/*` 차단. 차단이 저장소 밖에만 있어 검증할 수 없고, Prometheus가 수집하려면 8080을 `vmbr1`에도 열어야 한다.
   - 대안: 같은 포트에서 IP 제한이나 Basic 인증. `CF-Connecting-IP`로 바뀐 요청자 주소와 Docker NAT 때문에 IP 판단이 불안정하고, Basic 인증은 비밀값이 늘어난다.
3. **배포 스크립트는 8081, 8080 순서로 상태 확인을 본다.**
   - `v0.1.0`처럼 관리 포트가 없는 이미지로 되돌릴 때도 같은 스크립트가 성공해야 한다(#87 완료 조건 3을 다음 릴리스에서 확인한다). 새 이미지의 8080 `/actuator/health`는 404라 오판하지 않는다.
   - 둘 중 하나가 `UP`이면 성공이다. 8080 확인은 관리 포트가 없는 이미지로 되돌릴 일이 없어지면 지운다는 주석을 단다.
4. **요청 지연은 `http.server.requests` 히스토그램으로 본다.**
   - `management.metrics.distribution.percentiles-histogram.[http.server.requests]: true`, `minimum-expected-value: 5ms`, `maximum-expected-value: 10s`. 버킷 범위를 줄여 시계열 수를 제한한다.
   - 대시보드는 `uri`별 p95·p99(`histogram_quantile`), 요청 수, 5xx 비율, 4xx 비율을 보인다. 관리 포트 요청(`uri=~"/actuator.*"`)은 뺀다. (구현 중 변경) p50은 꼬리 지연을 보는 데 쓰지 않아 뺐다(설계 8과 맞춤). 오류가 없는 경로는 오류율이 빈 값이 아니라 0으로 보이게 했다.
   - 대안: 앱에서 백분위수 계산. 인스턴스끼리 합칠 수 없어 앱을 늘리는 실험(결정 0034 근거)과 맞지 않는다.
5. **수집 실행 결과는 `CollectionService`가 실행을 끝낼 때 Micrometer로 기록한다.** → 결정 문서 0036(지표 이름)
   - 새 클래스 `kr.govbenefit.collection.service.CollectionMetrics`(`@Component`, `MeterRegistry`). `record(CollectionRun run, List<CollectionRunStep> steps)`.
   - 지표(Prometheus 이름):
     - `collection_runs_total{status}`: 상태별 실행 수(Counter).
     - `collection_last_run_status{status}`: 마지막 실행의 상태면 1, 나머지 0(Gauge).
     - `collection_last_run_finished_seconds`: 마지막 실행이 끝난 시각(epoch 초).
     - `collection_last_run_duration_seconds`: 마지막 실행의 소요 시간.
     - `collection_last_run_records{endpoint,result}`: 마지막 실행의 경로별 처리 건수. `result`는 `pages`·`received`·`created`·`changed`·`unchanged`·`quarantined`·`unlinked`.
   - Gauge는 첫 실행이 끝나기 전에는 내보내지 않는다. 실행이 끝날 때마다 값을 덮는다(`MultiGauge` 등).
   - 기록 시점은 `run.finish(...)` 직후, 마지막 `runRepository.save(run)` 전이다. DB가 죽어 마지막 저장이 실패해도 `FAILED`가 지표에 남는다.
   - 실행 기록을 처음 저장하지 못한 경우(DB 장애로 시작도 못 함)와 인증키가 없는 경우는 기록하지 않는다. 대시보드의 "마지막 실행 후 경과 시간"(`time() - collection_last_run_finished_seconds`)이 하루를 넘는 것으로 드러난다.
   - 배포로 재시작하면 Gauge가 다음 실행까지 비지만, 이미 수집한 값은 Prometheus에 남는다. 대시보드는 `last_over_time(…[2d])`로 지난 실행을 보인다.
   - 대안: 수집할 때 DB의 최신 실행을 읽는 `MeterBinder`. 재시작에 영향받지 않지만 수집 간격마다 DB를 조회하고 지표가 테이블 구조에 묶인다.
   - 대안: Grafana MySQL 데이터 소스로 `collection_run`을 직접 조회. Grafana에 DB 계정을 주고 지표 VM에서 db VM 3306으로 가는 경로가 새로 생긴다.
6. **VM 지표는 각 VM의 `node_exporter`, MySQL 지표는 db VM의 `mysqld_exporter`로 모은다.**
   - `node_exporter`: `network_mode: host`, `pid: host`, 루트를 읽기 전용으로 마운트(`--path.rootfs=/host`), `vmbr1` 주소의 9100에만 리슨. app VM은 `10.10.0.11:9100`(설계 2와 같은 이유로 compose에 적음), db VM은 `${DB_BIND}:9100`.
   - `mysqld_exporter`: db compose 네트워크에서 `mysql:3306`에 접속, `${DB_BIND}:9104`에만 연다. 지표 전용 계정 `exporter`는 `PROCESS`, `REPLICATION CLIENT`와 `performance_schema`의 `SELECT`만, `MAX_USER_CONNECTIONS 3`. 비밀번호는 db VM `.env`의 `EXPORTER_DB_PASSWORD`. 계정은 운영 DB에 직접 만든다(절차는 `docs/ops/metrics.md`). Flyway 마이그레이션에 넣지 않는다. 앱 스키마가 아니고 비밀번호가 필요하다.
   - (구현 중 확인) 기본 수집기가 위 권한으로 연결 수·쿼리 수(`mysql_global_status_threads_connected`, `mysql_global_status_queries` 등)를 내는지 로컬 MySQL로 확인한다. → 2026-10-30 임시 MySQL 8.4와 mysqld_exporter v0.21.0으로 연결 수·쿼리 수·느린 쿼리·`max_connections`가 권한 오류 없이 나오는 것을 확인했다. v0.21.0은 비밀번호를 `MYSQLD_EXPORTER_PASSWORD` 환경 변수로 받는다.
   - db compose에 서비스를 더해도 `mysql` 서비스 정의는 그대로라 `docker compose up -d`가 MySQL을 다시 만들지 않는다.
   - 대안: Proxmox 내장 지표 서버. Prometheus로 보내지 않는다(InfluxDB·Graphite). `pve-exporter`는 호스트에 설치해야 해 "VM 안의 실행 구성만 저장소에 둔다"(결정 0034)에서 벗어난다.
7. **Grafana는 LAN에서만, Prometheus는 지표 VM 안에서만 연다.**
   - Grafana: `3000:3000`(지표 VM의 모든 주소). 공유기 포트포워딩도 Tunnel도 없어 인터넷에서는 닿지 않는다. 관리자 비밀번호는 VM의 `grafana.env`(`GF_SECURITY_ADMIN_PASSWORD`, 권한 600), 익명 접근과 가입은 끈다.
   - Prometheus: `127.0.0.1:9090`. Grafana는 Compose 네트워크로 `prometheus:9090`에 붙으므로 바깥에 열 필요가 없다. 직접 볼 때는 `ssh -L 9090:127.0.0.1:9090 govbenefit-metrics`. 처음 권장안은 `vmbr1`에 여는 것이었으나, `vmbr1`에서 Prometheus를 볼 곳이 없어 더 좁혔다.
   - 대안: Grafana도 `ssh -L`로만 접속. 더 닫혀 있지만 확인할 때마다 터널을 열어야 한다.
8. **대시보드는 provisioning으로 저장소에서 만든다.**
   - `deploy/metrics/grafana/provisioning/datasources/prometheus.yaml`(uid `prometheus` 고정), `deploy/metrics/grafana/provisioning/dashboards/dashboards.yaml`(파일 제공자, `allowUiUpdates: false`), `deploy/metrics/grafana/dashboards/*.json`.
   - 대시보드 세 개를 직접 만든다. 완료 조건의 줄과 맞춘다.
     - `app.json` 앱: 경로별 요청 수·p95·p99·5xx·4xx 비율, JVM 힙·비힙 메모리, GC 횟수·일시 정지 시간, Hikari 활성·유휴·대기 연결과 획득 시간.
     - `infra.json` VM·MySQL: VM별 CPU·메모리·디스크 사용률과 디스크 I/O, MySQL 연결 수·초당 쿼리·느린 쿼리·`max_connections` 대비 사용률.
     - `collection.json` 수집: 마지막 실행 상태·소요 시간·경과 시간, 경로별 처리 건수, 30일 상태 이력.
   - 화면에서 고친 대시보드는 JSON으로 내보내 커밋해야 남는다. `docs/ops/metrics.md`에 적는다.
   - 대안: 커뮤니티 대시보드 JSON 커밋. 패널 대부분이 필요 없고 수천 줄이라 검토하기 어렵다. 수집 대시보드는 어차피 직접 만든다.
9. **이미지 버전은 고정한다.** `prom/prometheus`, `grafana/grafana`, `prom/node-exporter`, `prom/mysqld-exporter`를 구현할 때의 최신 안정 버전으로 고정한다. (구현 중 확인) 2026-10-30 Docker Hub 기준 `v3.15.1`, `13.2.5`(13.3.0은 나온 직후라 한 단계 아래 패치), `v1.12.1`, `v0.21.0`. cloudflared(결정 0034)와 달리 오래돼도 동작이 멈추지 않고, 버전이 바뀌면 대시보드와 설정이 깨질 수 있다.
10. **결정 문서는 0034를 대체하는 0036 하나로 쓴다.**
    - 0034의 "상태 확인은 actuator health를 공개 경로에", "배포 스크립트는 8080의 `/actuator/health`를 기다린다"가 바뀐다. 채택된 문서는 고치지 않으므로(결정 README) 0036 "Proxmox 단일 호스트 배포와 운영 지표"가 0034의 유효한 내용에 관리 포트·지표 VM·지표 이름을 더해 대체한다. 0034는 상태 줄만 `대체됨 → 0036`으로 바꾼다.
    - 대안: 지표만 다루는 0036과 0034를 고친 0037로 나누기. 관리 포트가 두 문서의 경계에 걸쳐 같은 내용을 두 곳에 적게 된다.

## 단계별 구현 계획

1. **플랜 상태**: 착수할 때 이 파일의 `status`를 `in-progress`로 바꾼다.
2. **앱 지표 설정**
   - `backend/build.gradle`: `runtimeOnly 'io.micrometer:micrometer-registry-prometheus'`.
   - `application.yaml`의 `management`(설계 2·4, 주석에 이유와 결정 0036):
     - `server.port: 8081`
     - `endpoints.web.exposure.include: health,prometheus`
     - `metrics.distribution.percentiles-histogram.[http.server.requests]: true`, `minimum-expected-value.[http.server.requests]: 5ms`, `maximum-expected-value.[http.server.requests]: 10s`
   - `SecurityConfig.PUBLIC_PATHS`에 `/actuator/prometheus`(설계 2). `/actuator/health`의 주석을 관리 포트 기준으로 고친다.
3. **수집 실행 결과 지표**(설계 5)
   - `kr.govbenefit.collection.service.CollectionMetrics` 추가.
   - `CollectionService`: `collectAll()`에서 `List<CollectionRunStep> steps`를 만들어 `collectEndpoints`·`collectEndpoint`에 넘기고, `collectEndpoint`가 시작한 경로 기록을 담는다. `run.finish(...)` 직후 `collectionMetrics.record(run, steps)`.
4. **배포 스크립트** `scripts/deploy.sh`(설계 3): 상태 확인을 `http://127.0.0.1:8081/actuator/health`, 실패하면 `http://127.0.0.1:8080/actuator/health` 순서로 본다.
5. **테스트**
   - `HealthEndpointTest`를 `ActuatorEndpointTest`로 바꾼다. `RANDOM_PORT`로 실제 서버를 띄우고 `local.server.port`와 `local.management.port`로 요청한다. Prometheus 내보내기를 켠다.
     - 관리 포트 `/actuator/health`는 토큰 없이 200, `UP`, `components` 없음.
     - 관리 포트 `/actuator/prometheus`는 토큰 없이 200. API를 한 번 부른 뒤 `http_server_requests_seconds_bucket`, `jvm_memory_used_bytes`, `hikaricp_connections_active`가 있다.
     - 앱 포트 `/actuator/health`, `/actuator/prometheus`는 404(완료 조건 1).
     - 관리 포트 `/actuator/env`는 404.
   - `ClientIpTest`: 요청 경로를 `/api/profile/options`로 바꾼다(앱 포트에서 200인 공개 경로).
   - (구현 중 변경) `ActuatorEndpointTest`가 실제 서버용 컨텍스트를 하나 더 만들자 전체 테스트에서 공유 MySQL이 `Too many connections`로 컨텍스트 기동에 실패했다(develop 기준은 거부 0회). 컨텍스트마다 커넥션 풀(기본 10개)을 잡기 때문이다. `ClientIpTest`의 중첩 설정 `RemoteAddrRecorder`를 최상위 테스트 설정(`kr.govbenefit.common.RemoteAddrRecorder`)으로 옮기고 두 테스트의 설정을 같게 해 컨텍스트를 함께 쓰게 했다. 테스트 클래스 안의 중첩 `@TestConfiguration`은 그 클래스의 컨텍스트에만 더해져 설정을 같게 적어도 공유되지 않는다.
   - `CollectionServiceTest`: 성공 실행 뒤 `MeterRegistry`에서 `collection.last.run.status{status=SUCCEEDED}`가 1, `collection.last.run.records{endpoint=SERVICE_LIST,result=received}`가 stub 건수, `collection.runs{status=SUCCEEDED}`가 실행 전보다 1 많다. 카운터는 테스트끼리 누적되므로 차이로 확인한다.
   - `CollectionFaultToleranceTest`: 목록 1쪽 실패로 `FAILED`가 되는 기존 사례에 `collection.last.run.status{status=FAILED}`가 1인지 더한다.
6. **app·db VM 실행 구성**
   - `deploy/app/compose.yaml`: `app`의 `ports`에 `127.0.0.1:8081:8081`, `10.10.0.11:8081:8081`. `node-exporter` 서비스(설계 6).
   - `deploy/db/compose.yaml`: `node-exporter`, `mysqld-exporter`(설계 6). `deploy/db/.env.example`에 `EXPORTER_DB_PASSWORD`.
7. **지표 VM 실행 구성** `deploy/metrics/`
   - `compose.yaml`: `prometheus`(설정 마운트, 이름 있는 볼륨, 보관 30일, `127.0.0.1:9090`), `grafana`(provisioning·대시보드 읽기 전용 마운트, 이름 있는 볼륨, `env_file: grafana.env`, `3000:3000`). 로그 설정은 다른 compose와 같다.
   - `prometheus.yml`: 작업 `app`(`10.10.0.11:8081`, `/actuator/prometheus`), `node`(`10.10.0.11:9100`, `10.10.0.12:9100`), `mysql`(`10.10.0.12:9104`), `prometheus`(자기 자신). 대상마다 `vm` 라벨(`app`·`db`).
   - `grafana.env.example`: `GF_SECURITY_ADMIN_PASSWORD=`, `GF_AUTH_ANONYMOUS_ENABLED=false`, `GF_USERS_ALLOW_SIGN_UP=false`.
   - `grafana/provisioning/…`, `grafana/dashboards/{app,infra,collection}.json`(설계 8).
8. **운영 문서**
   - `docs/ops/proxmox-vm.md`: 구성 그림과 사양 표에 지표 VM, "4. VM 만들기"에 VMID 103(affinity C4), "5. VM 안 준비"에 `/opt/gov-benefit/metrics`, "6. SSH 설정"에 `govbenefit-metrics`, "7. 확인"에 지표 VM에서 `10.10.0.11:8081`·`9100`, `10.10.0.12:9100`·`9104`에 닿고 LAN의 다른 기기에서는 이 포트들에 닿지 않는다.
   - `docs/ops/metrics.md`(새 문서): 지표 VM에 실행 구성 복사(`scp -r deploy/metrics`), `grafana.env` 채우기, 기동, db VM의 `exporter` 계정 만들기(SQL)와 db 실행 구성 반영, Grafana 접속, 대시보드를 고쳤을 때 JSON을 내보내 커밋하는 방법, 설정을 바꿨을 때 다시 반영하는 방법, 보관 기간과 디스크.
   - `docs/ops/deploy.md`: 구성 요약에 지표 행, "5. 확인 배포"와 "9. 확인"의 외부 확인을 `/api/profile/options` 200과 `/actuator/prometheus` 404로, 앱 상태 확인 주소를 관리 포트로, "DB 구성 바꾸기"에 exporter 추가 시 MySQL이 다시 만들어지지 않는다는 점.
9. **결정과 제품 문서**
   - `docs/decisions/0036-proxmox-deploy-and-metrics.md`(설계 10). 0034의 상태 줄을 `대체됨 → [0036](0036-proxmox-deploy-and-metrics.md)`으로, `docs/decisions/README.md` 목록에 두 문서 상태를 고친다.
   - (구현 중 변경) 결정 0018로 대체할 때(#45)처럼 코드·설정·운영 문서·제품 문서에서 결정 0034를 가리키던 곳을 0036으로 바꿨다. 채택된 다른 결정 문서(0035)의 본문은 고치지 않았다. `docs/README.md` 문서 안내에 운영 지표 문서를 더했다.
   - `docs/product/development.md`
     - "결정한 사항" 표에 `DEC-OPS(관측)` 행: 선택(지표 VM의 Prometheus + Grafana, 관리 포트 분리, node·mysqld exporter, LAN 전용 접속, provisioning), 이유, 한계(경보 없음, 집 밖에서 볼 수 없음, 지표 VM이 죽으면 그동안 지표를 잃음, 30일 보관), 결정 0036 링크.
     - 변경 기록에 "DEC-OPS 중 관측 도구를 2026-10-xx에 정했다. 기록은 #92에서 했다."
     - `DEC-OPS(배포)` 행의 결정 0034 링크를 0036으로 고친다.
     - "아직 결정할 사항"의 `DEC-OPS` 행에서 관측 도구를 뺀다(백업 방식, 성능 목표가 남는다).
     - O3 행: 기본 지표를 부하 테스트와 떼어 #92에서 만들었다는 것과 완료 증거.
   - `docs/product/requirements.md`의 `REQ-OPS-02` 끝: 기본 지표는 #92에서 Prometheus·Grafana로 수집하고, 경보·로그·기능별 지표는 P1로 남는다는 것.
10. **검증과 PR**: 아래 검증 명령이 통과하면 마지막 커밋에서 `status: done`으로 바꾸고 PR을 올린다. 운영 확인은 머지 뒤에 하므로 PR 본문은 `Refs #92`로 적고 남은 완료 조건을 적는다.
11. **[사용자] 운영 반영과 확인(머지 뒤)**: 다음 릴리스를 배포해 앱의 관리 포트를 연다. `docs/ops/proxmox-vm.md`대로 지표 VM을 만들고, `docs/ops/metrics.md`대로 db exporter 계정과 지표 스택을 띄운다. 다음 날 04:00 수집 뒤 수집 대시보드를 확인하고 이슈를 닫는다. 같은 릴리스에서 #87 완료 조건 3(되돌리기)을 확인하면 설계 3의 대체 경로도 확인된다.

## 제안 (이 이슈에서 하지 않음)

- **테스트 공유 MySQL의 연결 여유**: 테스트 컨텍스트가 하나 늘 때마다 커넥션 풀 10개가 더해지는데, 공유 MySQL(결정 0033)의 `max_connections`는 기본값 151이다. 이 이슈에서 실제 서버용 컨텍스트 하나가 늘자 연결 거부로 테스트가 실패했다. 컨텍스트를 공유하게 고쳐 지금은 거부가 0회지만, 설정이 다른 테스트 클래스가 생기면 다시 생긴다. 테스트 MySQL의 `max_connections`를 올리거나 테스트의 풀 크기를 줄이는 것을 별도 이슈로 검토한다.

## 완료 조건 매핑

| 이슈 완료 조건 | 충족 방법 |
| --- | --- |
| `https://api.govbenefit.kr`로는 앱 지표 경로에 접근할 수 없다. | 설계 2, 단계 5 `ActuatorEndpointTest`(앱 포트 404). 운영에서 `curl https://api.govbenefit.kr/actuator/prometheus`가 404 |
| Grafana에서 운영 app의 경로별 요청 지연·오류율, JVM 메모리·GC, DB 커넥션 풀을 본다. | 설계 4·8, 단계 2·7 `app.json` |
| Grafana에서 app·db VM의 CPU·메모리·디스크와 MySQL 연결·쿼리 지표를 본다. | 설계 6·8, 단계 6·7 `infra.json` |
| 예약 수집이 끝난 뒤 수집 실행 결과(성공·부분 실패·실패, 처리 건수, 소요 시간)를 Grafana에서 본다. | 설계 5·8, 단계 3·5·7 `collection.json`. 04:00 수집 뒤 확인 |
| Grafana와 Prometheus는 인터넷에서 접근할 수 없다. | 설계 7. Prometheus는 `127.0.0.1`, Grafana는 LAN(포트포워딩·Tunnel 없음). 휴대폰 데이터망에서 집 공인 IP의 3000·9090이 닿지 않는지 확인 |
| 지표 VM을 문서 절차대로 다시 만들면 같은 대시보드가 보인다. | 설계 8, 단계 7·8. 문서대로 처음 만든 것으로 확인하고, `docker compose down -v` 뒤 다시 띄워 대시보드 세 개가 그대로 보이는지 확인 |

## 검증 명령

```bash
cd backend && ./gradlew test
bash -n scripts/deploy.sh
APP_TAG=v0.0.0 docker compose -f deploy/app/compose.yaml config -q --no-env-resolution
sed 's/=$/=x/' deploy/db/.env.example > /tmp/db.env && docker compose --env-file /tmp/db.env -f deploy/db/compose.yaml config -q
docker compose -f deploy/metrics/compose.yaml config -q --no-env-resolution
docker run --rm -v "$PWD/deploy/metrics:/cfg:ro" --entrypoint promtool prom/prometheus:v3.15.1 check config /cfg/prometheus.yml
```

대시보드는 로컬에서 Grafana를 provisioning으로 띄워 세 개가 오류 없이 불러와지는지(`/api/search`, 패널 쿼리 오류 없음) 보고, 패널의 지표 이름이 로컬 앱(`SPRING_PROFILES_ACTIVE=local ./gradlew bootRun`)의 `http://localhost:8081/actuator/prometheus` 출력에 있는지 대조한다. 운영 확인은 `docs/ops/metrics.md`를 따른다.

(구현 중 확인) 2026-10-30 로컬에서 앱(local 프로필), 임시 MySQL과 mysqld_exporter, node_exporter, Prometheus, Grafana를 띄워 확인했다. Grafana가 대시보드 세 개를 불러왔고, 대시보드의 모든 쿼리가 Prometheus에서 오류 없이 실행됐다. 수집 지표는 로컬에서 실행하지 않고 테스트에서 수집을 한 번 돌린 출력을 수집 대상으로 붙여 확인했다. 다음은 로컬에서 확인하지 못해 운영에서 확인한다.
- 디스크 사용률(`mountpoint="/"`): Docker Desktop의 VM에는 일반 ext4 루트가 없어 값이 나오지 않았다. Ubuntu VM에서는 루트가 `mountpoint="/"`로 나온다.
- 패널 화면 표시: 쿼리 결과만 확인했고 브라우저로 화면을 보지 않았다.
- `tomcat_threads_busy_threads`는 Tomcat MBean 등록을 켜야 나와 대시보드에서 뺐다(구현 중 변경).

## 결정 필요

없음. 1차 검토에서 권장안(관측 도구, 관리 포트 분리, 수집 결과 Micrometer 기록, 히스토그램, node·mysqld exporter, LAN 전용 Grafana, provisioning)으로 정했다(2026-10-30).
