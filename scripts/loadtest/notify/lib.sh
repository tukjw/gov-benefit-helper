# 알림 발송 장애 측정(#137) 스크립트의 공통 함수. switch.sh와 run.sh가 source한다. 직접 실행하지 않는다.
# 가짜 SES는 이 스크립트를 실행하는 기기(부하 생성 VM, 리허설은 내 PC)에서 띄운다.
#
# 환경 변수(괄호는 기본값)
#   APP_HOST(govbenefit-app)   local이면 SSH 없이 이 기기에서 실행한다(리허설)
#   APP_DIR(/opt/gov-benefit/app)  app VM의 실행 구성 디렉터리
#   FAKE_SES_BIND(10.10.0.14)  FAKE_SES_PORT(8025)  FAKE_SES_CONTROL_PORT(8026)
#   FAKE_SES_URL(http://<FAKE_SES_BIND>:<FAKE_SES_PORT>)  앱이 SES 대신 부를 주소
#   LATENCY_FILE(docs/research/notify-send-fault-baseline/results/ses-probe/latency-ms.txt)

notify_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd "$notify_dir/../../.." && pwd)
app_host=${APP_HOST:-govbenefit-app}
app_dir=${APP_DIR:-/opt/gov-benefit/app}
fake_ses_bind=${FAKE_SES_BIND:-10.10.0.14}
fake_ses_port=${FAKE_SES_PORT:-8025}
control_port=${FAKE_SES_CONTROL_PORT:-8026}
fake_ses_url=${FAKE_SES_URL:-http://$fake_ses_bind:$fake_ses_port}
latency_file=${LATENCY_FILE:-$repo_root/docs/research/notify-send-fault-baseline/results/ses-probe/latency-ms.txt}
state_dir=${XDG_STATE_HOME:-$HOME/.local/state}/gov-benefit-loadtest
override=compose.notify-fault.yaml
compose_fault="NOTIFY_FAKE_SES_URL=$fake_ses_url docker compose -f compose.yaml -f $override"
fake_ses_pid=

# epoch 초(소수점 6자리)
now() {
  if [[ -n ${EPOCHREALTIME:-} ]]; then echo "$EPOCHREALTIME"; else python3 -c 'import time; print(f"{time.time():.6f}")'; fi
}

# 실수 비교·계산. 예: calc '1.5 > 1', calc '2.5 - 1'
calc() {
  awk "BEGIN { v = $1; if (v == 1 || v == 0) print v; else printf \"%.6f\n\", v }"
}

# 시각(epoch 초)까지 기다린다. 이미 지났으면 바로 돌아온다.
sleep_until() {
  local wait
  wait=$(calc "$1 - $(now)")
  if [[ $(calc "$wait > 0") == 1 ]]; then sleep "$wait"; fi
}

log() {
  echo "[$(TZ=Asia/Seoul date '+%H:%M:%S')] $*"
}

# app VM의 실행 구성 디렉터리에서 명령을 실행한다.
on_app() {
  local command="cd $app_dir && $1"
  if [[ $app_host == local ]]; then bash -c "$command"; else ssh "$app_host" "$command"; fi
}

copy_to_app() {
  if [[ $app_host == local ]]; then cp "$1" "$app_dir/$2"; else scp -q "$1" "$app_host:$app_dir/$2"; fi
}

app_container() {
  on_app "docker compose ps -aq app"
}

# 앱 컨테이너 환경 변수 중 그 줄과 똑같은 줄의 수. 환경 변수 전체(비밀값)는 app VM 밖으로 내보내지 않는다.
app_env_count() {
  on_app "docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' \$(docker compose ps -aq app) | grep -cxF '$1' || true"
}

# 측정용 실행 구성이면 0
fault_mode_on() {
  [[ $(app_env_count "AUTH_MAIL_SES_ENDPOINT=$fake_ses_url") == 1 && $(app_env_count "GOV24_SCHEDULE_ENABLED=false") == 1 ]]
}

cloudflared_running() {
  on_app "docker compose ps --status running --services" | grep -qx cloudflared
}

# 앱이 UP이 될 때까지 기다리고 UP을 본 시각을 출력한다. 시간이 넘으면 실패한다.
wait_health() {
  local timeout=${1:-300} deadline
  deadline=$(calc "$(now) + $timeout")
  while [[ $(calc "$(now) < $deadline") == 1 ]]; do
    if on_app "curl -sf --max-time 2 http://127.0.0.1:8081/actuator/health" 2>/dev/null | grep -q '"UP"'; then
      now
      return 0
    fi
    sleep 1
  done
  return 1
}

# 가짜 SES 관리 경로
ses_ctl() {
  curl -fsS --max-time 5 -X POST "http://127.0.0.1:$control_port/$1" -H 'Content-Type: application/json' -d "${2:-"{}"}" > /dev/null
}

ses_stats() {
  curl -fsS --max-time 5 "http://127.0.0.1:$control_port/stats"
}

# 통계의 값 하나. 예: ses_stat '.lastArrived.digest // 0'
ses_stat() {
  ses_stats | jq -r "$1"
}

# 가짜 SES를 띄운다. 인자: 기록 파일, 출력 파일
start_fake_ses() {
  if curl -s --max-time 2 "http://127.0.0.1:$control_port/stats" > /dev/null; then
    echo "가짜 SES 관리 포트($control_port)가 이미 쓰이고 있습니다. 남은 가짜 SES를 멈추세요." >&2
    return 1
  fi
  python3 -I "$notify_dir/fake_ses.py" --host "$fake_ses_bind" --port "$fake_ses_port" --control-port "$control_port" \
    --log "$1" --latency "$latency_file" > "$2" 2>&1 &
  fake_ses_pid=$!
  for _ in $(seq 50); do
    ses_stats > /dev/null 2>&1 && return 0
    kill -0 "$fake_ses_pid" 2>/dev/null || break
    sleep 0.2
  done
  cat "$2" >&2
  echo "가짜 SES를 띄우지 못했습니다." >&2
  return 1
}

stop_fake_ses() {
  [[ -n $fake_ses_pid ]] || return 0
  ses_ctl release 2>/dev/null || true
  kill "$fake_ses_pid" 2>/dev/null || true
  wait "$fake_ses_pid" 2>/dev/null || true
  fake_ses_pid=
}
