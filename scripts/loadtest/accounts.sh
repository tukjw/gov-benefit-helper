#!/usr/bin/env bash
# 부하 테스트 계정을 운영 DB에 넣고 지운다(결정 0037). 절차는 docs/ops/load-test.md.
# 사용법: scripts/loadtest/accounts.sh <seed | cleanup | count>
#   seed    비밀번호를 새로 만들고 계정 1,000개를 넣는다. 비밀번호는 PASSWORD_FILE에만 둔다.
#   cleanup 계정을 지우고(관련 데이터는 CASCADE) 남은 수를 센 뒤 PASSWORD_FILE을 지운다.
#   count   남은 계정과 그 계정의 리프레시 토큰 수를 센다.
# SQL은 db VM의 mysql 컨테이너 안에서 앱 계정(컨테이너 환경 변수 MYSQL_USER·MYSQL_PASSWORD)으로 실행하므로
# 실행하는 기기(부하 VM이나 내 PC)에 DB 비밀번호가 필요 없다. SSH 대상은 DB_HOST(기본 govbenefit-db)다.
# LOADTEST_DB=local이면 로컬 compose(docker-compose.local.yml)의 MySQL에 실행한다(스크립트 확인용).
set -euo pipefail

usage() { echo "사용법: $0 <seed | cleanup | count>" >&2; exit 2; }
[[ $# -eq 1 ]] || usage
command=$1

script_dir=$(cd "$(dirname "$0")" && pwd)
repo_root=$(cd "$script_dir/../.." && pwd)
password_file=${PASSWORD_FILE:-${XDG_STATE_HOME:-$HOME/.local/state}/gov-benefit-loadtest/password}
db_host=${DB_HOST:-govbenefit-db}
# 컨테이너 안의 셸이 환경 변수를 펼친다. MYSQL_PWD로 넘겨 명령줄에 비밀번호가 보이지 않게 한다.
mysql_in_container='MYSQL_PWD="$MYSQL_PASSWORD" exec mysql -N -u"$MYSQL_USER" "$MYSQL_DATABASE"'

run_sql() {
  if [[ ${LOADTEST_DB:-} == local ]]; then
    docker compose -f "$repo_root/docker-compose.local.yml" exec -T mysql sh -c "$mysql_in_container"
  else
    ssh "$db_host" "cd /opt/gov-benefit/db && docker compose exec -T mysql sh -c '$mysql_in_container'"
  fi
}

count() {
  run_sql < "$script_dir/sql/count-accounts.sql"
}

case $command in
  seed)
    if [[ -e $password_file ]]; then
      echo "이전 비밀번호 파일이 있습니다($password_file). 지난 측정의 계정이 남아 있을 수 있으니 cleanup을 먼저 실행하세요." >&2
      exit 1
    fi
    # 앱과 같은 BCrypt 강도(10)여야 로그인 비용이 실제 계정과 같다(결정 0013). 앱은 $2y$ 해시도 비교한다.
    password=$(openssl rand -base64 24)
    hash=$(htpasswd -nbBC 10 "" "$password" | tr -d ':\n')
    [[ $hash == '$2'* ]] || { echo "BCrypt 해시를 만들지 못했습니다. htpasswd(-B)가 필요합니다." >&2; exit 1; }
    mkdir -p "$(dirname "$password_file")"
    (umask 077 && printf '%s\n' "$password" > "$password_file")
    if ! { printf "SET @password_hash = '{bcrypt}%s';\n" "$hash"; cat "$script_dir/sql/seed-accounts.sql"; } | run_sql; then
      rm -f "$password_file"
      echo "계정을 넣지 못했습니다. 이미 계정이 있으면 cleanup을 먼저 실행하세요." >&2
      exit 1
    fi
    count
    echo "비밀번호: $password_file (측정이 끝나면 cleanup이 지운다)"
    ;;
  cleanup)
    run_sql < "$script_dir/sql/cleanup-accounts.sql"
    count
    rm -f "$password_file"
    ;;
  count)
    count
    ;;
  *)
    usage
    ;;
esac
