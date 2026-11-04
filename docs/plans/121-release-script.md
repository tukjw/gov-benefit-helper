status: done

# 구현 계획 — #121 릴리스 PR 생성부터 운영 배포까지 명령 하나로 실행

브랜치: `chore/121-release-script` (develop `de0826d` 기준)

## 목표 및 범위

- 목표: 내 PC에서 `scripts/release.sh vX.Y.Z [설명 파일]` 한 번으로 릴리스 PR 머지, Pages 빌드 확인, 태그 push, 이미지 확인, 운영 배포까지 끝낸다. 중간에 멈추면 같은 명령을 다시 실행해 이어 간다.
- 포함:
  - `scripts/release.sh`
  - `AGENTS.md`의 릴리스 절, `docs/ops/deploy.md`의 구성 요약과 "릴리스할 때"
  - 검증용 격리 환경. 저장소 밖 임시 디렉터리에 두고 커밋하지 않는다.
- 제외:
  - `scripts/deploy.sh`, 이미지 워크플로(`image.yml`), Pages 설정, hotfix 절차와 역머지. 바꾸지 않는다.
  - 릴리스 PR 설명 자동 생성, GitHub Release(릴리스 노트) 생성.
  - 자동 되돌리기. 결정 0039대로 사람이 판단한다.
  - 저장소 설정 변경(auto-merge 켜기 등).

## 확인된 사실과 제약

- 지금 릴리스 절차(`AGENTS.md` 릴리스 절, `docs/ops/deploy.md` "릴리스할 때")
  - `develop → main` PR(`release: vX.Y.Z`)을 merge commit으로 머지한다. 사용자가 머지 커밋에 `git tag -a vX.Y.Z -m "vX.Y.Z"`를 붙여 push한다. 이미지 워크플로 성공을 확인하고 `scripts/deploy.sh vX.Y.Z`를 실행한다. Pages는 `main` 머지로 이미 빌드를 시작한 상태다.
  - "PR 머지 이후의 태그와 push는 사용자가 직접 합니다. 에이전트는 실행할 명령만 제시합니다."
  - 프론트가 API보다 몇 분 먼저 나간다. 새 API가 있어야 동작하는 화면 변경은 API를 먼저 릴리스한 뒤 다음 릴리스에 넣는다.
- 저장소 설정과 보호 규칙
  - `merge_commit_title`은 `PR_TITLE`, `merge_commit_message`는 `PR_BODY`다. 그래서 `gh pr merge --merge`의 머지 커밋은 지금과 같은 `release: vX.Y.Z (#N)` 제목이 되고, 본문은 PR 설명이 된다(`eca8031` 확인). `allow_auto_merge`는 `false`다.
  - `main` 보호 규칙: PR 필수, 머지 방식은 merge commit만, 필수 체크 `test`·`frontend`(GitHub Actions), 최신 base 반영 조건 없음(`strict_required_status_checks_policy: false`), 우회 주체 없음. 태그 보호 규칙은 없다.
  - `develop` 보호 규칙에 삭제 금지가 있다. 그래도 머지할 때 `--delete-branch`를 쓰지 않는다.
