#!/usr/bin/env bash
# develop → main 릴리스 PR 머지부터 운영 배포까지 차례로 한다(#121).
# 사용법: scripts/release.sh <vX.Y.Z> [설명 파일]
#   설명 파일은 릴리스 PR(release: vX.Y.Z)이 아직 없을 때 그 PR의 설명으로 쓴다. 열린 PR이 있으면 그 PR로 진행한다.
# 순서: 릴리스 PR 생성 → 필수 체크 대기 → merge commit으로 머지 → 머지 커밋의 Pages 빌드 확인
#       → 머지 커밋에 태그 push → 이미지 빌드 확인 → scripts/deploy.sh <태그> → 공개 API 확인
# 시작할 때 한 번 확인받는다. 중간에 멈추면 안내대로 처리한 뒤 같은 명령을 다시 실행한다.
# 끝난 단계는 GitHub과 git 상태를 보고 건너뛴다. 상태 파일은 두지 않는다.
# main 머지·태그 push·운영 배포를 하므로 에이전트는 실행하지 않고 명령만 제시한다(AGENTS.md). 절차는 docs/ops/deploy.md.
set -euo pipefail

# 필수 체크 이름. .github/workflows/ci.yml의 job 이름, .github/rulesets/main.json과 같아야 한다.
# gh pr checks --required는 frontend를 빠뜨려서 쓰지 않는다.
required_checks=(test frontend)
pages_check="Cloudflare Pages"
migration_dir=backend/src/main/resources/db/migration
# 대기 제한(초)과 조회 간격. 근거는 docs/plans/121-release-script.md 설계 6.
checks_timeout=1800
pages_timeout=900
image_start_timeout=180
image_timeout=1200
poll_interval=15

usage() { echo "사용법: $0 <vX.Y.Z> [설명 파일]" >&2; exit 2; }

# fail <단계> <이유> [이어서 할 일...]
fail() {
  local stage=$1 reason=$2 line
  shift 2
  echo "중단($stage): $reason" >&2
  for line in "$@"; do
    echo "  $line" >&2
  done
  exit 1
}

step() { printf '\n== %s\n' "$*"; }

# --- 버전과 태그 ---

