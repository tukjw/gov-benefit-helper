# 0034. Proxmox 단일 호스트 배포 구조

- 상태: 대체됨 → [0036](0036-proxmox-deploy-and-metrics.md)
- 날짜: 2026-10-28
- 출처: #87, [플랜](../plans/87-proxmox-deploy.md)

## 배경

첫 공개(2차 MVP)를 앞두고 운영 환경이 없었다. `DEC-OPS`의 배포 대상·서버 수·인프라 코드 도구·컨테이너 실행 방식·프론트 배포 위치가 정해지지 않았다. #87에서 운영을 개인 Proxmox 호스트(Ryzen 7 8845HS, RAM 32GB) 한 대에 올리고, API는 Cloudflare Tunnel로, 프론트는 Cloudflare Pages로 열기로 정했다. 이후 배포, 부하 테스트, 운영 작업이 따라야 할 구조와 설정 위치를 정한다.

## 결정

Proxmox 호스트 한 대에 app VM과 db VM을 두고, 각 VM에서 Docker Compose로 컨테이너를 띄운다. 앱 이미지는 릴리스 태그로 GHCR에 올리고, 로컬에서 SSH로 실행하는 `scripts/deploy.sh <태그>`로 배포하고 되돌린다. VM 안의 실행 구성은 저장소 `deploy/`에 두고, VM 생성은 문서 절차로 한다.

- 이미지: CI가 `./gradlew bootJar`로 만든 `app.jar`를 `backend/Dockerfile`(`eclipse-temurin:21-jre-noble`, 비루트 사용자)이 담는다. `JAVA_TOOL_OPTIONS`로 힙을 컨테이너 메모리의 75%로, JVM 기본 시간대를 `Asia/Seoul`로 둔다(0002).
- 이미지 워크플로(`.github/workflows/image.yml`): 태그 `vX.Y.Z`를 push하면 `backend/build.gradle`의 `version`과 같은지 확인하고 `ghcr.io/tukjw/gov-benefit-helper:vX.Y.Z`를 올린다. 수동 실행은 `sha-<커밋 7자리>`를 올린다. 운영 배포는 `v*` 태그로만 한다. `latest`는 두지 않는다. 이미지는 공개다.
- app VM(`deploy/app/compose.yaml`): `app`과 `cloudflared`. 앱 포트는 VM의 `127.0.0.1:8080`에만 열고, 외부 요청은 cloudflared가 Compose 네트워크로 전달한다. 비밀값은 VM의 `app.env`·`cloudflared.env`에만 두고, Compose 치환용 `.env`에는 배포 스크립트가 적는 `APP_TAG`만 둔다.
- db VM(`deploy/db/compose.yaml`): `mysql:8.4`. 로컬과 같은 문자 집합·정렬·시간대(0001·0002)를 쓰고, 물리 포트가 없는 내부 브리지(`vmbr1`)의 주소에만 연다.
- 배포 스크립트: 배포할 태그 시점의 `deploy/app/compose.yaml`을 VM에 복사하고, 이미지를 받은 뒤에 태그를 바꿔 교체하고, `/actuator/health`가 `UP`이 될 때까지 기다린다. 실패해도 자동으로 되돌리지 않는다.
- 상태 확인: actuator는 `health`만 노출하고 `SecurityConfig`의 공개 경로에 둔다. 세부 정보는 보이지 않는다.
- 클라이언트 IP: Tomcat 원격 IP 처리로 `CF-Connecting-IP`를 요청자 주소(`getRemoteAddr`)로 쓴다. 신뢰하는 프록시는 기본값(사설 대역)이다.
- 프론트: Cloudflare Pages가 `main`에서 `frontend/`를 빌드해 `govbenefit.kr`에 둔다. `404.html`이 없어 없는 경로에 루트를 돌려준다(0029).
- 비회원 조건 비교 목록: Cloudflare 요청량 제한 규칙으로 경로 `/api/matches`를 IP 기준으로 제한한다. 기준 값은 [`docs/ops/deploy.md`](../ops/deploy.md)에 둔다.

## 근거

