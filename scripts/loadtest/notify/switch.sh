#!/usr/bin/env bash
# 운영 앱을 알림 발송 장애 측정(#137)의 측정용 실행 구성으로 바꾸고 되돌린다. 절차는 docs/ops/load-test.md.
# 사용법: scripts/loadtest/notify/switch.sh <on | off | status>
#   on      실행 구성 파일 해시와 APP_TAG를 남기고, compose.notify-fault.yaml을 복사해 app만 다시 만든다(가짜 SES 주소,
#           가짜 접근 키, 예약 작업 끔). UP이 되면 합성 사용자 1명의 묶음을 가짜 SES가 접수하는지 확인하고 지운다.
#   off     합성 데이터가 0건일 때만 기본 실행 구성으로 app을 다시 만들고 덮어쓰기 파일을 지운다. 해시와 APP_TAG가
#           on 전과 같은지 본다. 합성 묶음이 남은 채 실제 SES로 돌아가면 .invalid 주소로 보내 반송이 생긴다.
#   status  지금 실행 구성(측정용인지, Tunnel, 덮어쓰기 파일)
# 가짜 SES를 이 기기에서 띄우므로 부하 생성 VM(리허설은 내 PC)에서 실행한다. Tunnel(cloudflared)은 건드리지 않는다.
# 환경 변수는 lib.sh와 data.sh를 본다.
set -euo pipefail

source "$(cd "$(dirname "$0")" && pwd)/lib.sh"

usage() { echo "사용법: $0 <on | off | status>" >&2; exit 2; }
[[ $# -eq 1 ]] || usage

before_file=$state_dir/notify-fault-before.txt

# 실행 구성 파일의 해시와 APP_TAG. 비밀값은 내보내지 않는다.
snapshot() {
  on_app "sha256sum app.env compose.yaml && sed -n 's/^APP_TAG=/APP_TAG=/p' .env"
}

# 합성 묶음 1건을 넣고 가짜 SES가 접수하기를 기다린다. 성공하면 0
smoke_check() {
  local log=$1 accepted
  "$notify_dir/data.sh" seed 1 > /dev/null || return 1
  log "가짜 SES가 합성 묶음 1건을 접수하는지 본다(발송은 30초마다)."
  for _ in $(seq 90); do
    accepted=$(ses_stat '.accepted.digest // 0') || return 1
    if (( accepted >= 1 )); then
      sleep 2
      log "접수 확인: $(jq -r 'select(.kind == "digest") | "\(.to) \(.result)"' "$log" | head -n1)"
      return 0
    fi
    sleep 1
  done
  echo "90초 안에 가짜 SES가 알림 요청을 받지 못했습니다. 앱 로그를 보세요." >&2
  return 1
}

smoke() {
  local work rc=0
  work=$(mktemp -d "${TMPDIR:-/tmp}/notify-switch.XXXXXX")
  start_fake_ses "$work/fake-ses.jsonl" "$work/fake-ses.out" || { rm -rf "$work"; return 1; }
  smoke_check "$work/fake-ses.jsonl" || rc=1
  stop_fake_ses
  "$notify_dir/data.sh" cleanup > /dev/null || rc=1
  rm -rf "$work"
  return "$rc"
}

case $1 in
  on)
    if [[ ! -e $before_file ]]; then
      mkdir -p "$state_dir"
      snapshot > "$before_file"
    fi
    copy_to_app "$notify_dir/$override" "$override"
    # 강제 종료에 쓰는 이미지를 미리 받아, 측정 중에 받는 시간이 섞이지 않게 한다.
    on_app "docker pull -q alpine:3.20 > /dev/null"
    log "측정용 실행 구성으로 app을 다시 만든다(가짜 SES $fake_ses_url)."
    on_app "$compose_fault up -d app"
    wait_health 300 > /dev/null || { echo "app이 UP이 되지 않았습니다." >&2; exit 1; }
    fault_mode_on || { echo "app 환경 변수에 측정용 값이 없습니다." >&2; exit 1; }
    smoke
    log "측정용 실행 구성이다. 끝나면 switch.sh off."
    ;;
  off)
    counts=$("$notify_dir/data.sh" count)
    if awk '$2 != 0 { found = 1 } END { exit !found }' <<< "$counts"; then
      echo "$counts" >&2
      echo "합성 데이터가 남아 있어 실제 SES로 되돌리지 않습니다. data.sh cleanup 뒤 다시 실행하세요." >&2
      exit 1
    fi
    log "기본 실행 구성으로 app을 다시 만든다."
    on_app "docker compose up -d app"
    wait_health 300 > /dev/null || { echo "app이 UP이 되지 않았습니다." >&2; exit 1; }
    on_app "rm -f $override"
    if [[ $(app_env_count "AUTH_MAIL_SES_ENDPOINT=$fake_ses_url") != 0 ]]; then
      echo "app 환경 변수에 측정용 SES 주소가 남아 있습니다." >&2
      exit 1
    fi
    if [[ -e $before_file ]]; then
      if ! diff <(snapshot) "$before_file"; then
        echo "실행 구성 파일이나 APP_TAG가 측정 전과 다릅니다(위 diff). 측정 전 값: $before_file" >&2
        exit 1
      fi
      rm -f "$before_file"
      log "실행 구성 파일 해시와 APP_TAG가 측정 전과 같다."
    else
      log "측정 전 해시 기록($before_file)이 없어 비교하지 않았다."
    fi
    log "기본 실행 구성이다. Tunnel은 따로 켠다."
    ;;
  status)
    fault_mode_on && echo "app: 측정용 실행 구성(가짜 SES $fake_ses_url)" || echo "app: 기본 실행 구성"
    cloudflared_running && echo "Tunnel: 실행 중" || echo "Tunnel: 멈춤"
    on_app "test -e $override" && echo "덮어쓰기 파일: 있음" || echo "덮어쓰기 파일: 없음"
    [[ -e $before_file ]] && echo "측정 전 기록: $before_file" || true
    ;;
  *)
    usage
    ;;
esac
