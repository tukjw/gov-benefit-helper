status: done

# 구현 계획 — #14 PR 자동 테스트와 머지 보호 적용

브랜치: `chore/14-ci-test-merge-guard` (develop `c7fff84` 기준)

## 목표 및 범위

- 목표: `develop`·`main` 대상 PR마다 Testcontainers MySQL을 포함한 `./gradlew test`를 자동으로 실행하고, 통과하지 않은 PR은 두 브랜치에 머지할 수 없게 한다(`REQ-OPS-01` P0 첫 조건, 운영 작업 O1).
- 포함: GitHub Actions 워크플로, `develop`·`main` ruleset 정의 파일과 적용 명령, CI 결과가 머지 조건이 되면서 바뀌는 릴리스·hotfix 절차의 지침 반영.
- 제외: 컨테이너 이미지, 배포(O2), CI 소요 시간 측정·단축(`REQ-OPS-01` P1), 실패 테스트의 PR 주석 리포트, Terraform 같은 IaC 도구(`DEC-OPS`).

## 확인된 사실과 제약

- `.github/`가 없고 `develop`·`main` 모두 보호 설정이 없다. 저장소는 private이며, 보호 API가 요금제 업그레이드 오류가 아니라 `Branch not protected`를 돌려주므로 ruleset을 쓸 수 있는 것으로 보인다. 적용할 때 확인한다.
- 테스트는 `@ServiceConnection`으로 붙는 `MySQLContainer(mysql:8.4)`를 쓰고 비밀값이 없다. `disabledWithoutDocker`를 쓰지 않으므로 Docker가 없으면 컨텍스트 로딩에서 실패한다.
- Gradle 9.7.1은 테스트 소스가 있는데 실행된 테스트가 없으면 `test` 태스크를 실패시킨다.
- Testcontainers는 설정된 Docker 탐색 방식이 실패하면 다른 방식을 차례로 시도한다. 이 머신에서 `DOCKER_HOST`를 잘못된 주소로 바꿔도 기본 소켓으로 연결되어 테스트가 통과했다(확인). Docker 부재 검증은 소켓이 없는 컨테이너에서 한다.
- GitHub-hosted `ubuntu` 러너에는 Docker가 있다. `pull_request` 이벤트는 head와 base를 합친 머지 ref를 체크아웃하므로 테스트는 머지 결과 기준으로 실행된다.
- 저장소 머지 설정은 squash와 merge commit 허용, rebase 비허용, 머지 후 head 브랜치 자동 삭제다. 지금까지 PR은 모두 squash로 머지했다.
- 기존 지침은 릴리스 버전 bump 커밋과 hotfix 역머지를 `develop`에 직접 push한다. PR 필수 규칙을 bypass 없이 걸면 두 절차가 모두 막힌다.
- 필수 체크의 "최신 base 반영(strict)"은 base 브랜치 끝 커밋이 head의 조상이어야 통과한다. PR 화면의 "Update branch"는 base를 head 브랜치에 병합해 push한다.

## 설계 결정

1. **GitHub-hosted 러너에서 Testcontainers를 그대로 쓴다.** `services: mysql`은 Testcontainers 검증 규칙과 어긋나고 CI 전용 DB 설정이 생기므로 제외했다. self-hosted 러너는 운영 부담 때문에 제외했다.
2. **워크플로는 `develop`·`main` 대상 PR마다 경로 필터 없이 항상 실행한다.** 경로 필터를 쓰면 문서 PR에서 필수 체크가 보고되지 않아 머지할 수 없게 된다. `if:`로 건너뛴 job은 필수 체크에서 통과로 취급되므로 변경 감지 방식도 제외했다.
3. **Docker가 없어 skip되는 경우는 CI에서 JUnit XML의 `<skipped` 요소로 잡는다.** 현재 코드는 실패하지만, 나중에 `disabledWithoutDocker`나 `Assumptions`가 들어와도 신호 없이 통과하지 않게 한다. 일부러 skip하는 테스트가 필요해지면 그때 예외 규칙을 정한다.
4. **머지 보호는 bypass 없는 repository ruleset으로 걸고, 정의 파일을 `.github/rulesets/`에 둔다.** 소유자도 우회할 수 없게 한다. 필수 체크의 출처는 GitHub Actions(`integration_id` 15368)로 고정한다. 적용은 `gh api` 명령으로 사용자가 한다.
5. **ruleset은 브랜치마다 하나씩 두고, 허용 머지 방식과 strict 여부를 다르게 한다.**
   - `develop`: squash와 merge commit 허용, strict 켬. 일반 PR은 squash, 역머지 PR만 merge commit이다.
   - `main`: merge commit만 허용, strict 끔. 릴리스 PR을 squash하면 `develop`과 조상 관계가 끊긴다. `main`에 strict를 켜면 릴리스 머지 커밋이 `develop`에 없어 다음 릴리스 PR이 매번 out of date가 된다. "Update branch"는 `develop`에 직접 push하므로 막히고, 결국 릴리스마다 역머지가 필요해진다. 테스트는 머지 ref 기준으로 돌기 때문에 strict 없이도 머지 결과를 검증한다.