is_version() { [[ $1 =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; }

# version_gt <a> <b>: a가 b보다 크면 참
version_gt() {
  local a b i
  IFS=. read -r -a a <<< "${1#v}"
  IFS=. read -r -a b <<< "${2#v}"
  for i in 0 1 2; do
    if (( 10#${a[i]} != 10#${b[i]} )); then
      (( 10#${a[i]} > 10#${b[i]} ))
      return
    fi
  done
  return 1
}

# 형식이 맞는 릴리스 태그를 큰 버전부터
release_tags() { git tag --list 'v*' --sort=-v:refname | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' || true; }

latest_tag() {
  local tags
  tags=$(release_tags)
  printf '%s\n' "${tags%%$'\n'*}"
}

# previous_tag <버전>: 그 버전보다 작은 릴리스 태그 중 가장 큰 것
previous_tag() {
  local tag
  while read -r tag; do
    if [[ -n $tag ]] && version_gt "$1" "$tag"; then
      echo "$tag"
      return
    fi
  done <<< "$(release_tags)"
}

# migrations_between <from> <to>: 그 사이에 추가된 마이그레이션 파일 이름. 없으면 "없음"
migrations_between() {
  local files
  files=$(git diff --name-only --diff-filter=A "$1" "$2" -- "$migration_dir" | sed 's|.*/||' | paste -sd ' ' -)
  echo "${files:-없음}"
}

# --- 릴리스 PR과 시작 단계 ---

# load_pr <버전>: 제목이 "release: <버전>"인 develop → main PR.
# 머지된 PR, 열린 PR 순으로 고르고 닫힌 PR은 없는 것으로 본다.
load_pr() {
  local row
  row=$(gh pr list --base main --head develop --state all --limit 100 \
    --json number,title,state,url,headRefOid,mergeCommit \
    --jq "map(select(.title == \"release: $1\"))
      | (map(select(.state == \"MERGED\")) + map(select(.state == \"OPEN\"))) | first // empty
      | [.number, .state, .url, .headRefOid, (.mergeCommit.oid // \"\")] | @tsv")
  pr_number='' pr_state='' pr_url='' pr_head='' pr_merge=''
  if [[ -n $row ]]; then
    IFS=$'\t' read -r pr_number pr_state pr_url pr_head pr_merge <<< "$row"
  fi
}

# 릴리스 PR과 태그를 보고 시작 단계(create, checks, pages)를 정한다(플랜 #121 설계 1).
# 버전이 맞지 않으면 PR을 만들거나 머지하기 전에 멈춘다.
resolve_start() {
  local latest tag_commit
  latest=$(latest_tag)
  load_pr "$version"
  if git rev-parse --verify --quiet "refs/tags/$version" > /dev/null; then
    tag_commit=$(git rev-parse "refs/tags/$version^{commit}")
    if [[ $pr_state != MERGED || $tag_commit != "$pr_merge" || $version != "$latest" ]]; then
      fail "버전 확인" "태그가 이미 있습니다: $version" \
        "이어서 진행할 수 있는 태그는 가장 최근 릴리스 태그(${latest:-없음})이면서 그 버전 릴리스 PR의 머지 커밋을 가리키는 것뿐입니다." \
        "이전 버전으로 되돌리려면 scripts/deploy.sh <태그>를 씁니다(docs/ops/deploy.md '되돌리기')."
    fi
  elif [[ -n $latest ]] && ! version_gt "$version" "$latest"; then
    fail "버전 확인" "가장 최근 릴리스 태그($latest)보다 큰 버전이어야 합니다: $version" "버전을 바꿔 다시 실행합니다."
  fi

  case $pr_state in
    MERGED) start=pages ;;
    OPEN) start=checks ;;
    *)
      if [[ -z $body_file ]]; then
        fail "릴리스 PR" "열린 릴리스 PR(release: $version)이 없고 설명 파일도 없습니다." \
          "PR 설명을 파일로 써서 두 번째 인자로 주거나, 릴리스 PR을 먼저 만든 뒤 다시 실행합니다."
      fi
      start=create
      ;;
  esac
}

# 시작할 때 한 번 확인받는다(플랜 #121 설계 2). y가 아니면 아무것도 바꾸지 않고 끝낸다.
confirm() {
  local prev target label answer=''
  prev=$(previous_tag "$version")
  case $start in
    create) target=$(git rev-parse origin/develop) label="릴리스 PR 생성" ;;
    checks) target=$pr_head label="필수 체크 대기" ;;
    pages) target=$pr_merge label="Pages 빌드 확인 (PR 머지됨)" ;;
  esac
  # 머지는 여기서 보여 준 head로만 한다.
  confirmed_head=$target

  echo "릴리스 $version"
  echo "  직전 릴리스: ${prev:-없음}"
  if [[ $start == create ]]; then
    echo "  릴리스 PR: 새로 만듦 (설명: $body_file)"
  else
    echo "  릴리스 PR: #$pr_number $pr_url ($pr_state)"
    if [[ -n $body_file ]]; then
      echo "  설명 파일은 쓰지 않습니다. PR에 있는 설명을 그대로 씁니다."
    fi
  fi
  echo "  시작 단계: $label"
  if [[ -n $prev ]]; then
    echo "  포함 커밋 ($prev..${target:0:7}):"
    git log --oneline --no-merges "$prev..$target" | sed 's/^/    /'
    echo "  추가된 마이그레이션: $(migrations_between "$prev" "$target")"
  fi
  echo "  프론트(Pages)가 API보다 먼저 나갑니다. 새 API가 있어야 동작하는 화면 변경이 있으면 중단하고 릴리스를 나눕니다."
  read -r -p "계속할까요? [y/N] " answer || true
  if [[ $answer != [yY] ]]; then
    echo "중단했습니다. 바뀐 것은 없습니다."
    exit 0
  fi
}

# --- 기다리기 ---

# poll <설명> <제한 초> <상태 함수>: 상태 함수는 pass, fail, pending 중 하나를 출력한다.
# 통과면 0, 실패면 1, 시간 초과면 2를 돌려준다. 조회가 실패하면 pending으로 보고 다시 묻는다.
poll() {
  local what=$1 timeout=$2 check=$3 state started=$SECONDS next_note=$((SECONDS + 60))
  while :; do
    state=$("$check" 2> /dev/null || true)
    case $state in
      pass) return 0 ;;
      fail) return 1 ;;
    esac
    if (( SECONDS - started >= timeout )); then
      return 2
    fi
    if (( SECONDS >= next_note )); then
      echo "  $what 기다리는 중 ($(( (SECONDS - started) / 60 ))분 지남)"
      next_note=$((SECONDS + 60))
    fi
    sleep "$poll_interval"
  done
}

# --- 릴리스 PR 생성, 필수 체크, 머지 ---

create_pr() {
  step "릴리스 PR 만들기"
  gh pr create --base main --head develop --title "release: $version" --body-file "$body_file" \
    || fail "릴리스 PR" "PR을 만들지 못했습니다." "위 gh 출력을 확인한 뒤 같은 명령을 다시 실행합니다."
  load_pr "$version"
  [[ $pr_state == OPEN ]] \
    || fail "릴리스 PR" "만든 PR을 찾지 못했습니다." "GitHub에서 PR을 확인한 뒤 같은 명령을 다시 실행합니다."
}

# 필수 체크 상태. 같은 이름이 여러 번 돌았으면 가장 늦게 시작한 것을 본다.
checks_state() {
  local names rows name status conclusion passed=0
  names=$(printf '"%s",' "${required_checks[@]}")
  rows=$(gh pr view "$pr_number" --json statusCheckRollup --jq "
    [.statusCheckRollup[] | select(.name | IN(${names%,}))]
    | group_by(.name) | map(max_by(.startedAt)) | .[] | \"\(.name) \(.status) \(.conclusion)\"")
  while read -r name status conclusion; do
    [[ -n $name && $status == COMPLETED ]] || continue
    if [[ $conclusion != SUCCESS ]]; then
      echo fail
      return
    fi
    passed=$((passed + 1))
  done <<< "$rows"
  if (( passed == ${#required_checks[@]} )); then echo pass; else echo pending; fi
}

wait_checks() {
  step "필수 체크 기다리기 (#$pr_number: ${required_checks[*]})"
  local rc=0
  poll "필수 체크" "$checks_timeout" checks_state || rc=$?
  case $rc in
    1) fail "필수 체크" "실패한 체크가 있습니다: $pr_url/checks" \
         "고칠 것은 develop에 머지하고, 일시적인 실패면 GitHub에서 다시 실행한 뒤 같은 명령을 다시 실행합니다." ;;
    2) fail "필수 체크" "$((checks_timeout / 60))분 안에 끝나지 않았습니다: $pr_url/checks" \
         "체크가 끝난 뒤 같은 명령을 다시 실행합니다." ;;
  esac
  echo "  통과"
}

merge_pr() {
  step "릴리스 PR 머지 (#$pr_number, merge commit)"
  # develop을 지우지 않도록 --delete-branch를 쓰지 않는다. 머지 커밋 제목·본문은 저장소 설정대로 PR 제목·설명이다.
  gh pr merge "$pr_number" --merge --match-head-commit "$confirmed_head" \
    || fail "머지" "PR을 머지하지 못했습니다: #$pr_number" \
         "확인한 뒤 develop이 바뀌었으면 같은 명령을 다시 실행해 바뀐 커밋을 확인합니다." \
         "그 밖에는 위 gh 출력을 확인한 뒤 같은 명령을 다시 실행합니다."
  load_pr "$version"
  [[ $pr_state == MERGED && -n $pr_merge ]] \
    || fail "머지" "머지 커밋을 찾지 못했습니다." "GitHub에서 PR을 확인한 뒤 같은 명령을 다시 실행합니다."
  git fetch --quiet --tags origin
  echo "  머지 커밋: $pr_merge"
}

# --- Pages 빌드, 태그, 이미지 빌드 ---

# 머지 커밋의 Pages 체크. 빌드가 끝날 때 생기므로 없으면 기다린다.
pages_state() {
  local row
  row=$(gh api "repos/{owner}/{repo}/commits/$pr_merge/check-runs?check_name=${pages_check// /%20}" \
    --jq '.check_runs[0] // empty | "\(.status) \(.conclusion)"')
  case $row in
    "completed success") echo pass ;;
    completed*) echo fail ;;
    *) echo pending ;;
  esac
}

wait_pages() {
  step "Pages 빌드 확인 (${pr_merge:0:7})"
  local rc=0
  poll "Pages 빌드" "$pages_timeout" pages_state || rc=$?
  case $rc in
    1) fail "Pages" "머지 커밋의 Pages 빌드가 실패했습니다. 태그는 아직 만들지 않았고 운영 API는 그대로입니다." \
         "Pages 대시보드 → Deployments에서 다시 빌드한 뒤 같은 명령을 다시 실행합니다." \
         "다시 빌드해도 체크가 바뀌지 않으면 docs/ops/deploy.md의 '스크립트 없이 릴리스할 때'로 이어 갑니다." ;;
    2) fail "Pages" "$((pages_timeout / 60))분 안에 머지 커밋의 Pages 빌드 결과가 나오지 않았습니다. 태그는 아직 만들지 않았습니다." \
         "Pages 대시보드에서 빌드를 확인한 뒤 같은 명령을 다시 실행합니다." ;;
  esac
  echo "  통과: $pages_check"
}

ensure_tag() {
  step "태그 $version"
  if ! git rev-parse --verify --quiet "refs/tags/$version" > /dev/null; then
    git tag -a "$version" "$pr_merge" -m "$version"
    echo "  만듦: $version → ${pr_merge:0:7}"
  fi
  if git ls-remote --exit-code --tags origin "refs/tags/$version" > /dev/null; then
    echo "  원격에 있습니다."
  else
    git push origin "refs/tags/$version" \
      || fail "태그" "태그를 push하지 못했습니다." "위 git 출력을 확인한 뒤 같은 명령을 다시 실행합니다."
  fi
}

# 태그 push로 시작한 이미지 워크플로 실행: "<id> <status> <conclusion>". 수동 실행(sha-*)과 섞이지 않게 태그로 찾는다.
image_run() {
  gh run list --workflow image.yml --branch "$version" --event push --limit 1 \
    --json databaseId,status,conclusion --jq '.[0] // empty | "\(.databaseId) \(.status) \(.conclusion)"'
}

image_started() {
  if [[ -n $(image_run) ]]; then echo pass; else echo pending; fi
}

image_state() {
  local id status conclusion
  read -r id status conclusion <<< "$(image_run)"
  if [[ -z $id || $status != completed ]]; then
    echo pending
  elif [[ $conclusion == success ]]; then
    echo pass
  else
    echo fail
  fi
}

wait_image() {
  step "이미지 빌드 확인 (ghcr.io/tukjw/gov-benefit-helper:$version)"
  local rc=0 id
  poll "이미지 빌드 시작" "$image_start_timeout" image_started || rc=$?
  if (( rc != 0 )); then
    fail "이미지" "태그 push 뒤 $((image_start_timeout / 60))분 안에 이미지 워크플로가 시작되지 않았습니다." \
      "GitHub Actions의 Image 워크플로를 확인한 뒤 같은 명령을 다시 실행합니다."
  fi
  poll "이미지 빌드" "$image_timeout" image_state || rc=$?
  read -r id _ <<< "$(image_run)"
  case $rc in
    1) fail "이미지" "이미지 빌드가 실패했습니다 (실행 $id)." \
         "gh run view $id --log-failed 로 원인을 보고, gh run rerun $id 뒤 같은 명령을 다시 실행합니다." ;;
    2) fail "이미지" "$((image_timeout / 60))분 안에 이미지 빌드가 끝나지 않았습니다 (실행 $id)." \
         "gh run watch $id 로 끝난 것을 본 뒤 같은 명령을 다시 실행합니다." ;;
  esac
  echo "  통과 (실행 $id)"
}

# --- 실행 ---

[[ $# -ge 1 && $# -le 2 ]] || usage
version=$1
body_file=${2:-}
is_version "$version" || fail "버전 확인" "버전 형식은 vX.Y.Z입니다: $version" "버전을 바꿔 다시 실행합니다."
if [[ -n $body_file ]]; then
  [[ -f $body_file ]] || fail "설명 파일" "파일이 없습니다: $body_file" "경로를 확인해 다시 실행합니다."
  body_file="$(cd "$(dirname "$body_file")" && pwd)/$(basename "$body_file")"
fi
gh auth status > /dev/null 2>&1 || fail "준비" "gh 인증이 없습니다." "gh auth login 뒤 다시 실행합니다."
repo_root=$(git rev-parse --show-toplevel)
cd "$repo_root"
git fetch --quiet --tags origin

resolve_start
confirm
if [[ $start == create ]]; then
  create_pr
fi
if [[ $start != pages ]]; then
  wait_checks
  merge_pr
fi
wait_pages
ensure_tag
wait_image
