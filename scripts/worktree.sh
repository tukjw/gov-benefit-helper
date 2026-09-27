#!/usr/bin/env bash
# 기존 로컬 브랜치 또는 이슈 번호로 워크트리를 생성한다.
# 사용법: scripts/worktree.sh <브랜치명 | 이슈번호>
# 경로: <저장소>-worktrees/<브랜치명의 '/'를 '-'로 치환>
# 브랜치 생성·전환, 원격 작업, 기존 경로 덮어쓰기는 하지 않는다.
set -euo pipefail

usage() { echo "사용법: $0 <브랜치명 | 이슈번호>" >&2; exit 2; }
[[ $# -eq 1 ]] || usage
target=$1

repo_root=$(git rev-parse --show-toplevel)
repo_name=$(basename "$repo_root")
parent_dir="$(dirname "$repo_root")/${repo_name}-worktrees"

# 1. 브랜치 결정
if git show-ref --verify --quiet "refs/heads/$target"; then
  branch=$target
elif [[ $target =~ ^[0-9]+$ ]]; then
  mapfile -t candidates < <(git for-each-ref --format='%(refname:short)' refs/heads/ \
    | awk -v n="$target" -F/ '{last=$NF} last==n || index(last, n"-")==1')
  case ${#candidates[@]} in
    0) echo "이슈 #$target 에 대응하는 로컬 브랜치가 없습니다. 브랜치를 먼저 생성하세요." >&2; exit 1 ;;
    1) branch=${candidates[0]} ;;
    *) echo "이슈 #$target 에 대응하는 브랜치가 여러 개입니다. 브랜치명을 직접 지정하세요:" >&2
       printf '  %s\n' "${candidates[@]}" >&2; exit 1 ;;
  esac
else
  echo "로컬 브랜치 '$target' 이(가) 없습니다." >&2; exit 1
fi

# 2. 이미 연결된 워크트리 확인
existing=$(git worktree list --porcelain | awk -v b="refs/heads/$branch" '
  /^worktree /{path=substr($0,10)} $0=="branch "b{print path}')
if [[ -n $existing ]]; then
  echo "생성하지 않음: 브랜치 '$branch' 은(는) 이미 워크트리에 연결되어 있습니다."
  echo "경로: $existing"
  exit 0
fi

# 3. 목적지 확정, 기존 항목 보호
dest="$parent_dir/${branch//\//-}"
if [[ -e $dest ]]; then
  echo "중단: 목적지가 이미 존재합니다: $dest" >&2; exit 1
fi
mkdir -p "$parent_dir"

# 4. 생성 및 검증
before=$(git -C "$repo_root" rev-parse --abbrev-ref HEAD)
git worktree add "$dest" "$branch"
after=$(git -C "$repo_root" rev-parse --abbrev-ref HEAD)
new_branch=$(git -C "$dest" rev-parse --abbrev-ref HEAD)
head=$(git -C "$dest" rev-parse HEAD)
[[ $new_branch == "$branch" ]] || { echo "검증 실패: 새 워크트리 브랜치가 '$new_branch' 입니다." >&2; exit 1; }
[[ $before == "$after" ]] || { echo "검증 실패: 원래 워크트리 브랜치가 바뀌었습니다." >&2; exit 1; }

cat <<RESULT
## 워크트리 생성 결과

- 저장소: \`$repo_root\`
- 브랜치: \`$branch\`
- 경로: \`$dest\`
- HEAD: \`$head\`
- 기존 워크트리 브랜치: \`$before\`
RESULT