6. **버전 bump는 `develop` 대상 PR로 먼저 머지한다.** 브랜치는 `release/vX.Y.Z`, 제목은 `release: vX.Y.Z 버전 반영`이다. 직접 커밋 예외를 없앤다.
7. **hotfix 역머지는 `develop`에서 딴 중간 브랜치로 PR을 올린다.** `main`을 head로 두면 strict 때문에 out of date가 되고, "Update branch"는 `main`에 push하므로 막힌다. `hotfix/<이슈번호>-<슬러그>-backmerge`를 `develop`에서 만들어 `main`을 병합하고 merge commit으로 머지한다. squash로 잘못 머지하면 `main`이 `develop`의 조상이 되지 않아 역머지가 반영되지 않는다.
8. **액션은 공식 액션(`actions/*`, `gradle/actions`)만 쓰고 메이저 태그로 고정한다.** SHA 고정은 갱신 자동화가 없는 지금은 관리 부담이 크다. 워크플로 권한은 `contents: read`만 준다. `setup-gradle`의 wrapper 검증을 그대로 쓴다.

## 단계별 구현 계획

1. `.github/workflows/ci.yml`을 추가한다.
   - 트리거: `pull_request`, `branches: [develop, main]`. 권한: `contents: read`. PR 번호 기준 `concurrency`로 이전 실행을 취소한다.
   - job id와 이름은 `test`다. 필수 체크 이름이 된다. `ubuntu-24.04`에서 `timeout-minutes: 20`으로 실행한다.
   - 단계: `actions/checkout@v7` → `actions/setup-java@v6`(temurin 21) → `gradle/actions/setup-gradle@v6` → `./gradlew test` → skip 검사 → 실패 시 `actions/upload-artifact@v7`로 `backend/build/reports/tests/test`를 올린다.
   - skip 검사: `backend/build/test-results/test/*.xml`이 없거나 `<skipped` 요소가 있으면 실패한다.
2. `.github/rulesets/develop.json`, `.github/rulesets/main.json`을 추가한다. 규칙은 `deletion`, `non_fast_forward`, `pull_request`(승인 0명, 허용 머지 방식), `required_status_checks`(`test`, strict)다. `bypass_actors`는 비운다.
3. `.github/rulesets/README.md`에 적용·갱신·확인용 `gh api` 명령을 적는다.
4. `AGENTS.md`를 고친다.
   - 검증 절: `develop`·`main` 머지는 CI `test` 통과가 조건이고 ruleset이 강제한다는 점, ruleset 파일 위치.
   - 분기 표에 머지 방식 열을 추가한다(일반 squash, hotfix·릴리스·역머지 merge commit).
   - hotfix 절: 역머지를 중간 브랜치 PR로 한다.
   - 릴리스 절: 버전 bump PR을 먼저 `develop`에 머지한다.
   - 하지 말 것: `develop` 직접 커밋 예외를 삭제한다.
5. `.agents/skills/pr-reviewer/SKILL.md`의 base·제목 확인 문구에 버전 반영 PR과 역머지 PR을 추가한다.

## 완료 조건 매핑

- [ ] `develop` 대상 PR을 열면 테스트가 자동으로 실행되고, 결과가 PR에 표시된다 → 1단계. 이 이슈의 PR에서 `test` 체크로 확인
- [ ] 테스트가 Docker를 쓰지 못하면 skip되지 않고 실패로 처리된다 → 1단계 skip 검사. 로컬에서 Docker 접속을 끊고 `./gradlew test` 실패 확인
- [ ] 일부러 실패하는 테스트를 넣은 PR은 `develop`에 머지할 수 없다. 이 확인은 머지하지 않고 닫는다 → 2·3단계. ruleset 적용 후 검증용 PR로 확인
- [ ] `main` 대상 PR에도 같은 머지 보호가 적용된다 → 2단계. `gh api repos/{owner}/{repo}/rules/branches/main`으로 확인
- [ ] 워크플로가 인증키나 DB 비밀번호를 쓰지 않고, 로그에도 비밀값이 남지 않는다 → 1단계. `secrets.*` 참조 없음, `contents: read`

## 검증 명령

- `backend/`에서 `./gradlew test`
- Docker 소켓이 없는 컨테이너에서 `backend/` 복사본으로 `./gradlew test` 실행: `docker run --rm -v "<복사본>:/w" -w /w eclipse-temurin:21-jdk ./gradlew test --no-daemon`가 `Could not find a valid Docker environment`로 실패하고 `skipped=0`인지 확인
- skip 검사 스크립트를 정상 결과와 `<skipped/>`가 든 가짜 결과에 각각 실행
- `docker run --rm -v "$PWD:/repo" -w /repo rhysd/actionlint:latest`
- `grep -rn 'secrets\.' .github/` 결과 없음
- ruleset 적용 후 `gh api repos/{owner}/{repo}/rules/branches/develop`, `.../main`

## 결정 필요

없음