- **VM 두 대**: 한 VM에 앱과 DB를 두는 쪽이 단순하지만, 나누면 앱만 늘리는 실험을 할 수 있고 측정할 때 앱과 DB의 자원 사용을 구분할 수 있다. VM마다 컨테이너가 1~2개라 오케스트레이션은 쓰지 않는다.
- **VM 생성은 문서 절차**: 호스트가 한 대이고 VM을 다시 만들 일이 드물어, Terraform이나 `qm` 스크립트를 유지하는 비용이 얻는 것보다 크다. 다시 만들 때 기억에 남지 않는 것은 VM 안에서 무엇을 어떻게 띄웠는지라서, 그것만 저장소에 둔다.
- **SSH 스크립트 배포**: 집 네트워크라 GitHub Actions 러너가 들어올 수 없다. 셀프 호스트 러너는 운영할 것이 하나 늘어난다.
- **실행 구성을 태그 시점으로 복사**: 되돌릴 때 이미지와 실행 구성이 어긋나지 않는다.
- **자동으로 되돌리지 않음**: 새 이미지가 마이그레이션을 이미 적용했으면 이전 이미지가 새 스키마로 뜨지 않을 수 있다. 무엇으로 되돌릴지는 사람이 보고 정한다.
- **CI에서 jar를 만든 뒤 담기**: Dockerfile 안에서 Gradle을 돌리면 CI의 Gradle 캐시를 따로 챙겨야 한다. `bootBuildImage`는 Dockerfile이 없지만 JVM 옵션과 시간대를 확인하기 어렵다.
- **태그와 버전 대조**: 버전 반영 PR(AGENTS.md의 릴리스 절차)을 빠뜨린 태그가 다른 버전의 이미지를 만들지 않게 한다.
- **`sha-*` 이미지**: 첫 릴리스 전에 VM, Tunnel, 비밀값을 끝까지 확인할 수 있다. `workflow_dispatch`는 기본 브랜치(`develop`)에 워크플로가 있어야 실행된다.
- **`CF-Connecting-IP`**: Cloudflare가 값을 덮어써 전달하고 값이 하나다. `X-Forwarded-For`는 목록에서 몇 번째를 믿을지 정해야 한다. 앱 포트가 VM 안에서만 열려 있어 이 헤더를 보낼 수 있는 곳은 cloudflared와 VM 자신뿐이다. Tomcat이 처리하므로 이후 IP를 쓰는 코드(#52 등)는 `getRemoteAddr()`만 읽으면 된다.
- **cloudflared 버전을 고정하지 않음**: Cloudflare는 1년이 지난 cloudflared를 지원하지 않는다. 배포 스크립트는 `app`만 받으므로 배포할 때 cloudflared가 바뀌지는 않는다.
- **Tunnel 경로는 대시보드에**: 원격 관리 Tunnel은 토큰 하나로 동작한다. 설정 파일 방식은 자격 증명 파일을 VM에 따로 옮겨야 한다.
- **Pages를 `main`에서 빌드**: `main`은 배포된 상태라는 규칙과 맞는다. 미리보기 배포(`*.pages.dev`)는 쿠키와 CORS가 맞지 않아(DEC-APP) 끈다. 없는 경로에 루트를 돌려주는 동작은 2026-10-28에 Cloudflare 문서(Serving Pages)로 확인했다.
- **요청량 제한을 Cloudflare에서**: 앱 안에서 제한하면 코드와 카운터 저장소가 필요하고, 요청이 앱까지 들어온 뒤에야 거절한다. 2026-10-28 Cloudflare 문서(Rate limiting rules)로 확인한 무료 플랜 조건은 규칙 1개, 기준 IP, 기간 10초, 차단 10초, 조건에 쓸 수 있는 필드는 경로뿐이다. 메서드로 나눌 수 없어 CORS 사전 요청(OPTIONS)도 같은 경로로 세어진다. CORS 설정에 사전 요청 캐시 시간이 없어 조회마다 사전 요청이 붙을 수 있으므로, 처음 기준은 실제 조회 기준 약 20회가 되게 10초에 40회로 잡았다. 측정 전이라 넉넉하게 잡은 값이다.

## 적용 위치

- `backend/Dockerfile`, `backend/.dockerignore`, `backend/build.gradle`(`spring-boot-starter-actuator`, `bootJar`의 `app.jar`)
- `backend/src/main/resources/application.yaml`(`server.forward-headers-strategy`, `server.tomcat.remoteip.remote-ip-header`, `management.endpoints.web.exposure.include`)
- `backend/src/main/java/kr/govbenefit/common/security/SecurityConfig.java`(`/actuator/health`)
- `backend/src/test/java/kr/govbenefit/common/HealthEndpointTest.java`, `ClientIpTest.java`
- `.github/workflows/image.yml`
- `deploy/app/compose.yaml`, `deploy/app/app.env.example`, `deploy/app/cloudflared.env.example`, `deploy/db/compose.yaml`, `deploy/db/.env.example`
- `scripts/deploy.sh`
- 절차: [`docs/ops/proxmox-vm.md`](../ops/proxmox-vm.md), [`docs/ops/deploy.md`](../ops/deploy.md)

## 영향

- 앱에 새 환경 변수가 생기면 `deploy/app/app.env.example`과 VM의 `app.env`에 더한다.
- 클라이언트 IP가 필요한 코드는 `HttpServletRequest.getRemoteAddr()`를 쓴다. 헤더를 직접 읽지 않는다.
- actuator 엔드포인트를 더 열 때(지표 등)는 공개 경로에 넣을지, 내부에서만 접근하게 할지 함께 정한다.
- 운영 이미지의 JVM 옵션은 `backend/Dockerfile`의 `JAVA_TOOL_OPTIONS`에서 바꾼다.
- 마이그레이션은 직전 릴리스 이미지가 새 스키마에서도 뜨도록 쓰는 것이 좋다. 그렇지 않은 변경은 되돌리기 절차에 영향을 적는다.
- 요청량 제한의 기준 값은 부하 테스트 결과로 조정하고 `docs/ops/deploy.md`에서 고친다.
