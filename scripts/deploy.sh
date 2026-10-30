#!/usr/bin/env bash
# app VM의 앱을 지정한 이미지 태그로 바꾼다(결정 0036). 되돌리기도 직전 태그로 같은 명령을 실행한다.
# 사용법: scripts/deploy.sh <vX.Y.Z | sha-<커밋 7자리>>
# SSH 대상은 DEPLOY_HOST(기본 govbenefit-app, ~/.ssh/config의 별칭)다. 절차는 docs/ops/deploy.md.
# 실패해도 자동으로 되돌리지 않는다. 마이그레이션이 이미 적용됐을 수 있어 이전 이미지가 뜨는지 보고 판단한다.
set -euo pipefail

usage() { echo "사용법: $0 <vX.Y.Z | sha-<커밋 7자리>>" >&2; exit 2; }
[[ $# -eq 1 ]] || usage
tag=$1
host=${DEPLOY_HOST:-govbenefit-app}
remote_dir=/opt/gov-benefit/app
health_timeout=120

# 1. 이미지 태그에 대응하는 커밋. 실행 구성을 이미지와 같은 시점으로 맞춘다.
case $tag in
  v*.*.*) ref="refs/tags/$tag" ;;
  sha-???????) ref=${tag#sha-} ;;
  *) usage ;;
esac
if ! commit=$(git rev-parse --verify --quiet "$ref^{commit}"); then
  echo "로컬 저장소에 '$tag'에 대응하는 커밋이 없습니다. git fetch --tags origin 뒤 다시 실행하세요." >&2
  exit 1
fi

# 2. 실행 구성 복사와 현재 태그 확인
# 먼저 읽어 두어, 그 시점에 파일이 없으면 VM의 파일을 빈 내용으로 덮기 전에 멈춘다.
compose=$(git show "$commit:deploy/app/compose.yaml")
printf '%s\n' "$compose" | ssh "$host" "cat > $remote_dir/compose.yaml"
previous=$(ssh "$host" "sed -n 's/^APP_TAG=//p' $remote_dir/.env 2>/dev/null" || true)
echo "배포: ${previous:-없음} → $tag ($host)"

# 3. 이미지를 먼저 받고, 받은 뒤에만 태그를 바꿔 교체한다. 받기에 실패하면 실행 중인 앱은 그대로다.
ssh "$host" "cd $remote_dir && APP_TAG=$tag docker compose pull app && echo 'APP_TAG=$tag' > .env && docker compose up -d"

# 4. 상태 확인. DB 연결과 Flyway 마이그레이션이 끝나야 UP이다.
# 상태 확인은 관리 포트(8081)에 있다(결정 0036). 관리 포트가 없는 이미지(v0.1.0)는 앱 포트(8080)에 있어
# 그 이미지로 되돌릴 때를 위해 8080도 본다. 그런 이미지로 되돌릴 일이 없어지면 8080 확인을 지운다.
health_cmd="curl -fsS http://127.0.0.1:8081/actuator/health || curl -fsS http://127.0.0.1:8080/actuator/health"
echo "상태 확인을 기다립니다(최대 ${health_timeout}초)."
deadline=$((SECONDS + health_timeout))
until ssh "$host" "$health_cmd" 2>/dev/null | grep -q '"status":"UP"'; do
  if (( SECONDS >= deadline )); then
    echo "실패: ${health_timeout}초 안에 상태 확인이 UP이 되지 않았습니다. 앱 로그 마지막 50줄:" >&2
    ssh "$host" "cd $remote_dir && docker compose logs --tail 50 app" >&2 || true
    if [[ -n $previous ]]; then
      echo "되돌리려면: $0 $previous" >&2
    fi
    exit 1
  fi
  sleep 3
done

echo "완료: $tag"
if [[ -n $previous && $previous != "$tag" ]]; then
  echo "되돌리려면: $0 $previous"
fi
