# gov-benefit-helper 작업 지침

이 파일은 Codex와 Claude Code가 공통으로 읽는 저장소 지침입니다. `CLAUDE.md`는 이 파일의 심볼릭 링크이고, `.claude/skills`는 `.agents/skills`의 심볼릭 링크입니다. 스킬은 `.agents/skills/`에서만 수정합니다.

## 저장소 구성

- `backend/`: Spring Boot API. 도메인별 레이어드 패키지 구조 (`kr.govbenefit.<도메인>.{controller,service,repository,domain,dto}`). Gradle 명령은 `backend/`에서 실행합니다.
- `frontend/`: Next.js 웹(App Router, TypeScript). npm 명령은 `frontend/`에서 실행하고, Node 버전은 `frontend/.nvmrc`를 따릅니다.
- `docs/product/`: 서비스 기획, 요구사항(`REQ-*`), 외부 데이터, 개발 순서와 미정 사항(`DEC-*`). 문서 안내는 `docs/README.md`에 있습니다. 기능 범위나 동작을 판단할 때 먼저 확인합니다.
- `docs/plans/`: 이슈별 구현 플랜. 파일명은 `<이슈번호>-<브랜치 슬러그>.md`.
- `scripts/`: 저장소 보조 스크립트.
- `docker-compose.local.yml`: 로컬 개발용 MySQL.
- 이슈와 PR은 GitHub에 있습니다. CLI는 `gh`입니다.

## 로컬 실행

- `docker compose -f docker-compose.local.yml up -d`로 MySQL을 띄우고, `backend/`에서 `SPRING_PROFILES_ACTIVE=local ./gradlew bootRun`으로 실행합니다.
- API 문서 화면은 `local` 프로필로 실행했을 때만 `http://localhost:8080/swagger-ui.html`에서 열립니다.
- `local` 프로필의 DB 계정 기본값은 로컬 compose가 만드는 계정과 같으므로 `.env` 없이 실행됩니다. 값을 바꿀 때만 루트 `.env`(compose)와 `backend/.env`(앱)를 `.env.example`에서 복사해 씁니다.
- 프론트는 `frontend/`에서 `npm ci` 후 `npm run dev`로 실행하면 `http://localhost:3000`에서 열립니다. API 주소(`NEXT_PUBLIC_API_BASE_URL`)는 `.env.development`의 로컬 기본값을 쓰고, 바꿀 때만 `frontend/.env.example`을 `frontend/.env.local`로 복사해 씁니다.

## 플랜 기반 작업 규칙

- 이슈 번호를 받으면 먼저 `docs/plans/<이슈번호>-*.md`를 찾아 읽고, 플랜의 단계와 완료 조건을 그대로 따릅니다.
- 플랜 파일이 없으면 `docs/plans/README.md`의 생략 조건에 해당하는지 확인합니다. 해당하면 이슈의 완료 조건을 따르고, 해당하지 않으면 작업을 멈추고 사용자에게 알립니다.
- 플랜 형식과 `status` 값은 `docs/plans/README.md`를 따릅니다. 이슈 하나에 플랜 파일은 최대 하나이며, 후속 작업이 필요하면 새 이슈를 만듭니다.
- 플랜에 `결정 필요`로 남아 있는 항목은 임의로 정하지 말고 작업을 멈추고 사용자에게 확인합니다.
- 플랜에 없는 리팩터링이나 범위 확장을 끼워 넣지 않습니다. 필요하다고 판단되면 플랜 파일에 제안으로 적고 진행하지 않습니다.
- 작업을 시작하면 플랜 상단의 `status`를 `in-progress`로 바꾸고, PR을 올리기 직전 마지막 커밋에서 `done`으로 바꿉니다.

## 검증

- Java: `backend/`에서 `./gradlew test`가 통과해야 합니다.
- 프론트: `frontend/`에서 `npm run lint`, `npm run typecheck`, `npm run build`가 통과해야 합니다. 의존성을 바꾸면 `package-lock.json`을 함께 커밋합니다.
- `develop`·`main` 대상 PR은 CI(`.github/workflows/ci.yml`)의 `test`와 `frontend` 체크가 통과해야 머지할 수 있습니다. `test`는 건너뛴 테스트가 있어도 실패합니다. `frontend`는 프론트 검사 결과를 모은 체크로, `frontend/`나 `ci.yml`을 바꾸지 않은 PR에서는 검사 없이 통과합니다. 보호 규칙은 `.github/rulesets/`에 있고 관리자도 우회할 수 없습니다.
- 테스트는 Testcontainers로 실제 MySQL을 띄우므로 Docker가 필요합니다. Docker가 없으면 skip하지 않고 실패로 둡니다.
- MySQL 전용 동작과 Flyway 마이그레이션은 H2 같은 대체 DB가 아니라 Testcontainers MySQL로 검증합니다.
- 새 테스트는 외부 서비스에 의존하지 않아야 합니다. 외부 HTTP는 fake나 로컬 stub 서버로 대체합니다. 실제 외부 API에 요청하는 테스트가 필요하면 기본 `./gradlew test`에 포함되지 않도록 분리합니다.

