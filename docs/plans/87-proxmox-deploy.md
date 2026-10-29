status: done

# 구현 계획 — #87 Proxmox 호스트에 운영 환경을 구성하고 릴리스 태그로 배포·되돌리기

브랜치: `chore/87-proxmox-deploy` (develop `a54ffb8` 기준)

## 목표 및 범위

- 목표: 개인 Proxmox 호스트의 app VM·db VM에 API를 올리고, 릴리스 태그로 만든 이미지를 SSH 스크립트 하나로 배포·되돌린다. API는 Cloudflare Tunnel로 `api.govbenefit.kr`에, 프론트는 Cloudflare Pages로 `govbenefit.kr`에 연다.
- 포함:
  - `DEC-OPS(배포)` 결정 기록과 결정 문서 0034, `REQ-OPS-01`·`REQ-OPS-02`와 개발 순서 O2·O3 조정.
  - 앱 이미지(`backend/Dockerfile`)와 태그 push 시 이미지를 빌드해 GHCR에 올리는 워크플로.
  - 상태 확인 경로(`/actuator/health`)와 Cloudflare가 전달한 클라이언트 IP를 요청자 주소로 쓰는 설정.
  - app VM·db VM의 실행 구성(`deploy/app/`, `deploy/db/`)과 배포 스크립트 `scripts/deploy.sh`.
  - VM 생성 절차와 배포·되돌리기·첫 수집 절차 문서(`docs/ops/`).
  - Cloudflare 설정(Tunnel, Pages, 비회원 조건 비교 목록 요청량 제한) 절차. 설정 자체는 사용자가 한다.
