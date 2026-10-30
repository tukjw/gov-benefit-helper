# 운영 지표

지표 VM의 Prometheus로 운영 app의 지표, app·db VM의 지표, MySQL 지표를 모으고 Grafana로 보는 절차다. 구조는 [결정 0036](../decisions/0036-proxmox-deploy-and-metrics.md)에 있다. 지표 VM은 [Proxmox VM 만들기](proxmox-vm.md)로 먼저 만든다.

비밀번호는 이 문서와 저장소에 적지 않는다.

## 구성 요약

| 무엇 | 어디에 | 누가 읽나 |
| --- | --- | --- |
| 앱 지표(요청·JVM·커넥션 풀·수집 실행 결과) | app VM `app` 컨테이너의 관리 포트 `10.10.0.11:8081/actuator/prometheus` | Prometheus |
| VM 지표 | app·db VM의 `node-exporter` (`10.10.0.11:9100`, `10.10.0.12:9100`) | Prometheus |
| MySQL 지표 | db VM의 `mysqld-exporter` (`10.10.0.12:9104`) | Prometheus |
| Prometheus | 지표 VM `127.0.0.1:9090`, 30일 보관 | Grafana, `ssh -L`로 직접 |
| Grafana | 지표 VM `<LAN 주소>:3000` | LAN의 브라우저 |
| 대시보드 | 저장소 `deploy/metrics/grafana/dashboards/` | Grafana가 불러온다 |
| 비밀값 | 지표 VM `/opt/gov-benefit/metrics/grafana.env`, db VM `/opt/gov-benefit/db/.env`의 `EXPORTER_DB_PASSWORD` (권한 600) | 직접 수정 |

수집 대상은 모두 내부 브리지(`vmbr1`) 주소에만 열려 있어 LAN에서는 닿지 않는다. Grafana는 LAN에 열지만 공유기 포트포워딩도 Tunnel도 없어 인터넷에서는 닿지 않는다.

## 처음 한 번

### 1. db VM에 exporter 계정과 실행 구성 반영

db VM에서 지표 전용 계정을 만든다. 비밀번호는 새로 만든다(예: `openssl rand -base64 24`).

```bash
cd /opt/gov-benefit/db
docker compose exec mysql mysql -uroot -p
```

```sql
CREATE USER 'exporter'@'%' IDENTIFIED BY '<비밀번호>' WITH MAX_USER_CONNECTIONS 3;
GRANT PROCESS, REPLICATION CLIENT ON *.* TO 'exporter'@'%';
GRANT SELECT ON performance_schema.* TO 'exporter'@'%';
```

같은 비밀번호를 db VM `.env`의 `EXPORTER_DB_PASSWORD`에 적는다([`deploy/db/.env.example`](../../deploy/db/.env.example)).

내 PC의 저장소 루트에서 실행 구성을 복사하고, db VM에서 반영한다. `mysql` 서비스 정의는 그대로라 MySQL은 다시 만들어지지 않고 exporter만 새로 뜬다.

```bash
scp deploy/db/compose.yaml govbenefit-db:/opt/gov-benefit/db/
ssh govbenefit-db 'cd /opt/gov-benefit/db && docker compose up -d && docker compose ps'
```

### 2. app VM에 관리 포트와 node-exporter 반영

