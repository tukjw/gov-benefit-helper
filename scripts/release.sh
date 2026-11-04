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

migration_dir=backend/src/main/resources/db/migration

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