- 제외:
  - 내부 테스트 환경(Proxmox VM의 nginx 프론트, 내부 HTTPS). 결정만 기록하고 구성은 부하 테스트 이슈에서 한다.
  - 지표 수집(Prometheus·Grafana)과 부하 테스트. 부하 테스트 이슈에서 한다.
  - 백업과 연속 수집 실패 경보. P1로 내린다(설계 1).
  - VM 생성의 코드화(Terraform, `qm` 스크립트). P1로 내린다(설계 1).
  - IP별 로그인 시도 제한(#52). 이 이슈는 앱이 실제 클라이언트 IP를 보게만 한다.
  - 되돌리기의 실제 실행. 첫 릴리스에는 직전 릴리스가 없으므로 다음 릴리스를 배포할 때 확인한다(완료 조건 3).

## 확인된 사실과 제약

- 앱 설정
  - 프로필은 `local`과 기본(`application.yaml`)뿐이다. 운영은 기본 프로필에 환경 변수로 값을 넣는다. 필요한 값: `DB_HOST`·`DB_PORT`·`DB_NAME`·`DB_USER`·`DB_PASSWORD`, `JWT_SECRET`(없으면 기동 실패), `GOV24_SERVICE_KEY`, `MAIL_SENDER=resend`·`RESEND_API_KEY`·`RESEND_FROM`(없으면 기동 실패, 결정 0032).
  - `spring.config.import: optional:file:.env[.properties]`는 작업 디렉터리의 `.env`를 읽는다. 컨테이너에서는 환경 변수로 넣으므로 쓰지 않는다.
  - CSRF·CORS 허용 출처 기본값이 이미 `https://govbenefit.kr`이고, 재설정 링크 기본값도 `https://govbenefit.kr/reset-password`다.
  - 수집은 `gov24.schedule.cron`(매일 04:00 Asia/Seoul)에 돈다. 기동 시 한 번 수집하는 `CollectionRunner`는 `collect` 프로필에서만 켜진다.
  - Flyway가 기동할 때 마이그레이션한다(`ddl-auto: validate`).
- 시간대(결정 0002): DB·JDBC·Hibernate를 `Asia/Seoul`로 맞춘다. Hibernate는 JVM 기본 시간대와 DB 사이에서 변환하므로 테스트 JVM에도 `user.timezone=Asia/Seoul`을 준다(`build.gradle`). 컨테이너의 JVM 기본 시간대는 UTC다.
- 보안 경계: `SecurityConfig.PUBLIC_PATHS`에 없는 경로는 액세스 토큰이 있어야 한다. actuator 의존성은 없다.
- 리프레시 토큰 쿠키는 `Secure`·`HttpOnly`·`SameSite=Strict`·`Path=/api/auth`다(`RefreshTokenCookies`). `govbenefit.kr`과 `api.govbenefit.kr`은 같은 사이트라 그대로 동작한다.
- 지금 코드에서 클라이언트 IP(`getRemoteAddr`, `X-Forwarded-For`)를 읽는 곳은 없다.
- 이미지: Dockerfile도, 이미지를 만드는 워크플로도 없다. CI(`ci.yml`)는 PR에서만 돈다. `build.gradle`의 `version`은 `0.0.1`이고 릴리스 때 `X.Y.Z`로 맞춘다(AGENTS.md).
- 저장소는 지금 비공개(private)이고, 사용자가 공개로 바꾼다(2026-10-28 결정).
- 프론트: `VITE_API_BASE_URL`은 빌드할 때 번들에 고정된다(결정 0029). Node 버전은 `frontend/.nvmrc`(26)다. `public/`에 `404.html`이 없다.
- 로컬 MySQL은 `mysql:8.4`에 `--character-set-server=utf8mb4 --collation-server=utf8mb4_0900_ai_ci`, `TZ=Asia/Seoul`로 띄운다(`docker-compose.local.yml`, 결정 0001·0002).
- `govbenefit.kr` DNS는 Cloudflare에서 관리한다. Resend 레코드도 이미 Cloudflare에 있다.
- 호스트: Ryzen 7 8845HS(8코어 16스레드), RAM 32GB.

## 설계 결정

1. **요구사항을 실제 운영 방식에 맞춰 조정한다.**
   - `REQ-OPS-01` P0의 "배포 환경(서버·네트워크·DB)을 코드로 정의해 다시 만들 수 있다"를 "VM 안의 실행 구성은 저장소에 두고, VM은 문서 절차로 다시 만들 수 있다"로 바꾸고, VM 생성의 코드화는 P1로 옮긴다. 호스트가 한 대고 VM을 다시 만들 일이 드물어 Terraform이나 스크립트를 유지하는 비용이 더 크다.
   - `REQ-OPS-02` P0의 연속 수집 실패 경보와 정기 백업을 P1로 옮긴다. 상태 확인은 P0로 남기고, 기본 지표 수집은 부하 테스트 이슈에서 만든다.
   - 한계: 디스크가 망가지면 계정·프로필·관심 혜택을 잃는다(혜택은 다시 수집하면 된다). 수집이 계속 실패해도 운영자가 직접 확인하기 전에는 모른다.
2. **배포 구조를 결정 문서 0034로 올린다.** Proxmox 단일 호스트에 app VM과 db VM을 두고, 각 VM에서 Docker Compose로 컨테이너를 띄운다. 이후 배포·부하 테스트·운영 작업이 모두 이 구조와 설정 위치를 따른다.
   - 대안: 한 VM에 앱과 DB. 단순하지만 사용자가 DB 분리를 원했고, 분리하면 앱만 늘리는 실험도 할 수 있다.
   - 대안: k3s 같은 오케스트레이션. VM마다 컨테이너가 1~2개라 얻는 것이 없다.
3. **이미지는 CI에서 jar를 만들고, Dockerfile은 그 jar만 담는다.**
   - `backend/Dockerfile`: `eclipse-temurin:21-jre` 기반, 비루트 사용자로 실행. `JAVA_TOOL_OPTIONS`에 `-XX:MaxRAMPercentage=75 -Duser.timezone=Asia/Seoul`(결정 0002)을 준다.
   - `bootJar`의 파일 이름을 `app.jar`로 고정해 Dockerfile이 버전을 몰라도 되게 한다.
   - 대안: 멀티 스테이지로 Dockerfile 안에서 Gradle 빌드. 로컬에서 바로 만들 수 있지만 CI에서 Gradle 캐시를 따로 챙겨야 한다. `setup-gradle` 캐시를 그대로 쓰는 쪽이 빠르다.
   - 대안: `bootBuildImage`(Buildpacks). Dockerfile이 없지만 이미지 구성이 감춰지고, JVM 옵션과 시간대를 확인하기 어렵다.
4. **이미지 워크플로 `.github/workflows/image.yml`**
   - `push: tags: ['v*.*.*']`: 태그와 `build.gradle`의 `version`이 다르면 실패한다. 버전 반영 PR을 빠뜨린 릴리스를 막는다. 이미지 태그는 git 태그와 같은 문자열(`v0.1.0`)이다. `latest`는 두지 않는다. 배포는 항상 태그를 지정한다.
   - `workflow_dispatch`: 릴리스 전에 VM 구성을 확인할 이미지를 `sha-<7자리>` 태그로 만든다. 첫 릴리스 전에 Tunnel·DB·비밀값을 끝까지 확인하기 위해서다. 운영 배포는 `v*` 태그로만 한다(docs에 적는다).
   - (구현 중 확인) `workflow_dispatch`는 워크플로가 기본 브랜치(`develop`)에 있어야 실행된다. 그래서 확인 배포는 이 PR이 머지된 뒤 `develop`에서 만든 이미지로 한다(단계 10·11).
   - 테스트는 돌리지 않는다. 릴리스 태그는 `test` 체크를 통과한 PR의 머지 커밋에 붙는다.
   - 이미지 이름: `ghcr.io/tukjw/gov-benefit-helper`. 공개 이미지로 둬 VM에서 토큰 없이 받는다. 저장소도 공개하므로 이미지에 담긴 코드가 새로 드러나는 것은 없다. 이미지에는 비밀값이 없다.
5. **상태 확인은 actuator health만 연다.**
   - `spring-boot-starter-actuator`를 더하고 `management.endpoints.web.exposure.include: health`로 명시한다. 기본 설정과 같지만, 부하 테스트 이슈에서 지표를 열 때 바꿀 자리를 드러낸다. 세부 정보는 기본값(`never`)이다.
   - `SecurityConfig.PUBLIC_PATHS`에 `/actuator/health`를 더한다.
   - health에 DB 상태가 들어가므로, 배포 스크립트가 health를 기다리면 DB 연결과 Flyway 마이그레이션까지 확인된다.
   - 대안: 직접 만든 `/api/health`. actuator는 부하 테스트에서 어차피 필요하다.
6. **클라이언트 IP는 Tomcat의 원격 IP 처리로 `CF-Connecting-IP`를 쓴다.**
   - `server.forward-headers-strategy: native`, `server.tomcat.remoteip.remote-ip-header: CF-Connecting-IP`. 신뢰할 프록시(`internal-proxies`)는 기본값(사설 대역)을 그대로 둔다. cloudflared 컨테이너의 주소가 Docker 사설 대역이다.
   - 앱 포트는 app VM의 `127.0.0.1`에만 열려 있어(설계 7) 헤더를 보낼 수 있는 곳은 cloudflared와 VM 자신뿐이다. Cloudflare는 이 헤더를 덮어써서 전달한다.
   - 대안: `X-Forwarded-For`. 여러 프록시를 거치면 목록에서 몇 번째를 믿을지 정해야 한다. `CF-Connecting-IP`는 값이 하나다.
   - 대안: 필터에서 헤더를 직접 읽기. #52가 쓸 `getRemoteAddr()`가 그대로 실제 IP가 되는 쪽이 단순하다.
7. **VM 실행 구성**
   - `deploy/app/compose.yaml`: `app`(`ghcr.io/tukjw/gov-benefit-helper:${APP_TAG:?}`, `env_file: app.env`, 포트 `127.0.0.1:8080:8080`)과 `cloudflared`(`cloudflare/cloudflared:<고정 버전>`, `tunnel --no-autoupdate run`, `TUNNEL_TOKEN`). 둘 다 `restart: unless-stopped`. 로그는 `json-file`의 `max-size: 10m`·`max-file: 3`으로 디스크를 채우지 않게 한다.
   - `deploy/app/app.env.example`: 위 "앱 설정"의 환경 변수를 빈 값으로. `DB_HOST`는 db VM의 내부 주소다.
   - `deploy/app/.env`(VM에서만): Compose 치환용 `APP_TAG` 한 줄. 배포 스크립트만 고친다. 비밀값(`app.env`)과 나눠, 스크립트가 비밀값 파일을 건드리지 않게 한다.
   - `deploy/db/compose.yaml`: `mysql:8.4`, 로컬과 같은 문자 집합·정렬·`TZ`, `--innodb-buffer-pool-size=1G`, 포트 `${DB_BIND:?}:3306:3306`, 이름 있는 볼륨. `deploy/db/.env.example`에 `MYSQL_ROOT_PASSWORD`·`DB_NAME`·`DB_USER`·`DB_PASSWORD`·`DB_BIND`.
   - 네트워크: 물리 포트가 없는 내부 브리지 `vmbr1`(`10.10.0.0/24`, app `.11`, db `.12`). 두 VM 모두 `vmbr0`(LAN, 인터넷·SSH)과 `vmbr1`에 붙는다. MySQL은 `10.10.0.12`에만 열어 LAN에서는 닿지 않는다.
   - Tunnel의 경로(`api.govbenefit.kr` → `http://app:8080`)는 Cloudflare 대시보드에 두는 원격 관리 방식이다. 저장소에는 토큰을 받는 자리만 둔다. 설정 파일 방식은 자격 증명 파일을 VM에 따로 옮겨야 한다.
8. **배포 스크립트 `scripts/deploy.sh <이미지 태그>`**
   - 받는 값: `v*`(git 태그) 또는 `sha-<7자리>`(커밋). SSH 대상은 `DEPLOY_HOST`(기본 `govbenefit-app`, `~/.ssh/config` 별칭).
   - 순서: ① 태그나 커밋이 로컬 저장소에 있는지 확인 ② 그 시점의 `deploy/app/compose.yaml`을 `git show`로 VM에 복사(배포하는 이미지와 실행 구성을 같은 시점으로 맞춘다) ③ VM의 현재 `APP_TAG`를 출력 ④ `.env`의 `APP_TAG`를 바꾸고 `docker compose pull app && docker compose up -d` ⑤ `http://127.0.0.1:8080/actuator/health`가 `UP`이 될 때까지 최대 120초 기다림 ⑥ 실패하면 앱 로그 마지막 50줄과 되돌리기 명령(`scripts/deploy.sh <이전 태그>`)을 출력하고 0이 아닌 값으로 끝난다.
   - 실패해도 자동으로 되돌리지 않는다. 마이그레이션이 이미 적용됐을 수 있어 이전 이미지가 뜨는지는 사람이 보고 판단한다.
   - db VM은 배포 대상이 아니다. DB 구성이 바뀌면 `docs/ops/deploy.md`의 절차로 직접 반영한다.
   - 대안: GitHub Actions에서 배포. 집 네트워크라 러너가 들어올 수 없고, 셀프 호스트 러너를 운영해야 한다.
9. **프론트는 Cloudflare Pages의 Git 연동으로 `main`에서 빌드한다.**
   - 프로덕션 브랜치 `main`, 루트 `frontend`, 빌드 `npm run build`, 출력 `dist`, 환경 변수 `VITE_API_BASE_URL=https://api.govbenefit.kr`, 커스텀 도메인 `govbenefit.kr`. `main`은 배포된 상태라는 규칙(AGENTS.md)과 맞는다.
   - 다른 브랜치의 미리보기 배포는 끈다. `*.pages.dev`에서는 쿠키와 CORS가 맞지 않아 쓸 수 없다(DEC-APP).
   - `404.html`이 없으면 Pages가 없는 경로에 `index.html`을 돌려준다(결정 0029의 조건). 이 동작은 작성할 때 Cloudflare 문서로 다시 확인해 날짜와 함께 적는다.
   - 한계: 프론트는 `main` 머지로 바로 빌드되고 API는 그 뒤 태그 push로 배포되어, 프론트가 몇 분 먼저 나간다. (구현 중 변경) 처음에는 API를 먼저 배포하는 순서를 적으려 했으나 Pages 자동 빌드에서는 순서를 정할 수 없다. 새 API가 있어야 동작하는 화면 변경은 API를 먼저 릴리스하라고 문서에 적는다.
10. **비회원 조건 비교 목록에 Cloudflare 요청량 제한 규칙을 둔다.**
    - 대상: `api.govbenefit.kr`의 `POST /api/matches`. 넘으면 10초 차단. 측정 전이라 넉넉하게 잡고 부하 테스트 뒤 조정한다.
    - (구현 중 변경) 기준을 IP당 10초에 20회에서 40회로 바꿨다. 2026-10-28 Cloudflare 문서로 확인한 무료 플랜은 규칙 1개, 기준 IP, 기간·차단 10초, 조건 필드는 경로뿐이라 메서드로 나눌 수 없다. `CorsConfig`에 사전 요청 캐시 시간이 없어 조회마다 `OPTIONS`가 붙을 수 있으므로, 실제 조회 기준 약 20회가 되게 했다. 기준 값은 `docs/ops/deploy.md`에 둔다.
    - 한계: Cloudflare의 차단 응답에는 CORS 헤더가 없어 브라우저에서는 네트워크 오류로 보이고, 프론트는 일반 오류로 표시한다. 로그인한 사용자의 조건 비교(`GET /api/me/matches`)는 제한하지 않는다.
    - 대안: 앱 안에서 제한. 코드와 저장소(메모리·DB)가 필요하고, 요청이 앱까지 들어온 뒤에야 거절한다.
11. **첫 수집은 `collect` 프로필로 한 번 실행한다.**
    - 첫 배포 뒤 DB가 비어 있다. `docker compose run --rm -e SPRING_PROFILES_ACTIVE=collect app`으로 수집을 한 번 돌리고, 끝났다는 로그를 보면 멈춘다. 코드는 바꾸지 않는다.
    - 04:00까지 기다리는 방법도 있지만, 그동안 검색 결과가 비어 확인이 늦어진다.
12. **VM 사양과 Proxmox 설정은 `docs/ops/proxmox-vm.md`에 둔다.**
    - app VM: 4 vCPU(물리 2코어) / 4GB. db VM: 2 vCPU(물리 1코어) / 4GB. Ubuntu 24.04 클라우드 이미지와 cloud-init(사용자·SSH 키)으로 만든다.
    - CPU type `host`, 물리 코어 단위 affinity(스레드 짝은 `lscpu -e=CPU,CORE`로 확인), ballooning 끔, 디스크 VirtIO SCSI single + `iothread=1` + `cache=none`, NIC VirtIO.
    - ZFS 스토리지라면 ARC 상한을 거는 절차를 함께 적는다.

## 단계별 구현 계획

1. **플랜 상태**: 착수할 때 이 파일의 `status`를 `in-progress`로 바꾼다.
2. **결정 문서** `docs/decisions/0034-proxmox-deploy.md`(설계 2~9 요지). `docs/decisions/README.md` 목록에 추가한다.
3. **제품 문서**
   - `docs/product/development.md`
     - "결정한 사항" 표에 `DEC-OPS(배포)` 행을 추가한다. 선택: Proxmox 단일 호스트, app·db VM 분리, VM 안 MySQL 컨테이너, Tunnel, 프론트 Pages(운영)·nginx VM(내부 테스트), Compose, SSH 배포 스크립트, VM 생성은 문서 절차, 백업·경보는 첫 공개에서 제외. 이유와 한계(설계 1, 단일 호스트라 전원·네트워크가 끊기면 서비스가 멈춤).
     - 표 위 변경 기록에 "DEC-OPS 중 배포 구조를 2026-10-xx에 정했다. 기록은 #87에서 했다."를 더한다.
     - "아직 결정할 사항"의 `DEC-OPS` 행을 남은 항목(관측 도구, 백업 방식, 성능 목표)으로 줄인다.
     - O2 행의 "배포 위치는 DEC-OPS에서 정한다"를 결정 결과로, O3 행을 "기본 지표는 부하 테스트에서, 백업·경보는 P1"로 고친다.
     - `DEC-AUTH(공개 범위)`의 "비회원 요청량 제한은 운영 배포(O2·O3)에서 다룬다"를 결과(#87, Cloudflare 규칙)로 고친다.
   - `docs/product/requirements.md`: `REQ-OPS-01`·`REQ-OPS-02`(설계 1). `REQ-OPS-01` 끝의 "배포 대상·환경 수·인프라 코드 도구는 `DEC-OPS`에서 정한다"를 결정 링크로 바꾼다.
4. **actuator와 클라이언트 IP**
   - `backend/build.gradle`: `spring-boot-starter-actuator`, `bootJar { archiveFileName = 'app.jar' }`.
   - `application.yaml`: `management.endpoints.web.exposure.include: health`, `server.forward-headers-strategy: native`, `server.tomcat.remoteip.remote-ip-header: CF-Connecting-IP`(설계 5·6, 주석에 이유).
   - `SecurityConfig.PUBLIC_PATHS`에 `/actuator/health`.
5. **테스트**
   - `backend/src/test/java/kr/govbenefit/common/HealthEndpointTest.java`: 토큰 없이 `GET /actuator/health`가 200과 `UP`이고 세부 정보가 없다. `GET /actuator/env`는 404다(처리할 API가 없는 경로는 토큰과 관계없이 404, `AccessTokenAuthTest`).
   - `backend/src/test/java/kr/govbenefit/common/ClientIpTest.java`: `@SpringBootTest(webEnvironment = RANDOM_PORT)`로 실제 Tomcat을 띄운다. 테스트 설정의 필터가 `request.getRemoteAddr()`를 기록하고, `CF-Connecting-IP: 203.0.113.7`을 붙여 `/actuator/health`를 부르면 그 값이, 헤더가 없으면 `127.0.0.1`이 기록된다. MockMvc는 Tomcat 밸브를 거치지 않으므로 쓰지 않는다.
6. **이미지**
   - `backend/Dockerfile`, `backend/.dockerignore`(설계 3).
   - `.github/workflows/image.yml`(설계 4): checkout → `setup-java` 21 → `setup-gradle` → 태그면 버전 검사 → `./gradlew bootJar` → `docker/login-action`(GHCR, `GITHUB_TOKEN`, `packages: write`) → `docker/build-push-action`(`backend/` 컨텍스트, 이미지에 `org.opencontainers.image.source`·`version`·`revision` 라벨).
7. **VM 실행 구성** `deploy/app/compose.yaml`, `deploy/app/app.env.example`, `deploy/db/compose.yaml`, `deploy/db/.env.example`(설계 7). `.gitignore`에 `deploy/**/.env`, `deploy/**/app.env`를 더한다.
8. **배포 스크립트** `scripts/deploy.sh`(설계 8). `scripts/worktree.sh`처럼 `set -euo pipefail`, 사용법 출력, 한국어 메시지.
9. **운영 문서**
   - `docs/ops/proxmox-vm.md`(설계 12): 호스트 준비(코어 짝 확인, ZFS ARC), `vmbr1` 만들기, 템플릿 만들기, app·db VM 복제와 설정, Docker 설치, `/opt/gov-benefit/{app,db}` 디렉터리와 비밀값 파일(권한 600).
   - `docs/ops/deploy.md`
     - 처음 한 번: db 기동 → GHCR 이미지 공개 설정 → Tunnel 만들기와 토큰 넣기 → `sha-*` 이미지로 확인 배포 → Pages 연결 → 요청량 제한 규칙 → 첫 수집(설계 11).
     - 릴리스할 때: 태그 push → 이미지 워크플로 확인 → `scripts/deploy.sh vX.Y.Z` → `main` 머지로 Pages가 빌드됐는지 확인. 프론트가 먼저 나가는 한계를 적는다(설계 9).
     - 되돌리기: `scripts/deploy.sh <직전 태그>`. 직전 릴리스 이후 마이그레이션이 있었으면 이전 이미지가 새 스키마로 뜨는지 먼저 확인한다.
     - DB 구성 변경 절차.
   - `docs/ops/mail-domain.md`의 "배포 환경에 넣는 값"이 있으면 `deploy/app/app.env.example`을 가리키게 고친다.
10. **검증과 PR**: `./gradlew test`가 통과하면 마지막 커밋에서 `status: done`으로 바꾸고 PR을 올린다. (구현 중 변경) `workflow_dispatch`가 `develop`에 워크플로가 있어야 실행되므로, 인프라 확인은 머지 뒤에 한다. PR 본문은 이슈를 닫지 않게 `Refs #87`로 적고, 남은 완료 조건을 남은 항목으로 적는다.
11. **[사용자] 인프라 작업과 확인(머지 뒤)**: `docs/ops/proxmox-vm.md`대로 VM 생성, `docs/ops/deploy.md`의 "처음 한 번" 1~7을 `develop`의 `sha-*` 이미지로 진행해 완료 조건 2·5~10을 확인한다. 8(Pages)과 완료 조건 1·4는 첫 릴리스에서, 3은 다음 릴리스에서 확인하고 이슈를 닫는다.

## 완료 조건 매핑

| 이슈 완료 조건 | 충족 방법 |
| --- | --- |
| 릴리스 태그 `vX.Y.Z`를 push하면 같은 버전으로 식별되는 이미지가 레지스트리에 올라간다. | 단계 6. 첫 릴리스 태그 push 때 GHCR에 `v0.1.0`이 생기는지 확인한다. `workflow_dispatch`로 같은 워크플로를 먼저 확인한다 |
| 배포 스크립트에 태그를 주면 app VM의 앱이 그 버전으로 바뀌고, `https://api.govbenefit.kr`의 상태 확인이 성공한다. | 단계 4·7·8. `sha-*`로 먼저, 첫 릴리스에서 `v0.1.0`으로 확인 |
| 직전 태그로 배포 스크립트를 실행하면 이전 버전으로 돌아간다. 이것을 실제로 실행해 확인한다. | 단계 8·9. 다음 릴리스를 배포할 때 직전 태그로 되돌렸다가 다시 올려 확인한다 |
| `https://govbenefit.kr`에서 검색·상세, 가입 인증 메일 수신·가입·로그인, 새로고침 후 로그인 유지·로그아웃, 없는 경로 직접 입력이 동작한다. | 단계 9·11(Pages, Tunnel), 첫 릴리스 뒤 수동 확인 |
| 운영 DB에 수집이 실행되어 혜택이 검색되고, 예약 수집이 다음 실행 시각에 돈다. | 설계 11, 다음 날 04:00 수집 실행 기록 확인 |
| DB 비밀번호, 공공 API 인증키, Resend API 키가 이미지와 저장소에 없다. | 단계 6·7. 비밀값은 VM의 `app.env`·`.env`만, `.gitignore`. `docker history`와 이미지 파일 목록 확인 |
| MySQL에는 app VM에서만 접속할 수 있고, app VM의 API 포트에는 외부에서 Tunnel을 거치지 않고 접속할 수 없다. | 설계 7. LAN의 다른 기기에서 `10.10.0.12:3306`, app VM LAN 주소의 `8080` 접속이 실패하는지 확인 |
| 서로 다른 클라이언트의 요청이 앱에서 각자의 IP로 식별된다. | 단계 4·5 `ClientIpTest`. 운영에서는 서로 다른 네트워크(예: 휴대폰 데이터)에서 요청해 앱 로그로 확인 |
| 비회원 조건 비교 목록 요청이 정한 기준을 넘으면 거절된다. | 설계 10. 10초 안에 21회 이상 보내 429를 확인 |
| `docs/ops/`의 절차만 보고 app VM과 db VM을 다시 만들어 배포할 수 있다. | 단계 9·11. VM을 문서대로 만들어 배포까지 마친 것으로 확인 |
| `development.md`에 `DEC-OPS(배포)` 결정이 기록되고, `REQ-OPS-01`·`REQ-OPS-02`와 O2·O3 행이 조정된 내용과 맞다. | 단계 2·3 |

## 검증 명령

```bash
cd backend && ./gradlew test
cd backend && ./gradlew bootJar && docker build -t gov-benefit-helper:local .
docker run --rm gov-benefit-helper:local java -XshowSettings:properties -version 2>&1 | grep user.timezone
bash -n scripts/deploy.sh
docker compose -f deploy/app/compose.yaml config   # APP_TAG, DB_BIND 등은 예시 값으로
docker compose -f deploy/db/compose.yaml config
```

운영 확인은 `docs/ops/deploy.md`의 절차를 따른다.

## 결정 필요

없음. GHCR 이미지 공개 여부는 저장소를 공개하기로 해 공개로 정했다(2026-10-28, 설계 4).