관리 포트와 `node-exporter`는 이 변경이 들어간 릴리스부터 있다. [배포 절차](deploy.md#릴리스할-때)대로 배포하면 배포 스크립트가 그 시점의 실행 구성을 복사하고 `docker compose up -d`로 `node-exporter`도 띄운다.

### 3. 지표 VM에 실행 구성 복사와 기동

내 PC의 저장소 루트에서:

```bash
scp -r deploy/metrics/. govbenefit-metrics:/opt/gov-benefit/metrics/
```

지표 VM에서 관리자 비밀번호를 채우고 띄운다.

```bash
cd /opt/gov-benefit/metrics
cp grafana.env.example grafana.env && chmod 600 grafana.env
vi grafana.env   # GF_SECURITY_ADMIN_PASSWORD
docker compose up -d
```

### 4. 확인

- Prometheus의 수집 대상이 모두 `UP`이다. 내 PC에서 `ssh -L 9090:127.0.0.1:9090 govbenefit-metrics`로 연 뒤 `http://localhost:9090/targets`를 본다.
- LAN의 브라우저에서 `http://<지표 VM의 LAN 주소>:3000`에 `admin`과 위 비밀번호로 로그인하면 Dashboards의 `앱`, `VM·MySQL`, `수집`이 보인다.
  - `앱`: 경로별 요청 수·지연·오류율, JVM 메모리·GC, 커넥션 풀
  - `VM·MySQL`: 수집 대상 상태, VM별 CPU·메모리·디스크, MySQL 연결·쿼리
  - `수집`: 예약 수집(매일 04:00)이 한 번 끝난 뒤부터 마지막 실행의 상태·소요 시간·처리 건수와 이력이 보인다. 앱을 다시 배포한 뒤에도 지난 실행은 Prometheus에 남은 값으로 보인다.
- 외부에서 앱 지표에 닿지 않는다: `curl -s -o /dev/null -w '%{http_code}\n' https://api.govbenefit.kr/actuator/prometheus`가 `404`
- 인터넷에서 Grafana와 Prometheus에 닿지 않는다: 휴대폰 데이터망에서 집 공인 IP의 3000·9090에 접속되지 않는다.

## 바꿀 때

### 대시보드 고치기

provisioning으로 불러온 대시보드는 화면에서 저장할 수 없다. 저장소의 JSON이 기준이다.

1. Grafana 화면에서 패널을 고친 뒤 대시보드의 Export → Export as JSON으로 내려받는다. 외부 공유용 내보내기(데이터 소스를 변수로 바꾸는 옵션)는 끈다. 데이터 소스 uid는 `prometheus`다.
2. `deploy/metrics/grafana/dashboards/<이름>.json`을 바꿔 PR로 머지한다. `uid`는 그대로 둔다(`gbh-app`, `gbh-infra`, `gbh-collection`).
3. 지표 VM에 복사한다. Grafana가 60초 안에 다시 읽는다.

   ```bash
   scp -r deploy/metrics/grafana/dashboards/. govbenefit-metrics:/opt/gov-benefit/metrics/grafana/dashboards/
   ```

앱의 지표 이름이나 태그를 바꾸면 대시보드 쿼리도 함께 고친다(결정 0036).

### Prometheus 설정·이미지 버전 바꾸기

`deploy/metrics/`를 고쳐 머지한 뒤 3의 `scp`로 복사하고, 지표 VM에서 `docker compose up -d`(이미지나 compose가 바뀐 경우) 또는 `docker compose restart prometheus`(설정 파일만 바뀐 경우)를 실행한다.

### Grafana 관리자 비밀번호 바꾸기

`grafana.env`의 값은 Grafana 데이터가 처음 만들어질 때만 적용된다. 이미 띄운 뒤에는:

```bash
cd /opt/gov-benefit/metrics
docker compose exec grafana grafana cli admin reset-admin-password '<새 비밀번호>'
```

`grafana.env`도 같은 값으로 고쳐 둔다. 다시 만들 때 그 값이 쓰인다.

## 다시 만들기

지표 VM이 망가지면 [Proxmox VM 만들기](proxmox-vm.md)대로 VM을 만들고 위 "처음 한 번"의 3·4를 따른다. 대시보드와 데이터 소스는 저장소에서 다시 불러오므로 같은 화면이 뜬다. 쌓인 지표(최대 30일)는 잃는다. app·db VM의 exporter는 그대로 둔다.

VM을 다시 만들지 않고 확인하려면 지표 VM에서 볼륨까지 지우고 다시 띄운다. 쌓인 지표와 Grafana 설정(비밀번호 포함)이 지워진다.

```bash
cd /opt/gov-benefit/metrics
docker compose down -v && docker compose up -d
```

## 보관과 디스크

Prometheus는 30일 치를 보관한다(`deploy/metrics/compose.yaml`의 `--storage.tsdb.retention.time`). 지표 VM의 디스크 사용량은 `docker system df -v`로 본다.
