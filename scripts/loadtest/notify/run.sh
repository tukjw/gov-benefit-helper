#!/usr/bin/env bash
# 알림 발송 장애 측정(#137). 운영 app VM에서 합성 사용자에게 새 혜택 알림을 가짜 SES로 보내며 장애를 주입하고, 가짜 SES
# 기록과 묶음 상태를 시나리오마다 결과 디렉터리에 남긴다. 절차는 docs/ops/load-test.md의 "알림 발송 장애 측정".
# 사용법: scripts/loadtest/notify/run.sh <all | outage-1h | normal | kill | deploy | response-loss | outage-10m | account-mail | notify-off>
#   all  여덟 시나리오를 위 순서로 차례로 잰다. 끝나거나 중간에 멈추면 스스로 정리한다: 합성 데이터 지우기 → 0건 확인 →
#        switch.sh off → Tunnel 켜기. 합성 데이터가 남으면 측정용 실행 구성 그대로 멈춘다(실제 SES로 .invalid 주소에
#        보내지 않게). 결과는 <RESULTS_DIR>/<시각>-all/<시나리오>/, 요약은 <RESULTS_DIR>/<시각>-all/finish.txt.
#   <시나리오>  하나만 잰다. 결과는 <RESULTS_DIR>/<시각>-<시나리오>/. 합성 데이터는 지우고, 실행 구성과 Tunnel은 그대로 둔다.
# 부하 생성 VM의 저장소에서 tmux 안에서 실행한다. 가짜 SES를 이 기기에서 띄운다. 먼저 switch.sh on과 Tunnel 정지가 필요하다.
#
# 시나리오(시각은 발송 시작, 즉 가짜 SES의 첫 알림 요청 기준)
#   outage-1h      0~OUTAGE_LONG_MIN분 동안 모든 요청에 500 InternalFailure
#   normal         주입 없음
#   kill           KILL_MIN_S~KILL_MAX_S초 무작위 간격(SEED 고정)으로 KILLS번 SIGKILL. 그중 HOLD_KILLS번은 가짜 SES가 요청을
#                  접수로 기록하고 붙잡은 채 죽인다. 간격은 앞 회차에서 발송이 다시 시작된 때부터 잰다.
#   deploy         DEPLOY_AT_MIN분마다 같은 이미지로 app만 다시 만든다(측정용 실행 구성 유지)
#   response-loss  알림 요청 LATE_EVERY번째마다 LATE_S초 뒤에 200(앱의 응답 제한 10초보다 늦게)
#   outage-10m     0~OUTAGE_SHORT_MIN분 동안 500 InternalFailure
#   account-mail   MAIL_MINUTES분 동안 가입 인증번호 요청 초당 BASE_RATE건, PEAK_START_MIN분부터 PEAK_MIN분 동안 초당 PEAK_RATE건
#   notify-off     NOTIFY_OFF_USERS명(SEED 고정)의 알림을 0~NOTIFY_OFF_WINDOW_MIN분 사이 무작위 시각에 NOTIFY_OFF_BATCHES번에
#                  나눠 DB에서 끈다
# 시나리오는 합성 묶음에 발송 대기가 없고 가짜 SES가 IDLE_END_S초 동안 알림 요청을 받지 않으면 끝난다. 예상 시간의
# TIMEOUT_FACTOR배가 지나면 그 상태로 기록하고 끝낸다.
#
# 환경 변수(괄호는 기본값). lib.sh(APP_HOST, APP_DIR, FAKE_SES_*, LATENCY_FILE)와 data.sh(DB_HOST, LOADTEST_DB)도 본다.
#   USERS(10000)  SEED(137)  RESULTS_DIR(docs/research/notify-send-fault-baseline/results, 상대 경로는 저장소 루트 기준)
#   OUTAGE_LONG_MIN(60)  OUTAGE_SHORT_MIN(10)  KILLS(10)  HOLD_KILLS(2)  KILL_MIN_S(90)  KILL_MAX_S(150)
#   DEPLOY_AT_MIN("5 12 19")  LATE_EVERY(100)  LATE_S(15)
#   MAIL_MINUTES(30)  BASE_RATE(1)  PEAK_RATE(10)  PEAK_START_MIN(5)  PEAK_MIN(2)
#   NOTIFY_OFF_USERS(1000)  NOTIFY_OFF_BATCHES(100)  NOTIFY_OFF_WINDOW_MIN(20)
#   IDLE_END_S(120)  TIMEOUT_FACTOR(2)  EXPECTED_MIN(시나리오별 예상 시간을 모두 이 값으로, 리허설용)
#   K6_IMAGE(grafana/k6:2.3.0)  K6_NETWORK(host)  K6_BASE_URL(http://10.10.0.11:8080)
#   FINISH(all은 restore, 하나는 keep)  restore면 끝난 뒤 switch.sh off와 Tunnel 켜기까지 한다
set -euo pipefail