- 필수 체크 조회(gh 2.103.3)
  - `gh pr checks --required`는 #102·#119에서 `test`만 보여 주고 `frontend`는 빠뜨린다. 그래서 쓸 수 없다.
  - `gh pr view <번호> --json statusCheckRollup`에서 이름이 `test`·`frontend`인 항목을 고르면 두 체크가 모두 나온다(#102 확인). 같은 이름이 여러 개면 `startedAt`이 가장 늦은 것을 쓴다.
  - #119에서 `test`는 2분 27초, `frontend`는 약 1분 걸렸다. job 제한 시간은 `test` 20분, `changes` 5분, `frontend-check` 10분, `frontend` 5분이다(`ci.yml`).
- Pages 빌드 결과
  - 머지 커밋에 Cloudflare 앱(`cloudflare-workers-and-pages`)의 체크 `Cloudflare Pages`가 붙는다. `repos/{owner}/{repo}/commits/<sha>/check-runs?check_name=Cloudflare%20Pages`로 조회한다. 기본 필터가 `latest`라 가장 최근 것 하나가 나온다.
  - v0.1.2: 머지 06:52:55Z, 체크 06:53:39Z(44초 뒤). `started_at`과 `completed_at`이 같아서 체크가 빌드가 끝날 때 생기는 것으로 보인다. 체크가 아직 없으면 진행 중으로 본다.
  - 프론트 변경이 없던 v0.1.1 머지 커밋에도 이 체크가 있다. Pages는 `main` 머지마다 빌드한다.
  - 대시보드에서 실패한 빌드를 다시 돌리면 같은 커밋에 체크가 새로 붙는지는 확인하지 못했다. 붙지 않으면 "스크립트 없이 릴리스할 때" 절차로 끝낸다.
- 이미지 빌드 결과
  - 사용자 자격 증명으로 태그를 push하면 이미지 워크플로가 돈다(v0.1.0부터 v0.1.2까지). 이 실행은 `headBranch`가 태그 이름이고 `event`가 `push`라서 `gh run list --workflow image.yml --branch vX.Y.Z --event push`로 찾는다.
  - 머지 커밋의 체크 `image`로도 볼 수 있지만, 같은 커밋으로 수동 실행한 `sha-*` 빌드도 같은 이름을 쓴다. 태그로 찾는 쪽이 정확하다.
  - v0.1.2: 태그 생성 06:53:19Z, 실행 06:53:26Z에서 06:54:05Z(39초). job 제한 시간은 15분이다.
- 릴리스 PR 조회: `gh pr list --base main --head develop --state all`로 찾으면 #91·#95·#102가 모두 `release: vX.Y.Z`이고 `MERGED`다. `release: v0.1.2 버전 반영 (#100)`은 base가 `develop`이고 제목도 달라 제목 완전 일치로 걸러진다.
- 배포 스크립트(`scripts/deploy.sh`)
  - 로컬 저장소에 태그 커밋이 있어야 한다. SSH 대상은 `DEPLOY_HOST`(기본 `govbenefit-app`)다. 저장소 루트의 git으로 실행 구성을 읽는다.
  - 이미지를 받은 뒤 `.env`의 `APP_TAG`를 먼저 바꾸고 나서 상태 확인을 기다린다. 그래서 실패한 뒤 같은 태그로 다시 실행하면 "직전 태그"가 그 태그 자신으로 나온다. 되돌리기 안내는 `release.sh`가 직전 릴리스 태그로 따로 출력한다.
  - 같은 태그로 다시 배포하면 `docker compose up -d`가 컨테이너를 다시 만들지 않는다. 그래서 다시 실행해도 안전하다.
  - 외부 확인은 `https://api.govbenefit.kr/api/profile/options`가 200인지로 한다(`docs/ops/deploy.md` 5). 외부의 `/actuator/health`는 404다.
- 도구
  - `bash`는 Homebrew 5.3(`/usr/bin/env bash`), `/bin/bash`는 3.2다. 기존 스크립트는 `#!/usr/bin/env bash`이고 `worktree.sh`는 bash 4 이상(`mapfile`)을 쓴다.
  - `shellcheck`는 설치돼 있지 않고 Docker는 있다. `gh`의 `--jq`가 있어 `jq`를 따로 쓰지 않는다. 시스템 `jq`(`/usr/bin/jq`)는 검증용 가짜 `gh`에서만 쓴다.
  - `GH_REPO=tukjw/gov-benefit-helper`를 주면 저장소 밖에서도 `gh api 'repos/{owner}/{repo}'`가 이 저장소로 풀린다.
- 이번 릴리스 상태: 최근 태그는 v0.1.2다. `develop`이 9커밋 앞서 있고 마이그레이션 V12에서 V15까지가 들어 있다. 새 API는 `ageConfirmed`가 없는 인증번호 요청과 가입을 400으로 거절하므로(#111) 프론트가 먼저 나가야 한다.

## 설계 결정

1. **중간에 멈추면 같은 명령으로 이어 간다. 상태는 GitHub과 git에서 읽는다(2026-11-04 사용자 선택).**
   - 상태 파일을 두지 않는다. 다른 워크트리나 PC에서 다시 실행해도 같은 판단을 한다.
   - 시작할 때 릴리스 PR과 태그를 보고 시작 단계를 정한다. Pages 확인부터는 다시 해도 결과가 같아서 매번 차례로 확인한다.

     | 릴리스 PR | 태그 `vX.Y.Z` | 동작 |
     | --- | --- | --- |
     | 없음 | 없음 | 설명 파일이 있어야 한다. PR 생성부터 |
     | 열림 | 없음 | 필수 체크 대기부터 |
     | 머지됨 | 없음 | Pages 확인부터. 확인 뒤 태그를 만든다 |
     | 머지됨 | 있음. 머지 커밋을 가리키고 가장 최근 릴리스 태그다 | Pages 확인부터. 태그는 만들지 않고, 원격에 없을 때만 push한다 |
     | 그 밖 | 그 밖 | 멈춘다 |

   - 이미 있는 태그로 이어 갈 수 있는 것은 가장 최근 릴리스 태그뿐이다. 이전 태그로 실행하면 사실상 되돌리기가 되는데, 되돌리기는 `scripts/deploy.sh`로 한다.
   - 닫히고 머지되지 않은 같은 제목의 PR은 없는 것으로 본다. 머지된 PR과 열린 PR이 함께 있으면 머지된 PR을 쓴다.
   - 제외한 대안: 남은 명령을 출력하고 끝내기. 스크립트는 단순하지만 실패한 뒤에 여러 줄을 손으로 입력해야 한다.
2. **시작할 때 한 번 확인받는다(2026-11-04 사용자 선택).**
   - 보여 줄 것: 버전, 직전 릴리스 태그, 릴리스 PR(주소와 상태, 또는 새로 만들 설명 파일), 시작 단계, 포함 커밋(`git log --oneline --no-merges <직전 태그>..<대상>`), 추가된 마이그레이션 파일. 프론트가 먼저 나간다는 안내도 함께 보여 준다.
   - `<대상>`은 머지됐으면 머지 커밋, 열려 있으면 PR head, 새로 만들면 `origin/develop`이다.
   - 표준 입력에서 `y`나 `Y`를 받아야 진행한다. 그 밖의 입력이나 입력이 없으면 아무것도 바꾸지 않고 끝낸다. 검증에서는 `printf 'y\n' |`로 넣는다.
   - 머지는 `--match-head-commit <확인한 head>`로 한다. 확인한 뒤 `develop`이 바뀌었으면 머지하지 않고 멈춘다.
3. **PR 설명은 따로 쓴다(2026-11-04 사용자 확인).** 두 번째 인자로 받은 파일로 PR을 만든다. 열린 릴리스 PR이 있으면 그 PR의 설명을 그대로 쓰고, 파일을 줬으면 쓰지 않는다고 알린다. PR이 없는데 파일도 없으면 아무것도 하지 않고 멈춘다. 평소에는 에이전트가 pr-creator로 PR을 먼저 만들고, 사용자는 버전만 주고 실행한다.
4. **필수 체크는 `statusCheckRollup`에서 이름으로 고른다.** `gh pr checks --required`는 `frontend`를 빠뜨린다. 체크 이름 목록(`test`, `frontend`)은 `ci.yml`·`.github/rulesets/*.json`과 맞춰야 한다고 스크립트 주석에 적는다.
   - 둘 다 `COMPLETED`이고 `SUCCESS`면 통과다. 하나라도 `COMPLETED`인데 `SUCCESS`가 아니면 실패다. 그 밖(아직 없음, 진행 중)은 대기다.
   - 제외한 대안: auto-merge. 저장소 설정을 바꿔야 하고, 머지 실패 이유를 스크립트가 바로 알 수 없다.
5. **이미지는 태그로 찾은 워크플로 실행을 기다린다.** 체크 `image`는 `sha-*` 빌드와 이름이 같다.
6. **대기 시간 제한과 조회 간격.** 조회 간격은 15초다. 제한을 넘으면 실패가 아니라 "아직 안 끝남"으로 알리고 멈춘다.

   | 대기 | 제한 | 근거 |
   | --- | --- | --- |
   | 필수 체크 | 30분 | `test` job 제한 20분에 대기열 여유. 실제 2분 27초 |
   | Pages 체크 | 15분 | 실제 44초 |
   | 이미지 실행 시작 | 3분 | 실제 7초 |
   | 이미지 실행 완료 | 20분 | job 제한 15분. 실제 39초 |

7. **배포 뒤 Tunnel을 거친 공개 API를 확인한다.** `deploy.sh`는 VM 안의 관리 포트만 본다. 5초 간격으로 다섯 번까지 200을 기다린다. 이렇게 하면 `docs/ops/deploy.md`의 확인 배포와 같은 기준으로 끝난다.
8. **결정 문서는 만들지 않는다.** 릴리스 절차는 `AGENTS.md`의 규칙이고, `AGENTS.md`에 있는 결정은 결정 문서로 올리지 않는다(`docs/plans/README.md`). 결정 0039의 배포 방식(로컬 SSH, `scripts/deploy.sh`)은 그대로다.
9. **개발 중 검증은 격리 환경에서만 한다.**
   - 저장소 밖 임시 디렉터리에 원격을 미러로 복제한 작업 사본을 둔다. 그래서 `git push`는 로컬 미러로 간다.
   - `PATH` 앞에 둔 가짜 `gh`가 쓰기 명령(`pr create`, `pr merge`)을 막고 시도를 기록한다. 읽기 명령은 진짜 `gh`로 넘긴다. 시나리오마다 정한 조회만 가짜 결과를 돌려준다.
   - `DEPLOY_HOST`는 존재하지 않는 호스트로 둔다. 실제 머지·태그 push·배포는 이 PR이 머지된 뒤 첫 릴리스에서 사용자가 한다(완료 조건 1).

## 단계별 구현 계획

1. **플랜 상태**: 착수할 때 이 파일의 `status`를 `in-progress`로 바꾼다.
2. **`scripts/release.sh`** (실행 권한 `100755`, `#!/usr/bin/env bash`, `set -euo pipefail`, 들여쓰기와 주석은 `deploy.sh`를 따른다)
   - 머리 주석: 사용법(`scripts/release.sh <vX.Y.Z> [설명 파일]`), 하는 일, 같은 명령으로 이어 가기, 에이전트는 실행하지 않는다는 것, 절차 문서(`docs/ops/deploy.md`)를 적는다.
   - 상수: 필수 체크 이름(`test`, `frontend`), Pages 체크 이름, 공개 API 주소, 설계 6의 제한 시간, 조회 간격.
   - `usage`, `fail <단계> <이유> <이어서 할 일>`: 표준 오류로 "중단: <단계>"와 이어서 할 일을 출력하고 1로 끝낸다.
   - 버전: `is_version`(`^v[0-9]+\.[0-9]+\.[0-9]+$`), `version_gt`(마디별 숫자 비교), `release_tags`(`git tag --list 'v*' --sort=-v:refname`에서 형식이 맞는 것), `latest_tag`, `previous_tag <버전>`(그 버전보다 작은 것 중 가장 큰 태그).
   - `load_pr <버전>`: `gh pr list --base main --head develop --state all --limit 100 --json number,title,state,url,headRefOid,mergeCommit`에서 제목이 `release: <버전>`과 같은 것을 고른다(설계 1). `PR_NUMBER`, `PR_STATE`, `PR_URL`, `PR_HEAD`, `PR_MERGE`를 채운다.
   - `resolve_start`: `git fetch --tags origin` 뒤 설계 1의 표대로 시작 단계(`create`, `checks`, `pages`)를 정한다. 버전 검사(새 태그면 `latest_tag`보다 큼, 있는 태그면 가장 최근 태그이면서 머지 커밋을 가리킴)도 여기서 한다. 어긋나면 PR을 만들거나 머지하기 전에 멈춘다.
   - `confirm`: 설계 2의 내용을 출력하고 입력을 받는다.
   - `poll <설명> <제한 초> <상태 함수>`: 상태 함수가 `pass`, `fail`, `pending` 중 하나를 출력한다. 통과면 0, 실패면 1, 시간 초과면 2를 돌려준다. 1분마다 기다리는 중이라고 한 줄 출력한다.
   - 단계 함수. 각 단계의 실패 안내는 아래와 같다.
     - `create_pr`: `gh pr create --base main --head develop --title "release: <버전>" --body-file <파일>` 뒤 `load_pr`. 실패하면 gh 출력을 보고 같은 명령.
     - `checks_state`, `wait_checks`: 설계 4. 실패하면 PR 주소를 보여 준다. 고칠 것은 `develop`에 머지하고, 일시적 실패면 GitHub에서 다시 실행한 뒤 같은 명령.
     - `merge_pr`: `gh pr merge <번호> --merge --match-head-commit <PR_HEAD>`(`--delete-branch` 없음) 뒤 `load_pr`로 머지 커밋을 얻고 `git fetch --tags origin`. 실패하면 같은 명령으로 바뀐 내용을 다시 확인.
     - `pages_state`, `wait_pages`: 머지 커밋의 `Cloudflare Pages` 체크. 실패하면 "태그는 아직 만들지 않았고 운영 API는 그대로다"라고 알린다. Pages 대시보드 → Deployments에서 다시 빌드한 뒤 같은 명령.
     - `ensure_tag`: 로컬에 없으면 `git tag -a <버전> <머지 커밋> -m <버전>`. `git ls-remote --exit-code --tags origin refs/tags/<버전>`이 없으면 `git push origin refs/tags/<버전>`.
     - `image_state`, `wait_image`: 설계 5. 실행이 나타날 때까지(3분), 끝날 때까지(20분) 기다린다. 실패하면 `gh run rerun <id>` 뒤 같은 명령.
     - `deploy`: `<저장소 루트>/scripts/deploy.sh <버전>`. `DEPLOY_HOST`는 환경 변수로 그대로 넘어간다. 실패하면 직전 릴리스 태그, 두 태그 사이의 마이그레이션 파일, 되돌리기 명령(`scripts/deploy.sh <직전 태그>`)과 `docs/ops/deploy.md`의 "되돌리기"를 안내한다. API를 되돌리면 Pages도 직전 배포로 되돌린다는 점도 적는다.
     - `check_public_api`: 설계 7. 실패하면 "VM 안의 상태 확인은 UP"이라고 알리고, app VM에서 `docker compose logs cloudflared`를 보라고 안내한다.
   - `main`: 인자 검사 → `gh auth status` → 저장소 루트(현재 디렉터리의 `git rev-parse --show-toplevel`)로 이동 → 설명 파일을 절대 경로로 바꾼다 → `resolve_start` → `confirm` → 시작 단계부터 차례로 → "완료: <버전>"과 되돌리기 명령을 출력한다.
3. **`AGENTS.md` 릴리스 절**
   - 릴리스는 저장소 루트에서 `scripts/release.sh vX.Y.Z [설명 파일]`로 한다. PR 머지, 프론트 빌드 확인, 태그 push, 이미지 확인, 운영 배포를 차례로 하고, 멈추면 같은 명령으로 이어 간다. 절차는 `docs/ops/deploy.md`에 있다.
   - 태그 형식(머지 커밋에 붙는 주석 태그 `vX.Y.Z`, 메시지 `vX.Y.Z`)과 "버전의 단일 소스는 git 태그" 문장은 남긴다.
   - "PR 머지 이후의 태그와 push는 사용자가 직접 합니다"를 바꾼다. 에이전트는 릴리스 PR 설명을 쓰고 PR을 만들 수 있지만 `scripts/release.sh`는 실행하지 않고 실행할 명령만 제시한다. 이 명령은 `main` 머지, 태그 push, 운영 배포를 하기 때문이다.
   - 분기 원점 표와 hotfix 절은 바꾸지 않는다.
4. **`docs/ops/deploy.md`**
   - 구성 요약의 API 행: 바뀌는 방법을 `scripts/release.sh <태그>`(릴리스), `scripts/deploy.sh <태그>`(되돌리기)로 고친다.
   - "릴리스할 때"를 고쳐 쓴다.
     - 릴리스 PR 설명에 포함된 변경, 마이그레이션 유무, 되돌릴 태그를 적는다. 이 설명이 `main` 머지 커밋의 메시지 본문이 된다.
     - 두 가지 실행 형태를 적는다(PR이 열려 있을 때, 설명 파일로 만들 때). 스크립트가 하는 일의 순서와 시작 전 확인 화면의 내용도 적는다.
     - 멈췄을 때의 표: 단계, 원인, 할 일. 할 일을 한 뒤에는 같은 명령을 다시 실행한다.
     - "프론트가 API보다 먼저 나간다" 문단은 남긴다. 스크립트가 Pages 성공 뒤에 API를 배포한다는 점을 더한다.
   - "스크립트 없이 릴리스할 때" 소절을 둔다. 지금의 수동 절차(머지, Pages 확인, 태그 push, 이미지 확인, `deploy.sh`)를 옮겨 둔다. Pages를 다시 돌려도 체크가 바뀌지 않는 경우에 쓴다.
5. **검증** (검증 명령 1·2, 설계 9의 격리 환경에서 시나리오 실행)
   - 격리 환경을 만든다. 저장소 밖 임시 디렉터리에 원격 미러(`origin.git`)와 그 복제(`work`)를 둔다. 가짜 `gh`(`bin/gh`)를 둔다.
     - 쓰기 명령(`pr create`, `pr merge`)은 인자를 `gh-writes.log`에 적고 1로 끝낸다.
     - `pr list`, `pr view`, `api …/check-runs…`, `run list`는 해당 환경 변수(`FAKE_PR_JSON`, `FAKE_ROLLUP_JSON`, `FAKE_PAGES_JSON`, `FAKE_RUN_JSON`)가 있으면 그 JSON에 인자의 `--jq` 식을 시스템 `jq -r`로 적용해 출력한다.
     - 그 밖은 진짜 `gh`로 넘긴다.
   - 실행은 `work`에서 `PATH=<bin>:$PATH GH_REPO=tukjw/gov-benefit-helper DEPLOY_HOST=invalid.invalid <워크트리>/scripts/release.sh …`로 한다.
   - 시나리오와 기대 결과는 아래 표와 같다.

     | # | 입력과 가짜 결과 | 기대 결과 |
     | --- | --- | --- |
     | S1 | 인자 없음 | 사용법, 종료 코드 2 |
     | S2 | `v1.2` | 형식 오류로 멈춤 |
     | S3 | `v0.1.1` | 가장 최근 태그가 아닌 기존 태그라 멈춤 |
     | S4 | `v0.0.9` | 최근 태그보다 크지 않아 멈춤 |
     | S5 | `v0.2.0`, 설명 파일 없음 | PR도 설명 파일도 없어 멈춤 |
     | S6 | `v0.2.0 <설명 파일>`, 입력 `n` | 확인 화면에 `v0.1.2..origin/develop` 커밋과 V12에서 V15까지가 나옴. 쓰기 기록 없음 |
     | S7 | `v0.2.0 <설명 파일>`, 열린 PR(가짜 #999), `test` 실패 | 설명 파일을 쓰지 않는다고 알림. 체크 단계에서 멈춤. 쓰기 기록 없음 |
     | S8 | S7에서 두 체크 성공 | 머지 시도가 기록되고(`pr merge 999 --merge --match-head-commit <sha>`, `--delete-branch` 없음) 머지 단계에서 멈춤 |
     | S9 | `v9.9.9`, 머지된 PR(가짜, 머지 커밋은 `origin/main`), Pages 실패 | Pages 단계에서 멈춤. 미러와 로컬에 `v9.9.9` 태그 없음 |
     | S10 | S9에서 Pages 성공 | 주석 태그 `v9.9.9`가 미러에 push됨. 이미지 실행이 3분 안에 나타나지 않아 멈춤. 다시 실행하면 태그를 다시 만들지 않고 이미지 단계까지 감 |
     | S11 | `v0.1.2`(실제 #102 머지, 태그 있음), 이미지 실행 실패 | Pages는 통과하고 태그는 push하지 않음. 이미지 단계에서 `gh run rerun` 안내와 함께 멈춤 |
     | S12 | `v0.1.2`, 가짜 결과 없음 | 이미지 단계까지 통과. `deploy.sh`가 SSH에서 실패하고, 직전 태그 `v0.1.1`과 마이그레이션 "없음", 되돌리기 안내를 출력하고 멈춤 |

   - 시나리오가 끝나면 `gh pr list --base main --state open`에 새 PR이 없고 GitHub에 `v9.9.9` 태그가 없는지 확인한다.
6. **마무리**: PR 설명에 시나리오 결과를 적는다. 관련 이슈는 `Related to #121`로 둔다. 완료 조건 1(첫 릴리스를 이 명령으로 배포)은 머지 뒤에 확인하므로, 첫 릴리스를 마치면 결과를 이슈에 남기고 닫는다. PR 직전 마지막 커밋에서 `status`를 `done`으로 바꾼다.

## 완료 조건 매핑

| 완료 조건 | 구현 단계 | 확인 방법 |
| --- | --- | --- |
| `scripts/release.sh vX.Y.Z`를 한 번 실행하면 릴리스 PR 머지, 태그 push, 운영 배포까지 끝난다. 이 이슈를 머지한 뒤의 첫 릴리스를 이 명령으로 배포한다. | 2, 6 | S8·S10·S12로 각 단계 호출 확인. 머지 뒤 첫 릴리스를 사용자가 이 명령으로 실행 |
| 운영 API 배포는 머지 커밋의 Pages 빌드가 성공한 뒤에 시작한다. Pages 빌드가 실패하면 태그를 만들지 않고 멈춘다. | 2 (`wait_pages` → `ensure_tag` 순서) | S9, S10 |
| 버전 형식이 `vX.Y.Z`가 아니거나, 이미 있는 태그이거나, 가장 최근 릴리스 태그보다 크지 않으면 PR을 만들거나 머지하기 전에 멈춘다. | 2 (`resolve_start`) | S2, S3, S4 |
| 필수 체크 실패, 이미지 빌드 실패, 배포 상태 확인 실패 중 어느 경우에도 다음 단계로 넘어가지 않고, 멈춘 단계와 이어서 할 방법을 출력한다. | 2 (`fail`, 단계 함수) | S7, S11, S12 |
| 같은 버전의 릴리스 PR이 이미 열려 있으면 새 PR을 만들지 않고 그 PR로 진행한다. | 2 (`load_pr`, `resolve_start`) | S7(쓰기 기록에 `pr create` 없음) |
| 저장소 지침과 운영 문서의 릴리스 절차가 이 명령 기준으로 설명돼 있고, 에이전트가 이 명령을 실행하지 않는다는 규칙이 지침에 있다. | 3, 4 | 문서 검토 |

## 검증 명령

```bash
# 1. 문법과 정적 검사
bash -n scripts/release.sh
docker run --rm -v "$PWD:/mnt:ro" -w /mnt koalaman/shellcheck:stable scripts/release.sh

# 2. 격리 환경(단계 5). <tmp>는 저장소 밖 임시 디렉터리
git clone --mirror "$(git remote get-url origin)" <tmp>/origin.git
git clone <tmp>/origin.git <tmp>/work
cd <tmp>/work
PATH=<tmp>/bin:$PATH GH_REPO=tukjw/gov-benefit-helper DEPLOY_HOST=invalid.invalid \
  <워크트리>/scripts/release.sh v0.2.0 <설명 파일> <<< n   # S6. 나머지 시나리오는 단계 5의 표

# 3. 실행 권한
git ls-files -s scripts/release.sh   # 100755
```

`backend/`·`frontend/`는 바꾸지 않으므로 Gradle·npm 검증은 CI에 맡긴다.

## 결정 필요

없음. 이어 가기 방식, 확인 프롬프트, PR 설명 전달은 2026-11-04 사용자와 정했다(설계 1·2·3).