## 브랜치와 커밋

- 브랜치 이름은 `<type>/<이슈번호>-<슬러그>` 형식입니다. 이슈에 대응하는 브랜치가 이미 만들어져 있으면 그 브랜치를 사용합니다.
- 커밋 메시지는 `<type>: <한국어 요약> (#<이슈번호>)` 형식입니다. 예: `fix: 로그인 실패 시 에러 메시지 개선 (#12)`.
- `type`은 이슈 라벨과 맞춥니다: `fix`, `feat`, `improve`, `refactor`, `docs`, `test`, `chore`, `hotfix`.
- 하나의 PR은 하나의 이슈만 다룹니다. PR 설명에는 플랜 파일 경로를 적고, 플랜을 생략했으면 `플랜: 없음`을 적습니다.
- 워크트리는 저장소 루트에서 `scripts/worktree.sh <브랜치명 | 이슈번호>`로 만듭니다. `git worktree add`를 직접 실행하지 않고, `git worktree remove`·`prune`은 사용자가 직접 합니다.

### 분기 원점과 PR base

`main`은 배포된 상태, `develop`은 통합 상태입니다. 저장소 기본 브랜치는 `develop`입니다.

| 작업 | 분기 원점 | PR base | 머지 방식 |
| --- | --- | --- | --- |
| 일반 이슈 | `develop` | `develop` | squash |
| hotfix | `main` | `main` | merge commit |
| hotfix 역머지 | `develop` | `develop` | merge commit |
| 릴리스 버전 반영 | `develop` | `develop` | squash |
| 릴리스 | `develop` (그대로 head) | `main` | merge commit |

`main`은 merge commit만 허용합니다. 역머지를 squash하면 `main`이 `develop`에 합쳐지지 않으므로 merge commit을 고릅니다.

### hotfix

- 배포된 `main`의 긴급 결함만 hotfix입니다. 판단 기준은 "지금 `develop`에 쌓인 변경을 함께 배포해도 되는가"입니다. 되면 일반 `fix` 이슈로 `develop`에, 안 되면 hotfix로 처리합니다.
- 이슈 라벨은 `type/hotfix`, 브랜치는 `hotfix/<이슈번호>-<슬러그>`, 커밋 type은 `hotfix`입니다. 유형 라벨은 하나만 붙이므로 `type/bug`와 함께 쓰지 않습니다.
- 절차: `main`으로 PR 머지 → 패치 버전 태그 `vX.Y.Z` → 곧바로 `develop`으로 역머지 PR 머지. 역머지를 빠뜨리면 다음 릴리스에서 수정이 되돌아가므로 같은 자리에서 끝냅니다.
- 역머지는 `develop`에서 `hotfix/<이슈번호>-<슬러그>-backmerge` 브랜치를 만들어 `origin/main`을 병합한 뒤 `develop`으로 PR을 올립니다. `main`을 그대로 head로 두면 최신 `develop`을 반영하라는 조건 때문에 머지할 수 없습니다. PR 제목은 `hotfix: vX.Y.Z 역머지 (#<이슈번호>)`입니다.
- PR 머지 이후의 태그, 역머지, push는 사용자가 직접 합니다. 에이전트는 실행할 명령만 제시합니다.

### 릴리스

- 장기 release 브랜치는 두지 않습니다. 버전 반영 PR을 `develop`에 머지한 뒤 `develop → main` PR로 릴리스합니다.
- 버전 반영 PR은 `develop`에서 `release/vX.Y.Z` 브랜치를 만들어 `backend/build.gradle`의 `version`을 `X.Y.Z`로 맞추고, 제목 `release: vX.Y.Z 버전 반영`으로 `develop`에 올립니다. SNAPSHOT 접미사는 쓰지 않습니다.
- 릴리스 PR 제목은 `release: vX.Y.Z`입니다. `release`는 대응하는 이슈 라벨이 없는 유일한 커밋 type이며 이슈 번호를 붙이지 않습니다.
- 머지 커밋에 `git tag -a vX.Y.Z -m "vX.Y.Z"`를 붙이고 push합니다. 버전의 단일 소스는 git 태그입니다.
- PR 머지 이후의 태그와 push는 사용자가 직접 합니다. 에이전트는 실행할 명령만 제시합니다.

## 하지 말 것

- `.env` 내용, DB 자격 증명, API 키를 코드나 문서에 새로 하드코딩하지 않습니다. 로컬 compose 전용 기본 계정(`docker-compose.local.yml`, `application-local.yaml`)만 예외입니다.
- 원격 브랜치 삭제, force push, `main`·`develop` 직접 커밋을 하지 않습니다. 모든 변경은 PR로 머지합니다.