# 본문 전체를 한 묶음으로 읽은 뒤 실행한다. 측정 중에 이 파일이 바뀌어도(git pull 등) 실행 중인 측정이 깨지지 않는다.
{
source "$(cd "$(dirname "$0")" && pwd)/lib.sh"

all_scenarios=(outage-1h normal kill deploy response-loss outage-10m account-mail notify-off)
# 예상 시간(분). 로컬 리허설의 발송 속도(초당 약 6건, 1만 통 약 28분)로 잡았다. 시간 제한은 이 값의 TIMEOUT_FACTOR배다.
declare -A expected_min=([outage-1h]=120 [normal]=30 [kill]=45 [deploy]=40 [response-loss]=50 [outage-10m]=45
  [account-mail]=35 [notify-off]=35)

usage() { echo "사용법: $0 <all | outage-1h | normal | kill | deploy | response-loss | outage-10m | account-mail | notify-off>" >&2; exit 2; }
[[ $# -eq 1 ]] || usage
scenario=$1
[[ $scenario == all || -n ${expected_min[$scenario]:-} ]] || usage

users=${USERS:-10000}
seed=${SEED:-137}
results_dir=${RESULTS_DIR:-docs/research/notify-send-fault-baseline/results}
[[ $results_dir == /* ]] || results_dir=$repo_root/$results_dir
idle_end_s=${IDLE_END_S:-120}
timeout_factor=${TIMEOUT_FACTOR:-2}
k6_image=${K6_IMAGE:-grafana/k6:2.3.0}
data=$notify_dir/data.sh

timeout_min() {
  echo $(( ${EXPECTED_MIN:-${expected_min[$1]}} * timeout_factor ))
}

kst_minutes() {
  echo $(( 10#$(TZ=Asia/Seoul date +%H) * 60 + 10#$(TZ=Asia/Seoul date +%M) ))
}

# 시작 전 확인. 하나라도 어긋나면 시작하지 않는다.
preflight() {
  local budget=$1 name
  for name in jq python3 curl docker; do
    command -v "$name" > /dev/null || { echo "$name이 없습니다." >&2; exit 1; }
  done
  [[ -s $latency_file ]] || { echo "가짜 SES 지연 표본이 없습니다: $latency_file" >&2; exit 1; }
  if cloudflared_running; then
    echo "app VM의 cloudflared가 실행 중입니다. Tunnel을 먼저 멈추세요." >&2
    exit 1
  fi
  fault_mode_on || { echo "app이 측정용 실행 구성이 아닙니다. switch.sh on을 먼저 실행하세요." >&2; exit 1; }
  if "$data" count | awk '$2 != 0 { found = 1 } END { exit !found }'; then
    "$data" count >&2
    echo "합성 데이터가 남아 있습니다. data.sh cleanup을 먼저 실행하세요." >&2
    exit 1
  fi
  # 합성 묶음은 그날 24:00(KST)까지만 보낸다. 끝날 수 있는 가장 늦은 시각이 23:50을 넘으면 시작하지 않는다.
  if (( $(kst_minutes) + budget > 1430 )); then
    echo "최대 ${budget}분이 걸릴 수 있어 오늘 23:50 안에 끝나지 않습니다. 00:00 이후에 시작하세요." >&2
    exit 1
  fi
}

# --- all ---------------------------------------------------------------------------------------------------------

finish_all() {
  local rc=$? counts tunnel switched=no reason
  set +e
  trap - EXIT
  if [[ -n ${stopped_at:-} ]]; then reason="$stopped_at에서 멈췄다"; elif (( rc == 0 )); then reason="모두 마쳤다"; else reason="종료 코드 $rc"; fi
  log "정리: $reason"
  for _ in 1 2 3; do
    "$data" cleanup > /dev/null && break
    sleep 30
  done
  counts=$("$data" count 2>&1)
  if [[ ${FINISH:-restore} == restore ]]; then
    if awk '$2 != 0 { found = 1 } END { exit !found }' <<< "$counts" || ! grep -q accounts <<< "$counts"; then
      log "합성 데이터가 남았거나 세지 못해 측정용 실행 구성 그대로 둔다."
    elif "$notify_dir/switch.sh" off; then
      switched=yes
      on_app "docker compose start cloudflared" > /dev/null
    fi
  fi
  cloudflared_running && tunnel="실행 중" || tunnel=멈춤
  {
    echo "끝난 시각: $(TZ=Asia/Seoul date '+%F %T') KST"
    echo "결과: $reason"
    echo "마친 시나리오: ${done_scenarios[*]:-없음}"
    echo "건너뛴 시나리오: ${skipped_scenarios[*]:-없음}"
    echo "남은 합성 데이터:"
    sed 's/^/  /' <<< "$counts"
    echo "기본 실행 구성으로 되돌림(switch.sh off): $switched"
    echo "Tunnel: $tunnel"
    for name in "${done_scenarios[@]}"; do
      echo
      echo "## $name"
      cat "$run_root/$name/counts.txt" 2>/dev/null || echo "(집계 없음)"
    done
  } > "$run_root/finish.txt"
  log "요약: $run_root/finish.txt"
  exit "$rc"
}

run_all() {
  local budget=0 name
  for name in "${all_scenarios[@]}"; do budget=$(( budget + $(timeout_min "$name") )); done
  preflight "$budget"
  run_root=$results_dir/$(TZ=Asia/Seoul date +%Y%m%dT%H%M%S)-all
  mkdir -p "$run_root"
  exec > >(tee -a "$run_root/run.log") 2>&1
  done_scenarios=()
  skipped_scenarios=()
  stopped_at=
  trap finish_all EXIT
  trap 'exit 130' INT TERM HUP
  log "측정 시작: ${all_scenarios[*]}, 사용자 ${users}명 → $run_root"
  for name in "${all_scenarios[@]}"; do
    if [[ -n $stopped_at ]] || (( $(kst_minutes) + $(timeout_min "$name") > 1430 )); then
      skipped_scenarios+=("$name")
      continue
    fi
    if RUN_ROOT=$run_root "$0" "$name"; then
      done_scenarios+=("$name")
    else
      stopped_at=$name
    fi
  done
  [[ -z $stopped_at ]]
}

if [[ $scenario == all ]]; then
  run_all
  exit 0
fi

# --- 시나리오 하나 ---------------------------------------------------------------------------------------------------

if [[ -n ${RUN_ROOT:-} ]]; then
  out=$RUN_ROOT/$scenario
else
  preflight "$(timeout_min "$scenario")"
  out=$results_dir/$(TZ=Asia/Seoul date +%Y%m%dT%H%M%S)-$scenario
fi
mkdir -p "$out"
injections=$out/injections.tsv
printf 'round\tkind\tt_cmd\tt_done\tt_up\tdetail\n' > "$injections"
inj_pid=
k6_container=notify-account-mail
t_start=
t0=

record() {
  printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "${4:-}" "${5:-}" "${6:-}" >> "$injections"
}

# 가짜 SES가 최근 2초 안에 알림 요청을 받았을 때까지 기다린다(발송 중). 최대 인자초. 발송 중이 아니면 1
wait_sending() {
  local deadline last
  deadline=$(calc "$(now) + $1")
  while [[ $(calc "$(now) < $deadline") == 1 ]]; do
    last=$(ses_stat '.lastArrived.digest // 0')
    [[ $(calc "$(now) - $last < 2") == 1 ]] && return 0
    sleep 0.2
  done
  return 1
}

# 인자 시각 뒤의 첫 알림 요청을 기다린다(0.2초 간격으로 마지막 요청 시각을 본다). 본 시각을 출력한다.
wait_arrival_after() {
  local deadline last
  deadline=$(calc "$(now) + $2")
  while [[ $(calc "$(now) < $deadline") == 1 ]]; do
    last=$(ses_stat '.lastArrived.digest // 0')
    if [[ $(calc "$last > $1") == 1 ]]; then echo "$last"; return 0; fi
    sleep 0.2
  done
  return 1
}

# Docker가 다시 띄우도록 호스트 PID로 SIGKILL한다(docker kill은 수동 중지로 처리돼 다시 띄우지 않는다).
kill_app() {
  on_app "docker run --rm --pid=host alpine:3.20 kill -KILL \$(docker inspect -f '{{.State.Pid}}' \$(docker compose ps -aq app))"
}

inject_outage() {
  sleep_until "$(calc "$t0 + $1 * 60")"
  ses_ctl outage '{"on": false}'
  record - outage_off "$(now)"
  log "SES 장애를 끝냈다(${1}분)."
}

inject_kill() {
  local plan round interval type ref t_cmd t_done t_up t_resume
  plan=$(python3 -I -c "
import random
r = random.Random($seed)
n = ${KILLS:-10}
intervals = [r.uniform(${KILL_MIN_S:-90}, ${KILL_MAX_S:-150}) for _ in range(n)]
hold = set(r.sample(range(n), min(${HOLD_KILLS:-2}, n)))
for i in range(n):
    print(i + 1, f'{intervals[i]:.1f}', 'hold' if i in hold else 'kill')
")
  ref=$t0
  # 계획은 3번 파일 설명자로 읽는다. 루프 안의 명령이 표준 입력을 읽어도 남은 회차가 사라지지 않는다.
  while read -r round interval type <&3; do
    sleep_until "$(calc "$ref + $interval")"
    if ! wait_sending 180; then
      log "발송이 끝나 ${round}회차부터 강제 종료하지 않는다."
      break
    fi
    if [[ $type == hold ]]; then
      ses_ctl hold-next
      for _ in $(seq 300); do
        (( $(ses_stat '.held') >= 1 )) && break
        sleep 0.1
      done
      if (( $(ses_stat '.held') < 1 )); then
        log "${round}회차: 붙잡을 요청이 오지 않아 강제 종료를 그만둔다."
        break
      fi
    fi
    t_cmd=$(now)
    kill_app > /dev/null
    t_done=$(now)
    [[ $type == hold ]] && ses_ctl release
    t_up=$(wait_health 300) || t_up=
    record "$round" "$type" "$t_cmd" "$t_done" "$t_up" "interval=$interval"
    log "${round}회차 강제 종료($type), 다시 UP까지 $(calc "${t_up:-$t_done} - $t_cmd")초"
    t_resume=$(wait_arrival_after "${t_up:-$t_done}" 180) || t_resume=$(now)
    ref=$t_resume
  done 3<<< "$plan"
}

inject_deploy() {
  local round=0 minute cid follower t_cmd t_done t_up
  for minute in ${DEPLOY_AT_MIN:-5 12 19}; do
    round=$(( round + 1 ))
    sleep_until "$(calc "$t0 + $minute * 60")"
    # 다시 만들면 이전 컨테이너의 로그가 지워지므로, 끝날 때까지 따라가며 남긴다.
    cid=$(app_container)
    on_app "docker logs -f --since $t_start $cid" > "$out/app-log-part$round.txt" 2>&1 &
    follower=$!
    t_cmd=$(now)
    on_app "$compose_fault up -d --force-recreate app" > /dev/null 2>&1
    t_done=$(now)
    t_up=$(wait_health 300) || t_up=
    for _ in $(seq 60); do kill -0 "$follower" 2>/dev/null || break; sleep 1; done
    kill "$follower" 2>/dev/null || true
    record "$round" deploy "$t_cmd" "$t_done" "$t_up" "at_min=$minute"
    log "${round}회차 배포(${minute}분), 다시 UP까지 $(calc "${t_up:-$t_done} - $t_cmd")초"
  done
}

inject_notify_off() {
  local offset emails before after list
  list=$(mktemp "${TMPDIR:-/tmp}/notify-off.XXXXXX")
  printf 'email\tbefore\tafter\n' > "$out/notify-off.tsv"
  python3 -I -c "
import random
r = random.Random($seed)
users = r.sample(range(1, $users + 1), min(${NOTIFY_OFF_USERS:-1000}, $users))
batches = ${NOTIFY_OFF_BATCHES:-100}
offsets = sorted(r.uniform(0, ${NOTIFY_OFF_WINDOW_MIN:-20} * 60) for _ in range(batches))
for b in range(batches):
    part = users[b::batches]
    if part:
        print(f'{offsets[b]:.1f}', ' '.join(f'notify-{u:05d}@loadtest.invalid' for u in part))
" | while read -r offset emails; do
    sleep_until "$(calc "$t0 + $offset")"
    tr ' ' '\n' <<< "$emails" > "$list"
    before=$(now)
    "$data" notify-off "$list"
    after=$(now)
    awk -v b="$before" -v a="$after" '{ print $0 "\t" b "\t" a }' "$list" >> "$out/notify-off.tsv"
  done
  rm -f "$list"
  record - notify_off "$t0" "$(now)" "" "users=$(( $(wc -l < "$out/notify-off.tsv") - 1 ))"
  log "알림 끄기를 마쳤다."
}

inject_account_mail() {
  record - mail_start "$(now)"
  docker run --rm --name "$k6_container" --network "${K6_NETWORK:-host}" -u "$(id -u):$(id -g)" \
    -v "$notify_dir/k6:/k6:ro" -v "$out:/work" \
    -e BASE_URL="${K6_BASE_URL:-http://10.10.0.11:8080}" -e WORK_DIR=/work -e RUN_TAG="$(date +%H%M%S)" \
    -e MAIL_MINUTES="${MAIL_MINUTES:-30}" -e BASE_RATE="${BASE_RATE:-1}" -e PEAK_RATE="${PEAK_RATE:-10}" \
    -e PEAK_START_MIN="${PEAK_START_MIN:-5}" -e PEAK_MIN="${PEAK_MIN:-2}" \
    "$k6_image" run --quiet --no-color /k6/account-mail.js > "$out/k6.txt" 2>&1 || true
  record - mail_end "$(now)"
  log "계정 메일 요청을 마쳤다."
}

# 비정상 종료에도 가짜 SES·k6·주입 작업을 멈추고 합성 데이터를 지운다.
scenario_exit() {
  local rc=$?
  set +e
  trap - EXIT
  [[ -n $inj_pid ]] && kill "$inj_pid" 2>/dev/null
  docker stop "$k6_container" > /dev/null 2>&1
  stop_fake_ses
  if (( rc != 0 )); then
    log "$scenario 실패(종료 코드 $rc). 합성 데이터를 지운다."
    "$data" cleanup
  fi
  exit "$rc"
}
trap scenario_exit EXIT
trap 'exit 130' INT TERM HUP

# 1. 가짜 SES와 합성 데이터. 장애는 발송 전에 켜 둔다.
t_start=$(now)
start_fake_ses "$out/fake-ses.jsonl" "$out/fake-ses.out"
case $scenario in
  outage-*)
    ses_ctl outage '{"on": true, "status": 500, "code": "InternalFailure"}'
    record - outage_on "$(now)" "" "" "status=500 code=InternalFailure"
    ;;
  response-loss)
    ses_ctl late "{\"every\": ${LATE_EVERY:-100}, \"seconds\": ${LATE_S:-15}}"
    record - late_on "$(now)" "" "" "every=${LATE_EVERY:-100} seconds=${LATE_S:-15}"
    ;;
esac
log "$scenario: 합성 사용자 ${users}명을 넣는다 → $out"
"$data" seed "$users" > "$out/seed.txt"
t_seeded=$(now)

# 2. 발송 시작(가짜 SES의 첫 알림 요청)을 기다린다. 발송 작업은 30초마다 돈다.
for _ in $(seq 360); do
  t0=$(ses_stat '.firstArrived.digest // empty')
  [[ -n $t0 ]] && break
  sleep 0.5
done
[[ -n $t0 ]] || { echo "3분 안에 발송이 시작되지 않았습니다." >&2; exit 1; }
log "발송 시작($(calc "$t0 - $t_seeded")초 뒤)"

# 3. 장애 주입(백그라운드)
case $scenario in
  outage-1h) inject_outage "${OUTAGE_LONG_MIN:-60}" & inj_pid=$! ;;
  outage-10m) inject_outage "${OUTAGE_SHORT_MIN:-10}" & inj_pid=$! ;;
  kill) inject_kill & inj_pid=$! ;;
  deploy) inject_deploy & inj_pid=$! ;;
  account-mail) inject_account_mail & inj_pid=$! ;;
  notify-off) inject_notify_off & inj_pid=$! ;;
esac

# 4. 끝 조건: 주입이 끝났고, 발송 대기 합성 묶음이 0이고, 가짜 SES가 IDLE_END_S초 동안 알림 요청을 받지 않았다.
timeout_s=$(( $(timeout_min "$scenario") * 60 ))
end_reason=
while :; do
  sleep 15
  if [[ $(calc "$(now) - $t_start > $timeout_s") == 1 ]]; then
    end_reason=timeout
    log "예상 시간의 ${timeout_factor}배가 지나 이 상태로 끝낸다."
    break
  fi
  [[ -n $inj_pid ]] && kill -0 "$inj_pid" 2>/dev/null && continue
  pending=$("$data" pending) || continue
  last=$(ses_stat '.lastArrived.digest // 0')
  if [[ $pending == 0 && $(calc "$(now) - $last >= $idle_end_s") == 1 ]]; then
    end_reason=done
    break
  fi
done
t_end=$(now)
if [[ -n $inj_pid ]]; then
  kill "$inj_pid" 2>/dev/null || true
  docker stop "$k6_container" > /dev/null 2>&1 || true
  wait "$inj_pid" 2>/dev/null || true
  inj_pid=
fi

# 5. 결과: 묶음 상태, 앱 로그, 가짜 SES 기록, 실행 환경, 서버 지표, 집계
"$data" status > "$out/digests.tsv"
{
  cat "$out"/app-log-part*.txt 2>/dev/null || true
  on_app "docker logs --since $t_start --until $t_end \$(docker compose ps -aq app)" 2>&1
} > "$out/app-log.txt"
rm -f "$out"/app-log-part*.txt
stop_fake_ses
printf '%s\t-\t%s\t%s\n' "$scenario" "${t_start%.*}" "${t_end%.*}" > "$out/steps.tsv"

app_tag=$(on_app "sed -n 's/^APP_TAG=//p' .env")
config_sha=$(on_app "sha256sum compose.yaml $override" | awk '{ printf "%s%s=%s", sep, $2, $1; sep = " " }')
jq -n \
  --arg scenario "$scenario" --arg out "${out#"$repo_root"/}" --argjson users "$users" --argjson seed "$seed" \
  --arg appTag "$app_tag" --arg configSha256 "$config_sha" --arg fakeSesUrl "$fake_ses_url" \
  --arg latencyFile "${latency_file#"$repo_root"/}" --arg latencySha256 "$(openssl dgst -sha256 -r "$latency_file" | cut -d' ' -f1)" \
  --arg repoCommit "$(git -C "$repo_root" rev-parse HEAD)" \
  --argjson repoDirty "$( [[ -z $(git -C "$repo_root" status --porcelain -- scripts/loadtest) ]] && echo false || echo true )" \
  --argjson tStart "$t_start" --argjson tSeeded "$t_seeded" --argjson t0 "$t0" --argjson tEnd "$t_end" \
  --arg endReason "$end_reason" --argjson idleEndS "$idle_end_s" --argjson timeoutMin "$(timeout_min "$scenario")" \
  --arg params "OUTAGE_LONG_MIN=${OUTAGE_LONG_MIN:-60} OUTAGE_SHORT_MIN=${OUTAGE_SHORT_MIN:-10} KILLS=${KILLS:-10} \
HOLD_KILLS=${HOLD_KILLS:-2} KILL_MIN_S=${KILL_MIN_S:-90} KILL_MAX_S=${KILL_MAX_S:-150} DEPLOY_AT_MIN=$(tr ' ' , <<< "${DEPLOY_AT_MIN:-5 12 19}") \
LATE_EVERY=${LATE_EVERY:-100} LATE_S=${LATE_S:-15} MAIL_MINUTES=${MAIL_MINUTES:-30} BASE_RATE=${BASE_RATE:-1} \
PEAK_RATE=${PEAK_RATE:-10} PEAK_START_MIN=${PEAK_START_MIN:-5} PEAK_MIN=${PEAK_MIN:-2} \
NOTIFY_OFF_USERS=${NOTIFY_OFF_USERS:-1000} NOTIFY_OFF_BATCHES=${NOTIFY_OFF_BATCHES:-100} \
NOTIFY_OFF_WINDOW_MIN=${NOTIFY_OFF_WINDOW_MIN:-20}" \
  '{
    scenario: $scenario, out: $out, users: $users, seed: $seed,
    params: ($params | split(" ") | map(split("=") | {(.[0]): .[1]}) | add),
    app: {imageTag: $appTag, configSha256: $configSha256, fakeSesUrl: $fakeSesUrl},
    fakeSes: {latencyFile: $latencyFile, latencySha256: $latencySha256},
    repo: {commit: $repoCommit, dirty: $repoDirty},
    times: {start: $tStart, seeded: $tSeeded, firstRequest: $t0, end: $tEnd},
    endReason: $endReason, idleEndS: $idleEndS, timeoutMin: $timeoutMin
  }' > "$out/meta.json"

if [[ ${SKIP_METRICS:-} != 1 ]] && ! "$repo_root/scripts/loadtest/export-metrics.sh" "$out"; then
  log "서버 지표를 받지 못했다. 나중에 다시: scripts/loadtest/export-metrics.sh $out"
fi
python3 -I "$notify_dir/count.py" "$out" | tee "$out/counts.txt"
gzip -f "$out/fake-ses.jsonl" "$out/app-log.txt"

# 6. 합성 데이터 지우기
"$data" cleanup > "$out/cleanup.txt"
if awk '$2 != 0 { found = 1 } END { exit !found }' "$out/cleanup.txt"; then
  cat "$out/cleanup.txt" >&2
  echo "합성 데이터가 남았습니다." >&2
  exit 1
fi
# 시간이 넘어 끝낸 시나리오도 그 상태로 기록했으므로 실패로 보지 않는다(all은 다음 시나리오로 넘어간다).
log "$scenario 끝($end_reason): $out"
exit 0
}
