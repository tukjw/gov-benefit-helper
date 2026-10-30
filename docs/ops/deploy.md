# 운영 배포와 되돌리기

API를 app VM에 배포하고 되돌리는 절차, 처음 한 번 하는 Cloudflare·DB 설정, 첫 수집 방법이다. 구조는 [결정 0036](../decisions/0036-proxmox-deploy-and-metrics.md)에 있다. VM은 [Proxmox VM 만들기](proxmox-vm.md)로 먼저 만든다.

Cloudflare 대시보드 화면은 2026-10-28 기준이다. 화면이 바뀌었으면 Cloudflare 문서를 따른다. 토큰, API 키, 비밀번호는 이 문서와 저장소에 적지 않는다.

## 구성 요약

| 무엇 | 어디에 | 어떻게 바뀌나 |
| --- | --- | --- |
| API | app VM의 `app` 컨테이너 (`ghcr.io/tukjw/gov-benefit-helper:<태그>`) | `scripts/deploy.sh <태그>` |
| 외부 노출 | app VM의 `cloudflared` → `api.govbenefit.kr` | Cloudflare 대시보드 |
| DB | db VM의 `mysql` 컨테이너 | 아래 "DB 구성 바꾸기" |
| 프론트 | Cloudflare Pages → `govbenefit.kr` | `main`에 머지되면 자동 빌드 |
| 비밀값 | app VM `/opt/gov-benefit/app/app.env`·`cloudflared.env`, db VM `/opt/gov-benefit/db/.env` (권한 600) | 직접 수정 |

## 처음 한 번

### 1. DB 띄우기

내 PC에서 db VM으로 실행 구성을 복사한다.

```bash
scp deploy/db/compose.yaml deploy/db/.env.example govbenefit-db:/opt/gov-benefit/db/
```

db VM에서 비밀값을 채우고 띄운다.

```bash
cd /opt/gov-benefit/db
mv .env.example .env && chmod 600 .env && vi .env   # 비밀번호 두 개를 채운다
docker compose up -d
docker compose ps   # STATUS가 healthy가 될 때까지 기다린다
```

### 2. 확인용 이미지 만들기

이미지 워크플로는 기본 브랜치(`develop`)에 있어야 수동으로 실행할 수 있다.

```bash
gh workflow run image.yml --ref develop
gh run watch   # 끝나면 요약에 이미지 태그(sha-<7자리>)가 나온다
```

처음 올린 패키지는 비공개다. GitHub의 저장소 → Packages → `gov-benefit-helper` → Package settings → Danger Zone → Change visibility에서 **Public**으로 바꾼다. 한 번만 하면 된다.

### 3. Tunnel 만들기

1. Cloudflare 대시보드 → Zero Trust → Networks → Tunnels → Create a tunnel → **Cloudflared**.
2. 이름은 `gov-benefit-app`으로 한다. 설치 안내 화면의 명령에 들어 있는 토큰(`--token` 뒤의 값)만 복사한다. 설치 명령은 실행하지 않는다. cloudflared는 컨테이너로 띄운다.
3. Public hostname을 추가한다.
   - Subdomain `api`, Domain `govbenefit.kr`, Path 비움
   - Service: Type `HTTP`, URL `app:8080`

### 4. 앱 비밀값 채우기

```bash
scp deploy/app/app.env.example deploy/app/cloudflared.env.example govbenefit-app:/opt/gov-benefit/app/
```

app VM에서:

```bash
cd /opt/gov-benefit/app
mv app.env.example app.env && mv cloudflared.env.example cloudflared.env
chmod 600 app.env cloudflared.env
vi app.env          # DB 계정은 db VM의 .env와 같게, JWT_SECRET은 openssl rand -base64 32
vi cloudflared.env  # 3에서 복사한 토큰
```

Resend 키와 발신 주소는 [메일 발신 도메인 인증](mail-domain.md)에서 만든다.

### 5. 확인 배포

내 PC의 저장소 루트에서, 2에서 만든 태그로 배포한다. 해당 커밋이 로컬에 있어야 한다(`git fetch origin`).

```bash
scripts/deploy.sh sha-<7자리>
curl -s https://api.govbenefit.kr/actuator/health   # {"status":"UP",...}
```

### 6. 첫 수집

DB가 비어 있으므로 수집을 한 번 돌린다. 예약 수집은 매일 04:00(Asia/Seoul)이다. app VM에서:

```bash
cd /opt/gov-benefit/app
docker compose run --rm -e SPRING_PROFILES_ACTIVE=collect app
```

로그에 `수집 완료 runId=...`(또는 `수집 부분 실패`)가 나오면 Ctrl+C로 멈춘다. 이 명령은 포트를 열지 않으므로 실행 중인 앱과 겹치지 않는다. 끝나면 `https://api.govbenefit.kr/api/benefits?size=1`에 혜택이 나오는지 확인한다.

