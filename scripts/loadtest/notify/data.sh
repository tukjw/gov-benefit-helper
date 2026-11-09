#!/usr/bin/env bash
# 알림 발송 장애 측정(#137)의 합성 데이터를 운영 DB에 넣고 지운다. run.sh와 switch.sh가 부른다. 절차는 docs/ops/load-test.md.
# 사용법: scripts/loadtest/notify/data.sh <seed [사용자 수] | cleanup | count | pending | status | notify-off <이메일 파일>>
#   seed       가짜 새 혜택 3건, 합성 사용자(기본 10000명), 사용자마다 발송 대기 묶음 하나를 넣는다(sql/seed.sql).
#              남은 합성 데이터가 있으면 넣지 않는다.
#   cleanup    합성 데이터를 지우고 남은 수를 센다.
#   count      남은 합성 데이터 수(계정, 묶음, 가짜 새 혜택, 계정 메일 인증 기록). 정리 뒤 모두 0이어야 한다.
#   pending    발송 대기로 남은 합성 묶음 수
#   status     합성 묶음의 상태(TSV, sql/status.sql의 열)
#   notify-off 파일의 이메일(한 줄에 하나)의 알림을 끈다. 끝나면 커밋된 것이다(autocommit).
# SQL은 accounts.sh와 같이 db VM의 mysql 컨테이너 안에서 앱 계정으로 실행한다. SSH 대상은 DB_HOST(기본 govbenefit-db)다.
# LOADTEST_DB=local이면 로컬 compose(docker-compose.local.yml)의 MySQL에 실행한다(리허설).
set -euo pipefail

usage() { echo "사용법: $0 <seed [사용자 수] | cleanup | count | pending | status | notify-off <이메일 파일>>" >&2; exit 2; }
[[ $# -ge 1 ]] || usage
command=$1

script_dir=$(cd "$(dirname "$0")" && pwd)
repo_root=$(cd "$script_dir/../../.." && pwd)
db_host=${DB_HOST:-govbenefit-db}
mysql_in_container='MYSQL_PWD="$MYSQL_PASSWORD" exec mysql -N -u"$MYSQL_USER" "$MYSQL_DATABASE"'

run_sql() {
  if [[ ${LOADTEST_DB:-} == local ]]; then
    docker compose -f "$repo_root/docker-compose.local.yml" exec -T mysql sh -c "$mysql_in_container"
  else
    ssh "$db_host" "cd /opt/gov-benefit/db && docker compose exec -T mysql sh -c '$mysql_in_container'"
  fi
}

count() {
  run_sql < "$script_dir/sql/count.sql"
}

# 남은 합성 데이터가 하나라도 있으면 1
leftover() {
  count | awk '$2 != 0 { found = 1 } END { exit !found }'
}

case $command in
  seed)
    users=${2:-10000}
    [[ $users =~ ^[1-9][0-9]*$ ]] && (( users <= 99999 )) || { echo "사용자 수는 1~99999입니다: $users" >&2; exit 2; }
    if leftover; then
      count >&2
      echo "지난 측정의 합성 데이터가 남아 있습니다. cleanup을 먼저 실행하세요." >&2
      exit 1
    fi
    now=$(TZ=Asia/Seoul date '+%Y-%m-%d %H:%M:%S')
    { printf "SET @now = '%s';\nSET @users = %d;\n" "$now" "$users"; cat "$script_dir/sql/seed.sql"; } | run_sql
    result=$(count)
    echo "$result"
    accounts=$(awk '$1 == "accounts" { print $2 }' <<< "$result")
    benefits=$(awk '$1 == "fake_benefits" { print $2 }' <<< "$result")
    if [[ $accounts != "$users" || $benefits != 3 ]]; then
      echo "합성 데이터가 기대와 다릅니다(계정 $users, 가짜 새 혜택 3). 지웁니다." >&2
      run_sql < "$script_dir/sql/cleanup.sql"
      exit 1
    fi
    ;;
  cleanup)
    run_sql < "$script_dir/sql/cleanup.sql"
    count
    ;;
  count)
    count
    ;;
  pending)
    run_sql < "$script_dir/sql/pending.sql"
    ;;
  status)
    run_sql < "$script_dir/sql/status.sql"
    ;;
  notify-off)
    [[ $# -eq 2 && -f $2 ]] || usage
    # 합성 사용자 주소만 받는다. 다른 주소가 섞이면 실행하지 않는다.
    if grep -vqE '^notify-[0-9]{5}@loadtest\.invalid$' "$2"; then
      echo "합성 사용자 주소가 아닌 줄이 있습니다: $2" >&2
      exit 1
    fi
    { printf "UPDATE user_account SET new_benefit_notify = FALSE WHERE email IN ("
      paste -sd, <(sed "s/.*/'&'/" "$2")
      printf ");\n"; } | run_sql
    ;;
  *)
    usage
    ;;
esac