### 7. 요청량 제한 규칙

Cloudflare 대시보드 → `govbenefit.kr` → Security → WAF → Rate limiting rules → Create rule.

| 항목 | 값 |
| --- | --- |
| Rule name | `guest-match-list` |
| If incoming requests match | URI Path equals `/api/matches` |
| With the same characteristics | IP |
| When rate exceeds | 40 requests / 10 seconds |
| Then take action | Block |
| For duration | 10 seconds |

- 대상은 비회원 조건 비교 목록(`POST /api/matches`)이다. 무료 플랜은 경로만 조건으로 쓸 수 있어, 브라우저가 먼저 보내는 CORS 사전 요청(`OPTIONS`)도 함께 센다. 40회는 실제 조회로 약 20회다.
- 기준 값은 부하 테스트 뒤 조정하고 이 표를 고친다. 처음 값은 2026-10-28에 정했다.
- 차단 응답에는 CORS 헤더가 없어 브라우저에서는 네트워크 오류로 보인다.

### 8. 프론트(Cloudflare Pages)

`main`에 `frontend/`가 생긴 뒤(첫 릴리스 PR 머지 뒤)에 연결한다.

1. 대시보드 → Workers & Pages → Create → Pages → Connect to Git → 이 저장소.
2. 빌드 설정
   - Production branch: `main`
   - Root directory: `frontend`
   - Framework preset: None, Build command: `npm run build`, Build output directory: `dist`
   - Environment variables: `VITE_API_BASE_URL` = `https://api.govbenefit.kr`
3. 첫 빌드 로그에서 Node 버전이 `frontend/.nvmrc`와 같은지 확인한다. 다르면 환경 변수 `NODE_VERSION`에 `.nvmrc`의 값을 넣고 다시 빌드한다.
4. Settings → Builds → Branch control에서 미리보기 배포(Preview branches)를 끈다. `*.pages.dev`에서는 쿠키와 CORS가 맞지 않아 쓸 수 없다.
5. Custom domains에 `govbenefit.kr`을 추가한다.

`frontend/public/`에 `404.html`이 없으므로 Pages는 없는 경로에 `index.html`을 돌려준다(결정 0029). `404.html`을 추가하지 않는다.

### 9. 확인

- `https://govbenefit.kr`: 검색·상세, 가입 인증 메일 수신·가입·로그인, 새로고침 후 로그인 유지, 로그아웃, 없는 경로(예: `/benefits/x/y`)를 주소창에 직접 입력
- LAN의 다른 기기에서 db VM의 LAN 주소로 3306에, app VM의 LAN 주소로 8080에 닿지 않는다.
- 비회원 조건 비교 목록을 10초 안에 40회 넘게 보내면 429다.
- 다음 날 04:00 뒤 `docker compose logs app | grep 수집`에 예약 수집 기록이 있다.

## 릴리스할 때

AGENTS.md의 릴리스 절차로 `develop → main` PR을 머지하고 태그를 push한 뒤:

1. GitHub Actions의 Image 워크플로가 성공했는지 본다. `backend/build.gradle`의 `version`과 태그가 다르면 실패한다.
2. 내 PC에서 배포한다.

   ```bash
   git fetch --tags origin
   scripts/deploy.sh vX.Y.Z
   ```

3. Pages는 `main` 머지로 이미 빌드를 시작했다. 대시보드에서 성공을 확인한다.

프론트가 API보다 몇 분 먼저 나간다. 새 API가 있어야 동작하는 화면 변경은 API를 먼저 릴리스한 뒤 다음 릴리스에 넣는다.

## 되돌리기

배포 스크립트는 실패를 알려 줄 뿐 자동으로 되돌리지 않는다. 출력된 직전 태그로 다시 배포한다.

```bash
scripts/deploy.sh <직전 태그>
```

- 두 태그 사이에 마이그레이션이 있었는지 먼저 본다. 있으면 직전 이미지가 새 스키마에서 뜨는지(엔티티 검증 실패 여부) 로그로 확인한다.

  ```bash
  git diff --stat <직전 태그> <현재 태그> -- backend/src/main/resources/db/migration
  ```

- 프론트는 Pages 대시보드 → Deployments에서 직전 배포의 Rollback을 누른다.

## 그 밖의 작업

- 앱 로그: app VM에서 `cd /opt/gov-benefit/app && docker compose logs -f app`
- cloudflared 갱신: 배포 스크립트는 `app`만 받는다. app VM에서 `docker compose pull cloudflared && docker compose up -d cloudflared`
- 비밀값 변경: 파일을 고친 뒤 `docker compose up -d`(바뀐 컨테이너만 다시 만든다)
- DB 구성 바꾸기: `deploy/db/compose.yaml`을 고쳐 머지한 뒤 `scp`로 db VM에 복사하고 `docker compose up -d`. 그동안 앱의 DB 요청은 실패한다.
