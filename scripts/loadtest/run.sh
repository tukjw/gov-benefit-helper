#!/usr/bin/env bash
# 시나리오 하나를 요청 속도 단계마다 부하 VM에서 실행하고, 결과와 같은 구간의 서버 지표를 저장소로 가져온다(결정 0037).
# 사용법: scripts/loadtest/run.sh <search | detail | guest-match | login | mixed> <요청 속도>...
#   예: scripts/loadtest/run.sh search 5 10 20 40 80 160
# 부하 VM의 저장소에서 tmux 안에서 실행한다. 내 PC의 연결이 끊겨도 측정이 계속된다. 내 PC의 저장소 루트에서도 실행할 수 있다.
# 절차(테스트 계정, API 막기, 끝난 뒤 정리)는 docs/ops/load-test.md.
# 결과는 docs/research/load-test-baseline/results/<시각>-<시나리오>/에 쓴다.
# 단계마다 한계 판정 기준(결정 0037)을 계산해, 한계 단계가 나오면 남은 단계를 실행하지 않는다.
#
# 환경 변수(괄호는 기본값)
#   STEP_MINUTES(3)  WARMUP_MINUTES(3)  WARMUP_RATE(5)  MAX_VUS(1000, 로그인을 보내면 계정 수 1,000 이하)
#   BASE_P95_MS      p95 기준. 없으면 이번 실행의 첫 단계 p95다. 세밀 단계를 다시 잴 때 거친 단계 첫 단계의 값을 준다.
#   INPUTS           지난 결과의 inputs.json. 주면 같은 입력으로 다시 잰다. 없으면 prepare.py로 새로 만든다.
#   LOADGEN_HOST(govbenefit-loadgen)  APP_HOST(govbenefit-app)  DB_HOST(govbenefit-db)  METRICS_HOST(govbenefit-metrics)
#                    LOADGEN_HOST가 local이면 부하 VM 명령을 SSH 없이 이 기기에서 실행한다. 부하 VM(내부 브리지 주소
#                    10.10.0.14가 있는 기기)에서 실행하면 기본값이 local이다.
#   LOADGEN_DIR(/opt/gov-benefit/loadtest)  부하 VM에서 스크립트와 단계 결과를 두는 디렉터리
#   PASSWORD_FILE    accounts.sh seed가 만든 비밀번호 파일(accounts.sh와 같은 기본 경로)
set -euo pipefail

usage() { echo "사용법: $0 <search | detail | guest-match | login | mixed> <요청 속도>..." >&2; exit 2; }
[[ $# -ge 2 ]] || usage
scenario=$1
shift
rates=("$@")

script_dir=$(cd "$(dirname "$0")" && pwd)
repo_root=$(cd "$script_dir/../.." && pwd)
k6_image=grafana/k6:2.3.0
step_minutes=${STEP_MINUTES:-3}
warmup_minutes=${WARMUP_MINUTES:-3}
warmup_rate=${WARMUP_RATE:-5}
max_vus=${MAX_VUS:-1000}
# 부하 VM에서 실행하면 부하 VM 명령을 SSH 없이 바로 실행한다. 내 PC(macOS)의 hostname에는 -I가 없어 거짓이 된다.
if [[ -z ${LOADGEN_HOST:-} ]] && hostname -I 2>/dev/null | tr ' ' '\n' | grep -qx 10.10.0.14; then
  loadgen_host=local
else
  loadgen_host=${LOADGEN_HOST:-govbenefit-loadgen}
fi
app_host=${APP_HOST:-govbenefit-app}
db_host=${DB_HOST:-govbenefit-db}
password_file=${PASSWORD_FILE:-${XDG_STATE_HOME:-$HOME/.local/state}/gov-benefit-loadtest/password}
remote_root=${LOADGEN_DIR:-/opt/gov-benefit/loadtest}

# 호스트에서 명령을 실행한다. local이면 SSH 없이 이 기기에서 실행한다.
on_host() {
  if [[ $1 == local ]]; then bash -c "$2"; else ssh "$1" "$2"; fi
}

# 로컬 파일을 부하 VM으로 복사한다. 첫 인자가 부하 VM의 대상 경로다.
copy_to_loadgen() {
  local dest=$1
  shift
  if [[ $loadgen_host == local ]]; then cp -R "$@" "$dest"; else scp -q -r "$@" "$loadgen_host:$dest"; fi
}

# 1. 입력 확인
case $scenario in
  search | detail | guest-match) password_file= ;;
  login | mixed)
    [[ -f $password_file ]] || { echo "테스트 계정 비밀번호가 없습니다. scripts/loadtest/accounts.sh seed를 먼저 실행하세요." >&2; exit 1; }
    (( max_vus <= 1000 )) || { echo "로그인을 보내는 시나리오는 MAX_VUS가 테스트 계정 수(1,000) 이하여야 합니다." >&2; exit 1; }
    ;;
  *) usage ;;
esac
seen=" "
for rate in "${rates[@]}" "$warmup_rate"; do
  [[ $rate =~ ^[1-9][0-9]*$ ]] || { echo "요청 속도는 양의 정수입니다: $rate" >&2; exit 2; }
done
for rate in "${rates[@]}"; do
  [[ $seen != *" $rate "* ]] || { echo "같은 요청 속도가 두 번 있습니다: $rate" >&2; exit 2; }
  seen+="$rate "
done

# 2. 04:00 예약 수집은 공공 API를 부른다. 측정이 03:30~05:00(KST)과 겹치면 시작하지 않는다.
estimate=$(( warmup_minutes + ${#rates[@]} * (step_minutes + 1) + 5 ))
now=$(( 10#$(TZ=Asia/Seoul date +%H) * 60 + 10#$(TZ=Asia/Seoul date +%M) ))
for (( minute = now; minute <= now + estimate; minute++ )); do
  if (( minute % 1440 >= 210 && minute % 1440 < 300 )); then
    echo "측정이 예약 수집 시간대(03:30~05:00)와 겹칩니다(예상 ${estimate}분). 시간을 옮기세요." >&2
    exit 1
  fi
done

# 3. 측정 동안 API는 막혀 있어야 한다(cloudflared 중지). 실제 요청이 섞이지 않고 Cloudflare를 거치는 경로가 없다.
# 먼저 받아 두어, app VM에 닿지 못하면 막혀 있다고 오판하지 않고 멈춘다.
running=$(ssh "$app_host" "cd /opt/gov-benefit/app && docker compose ps --status running --services")
if grep -qx cloudflared <<< "$running"; then
  echo "app VM의 cloudflared가 실행 중입니다. docs/ops/load-test.md의 'API 막기'를 먼저 하세요." >&2
  exit 1
fi

run_id=$(TZ=Asia/Seoul date +%Y%m%dT%H%M%S)-$scenario
out=$repo_root/docs/research/load-test-baseline/results/$run_id
work=$remote_root/runs/$run_id
mkdir -p "$out"
echo "측정: $scenario, 단계 ${rates[*]}(/s), 단계마다 ${step_minutes}분, 워밍업 ${warmup_rate}/s ${warmup_minutes}분 → $out"

# 4. 스크립트 복사와 입력 준비
on_host "$loadgen_host" "rm -rf $remote_root/k6 && mkdir -p $work"
copy_to_loadgen "$remote_root/" "$script_dir/k6" "$script_dir/step.sh" "$script_dir/prepare.py"
if [[ -n ${INPUTS:-} ]]; then
  copy_to_loadgen "$work/inputs.json" "$INPUTS"
else
  on_host "$loadgen_host" "python3 -I $remote_root/prepare.py --out $work/inputs.json"
fi

# 5. 실행 환경 기록: 이미지 태그, 실행 구성, VM 사양. 사양을 읽지 못하면 null로 남기고 측정은 계속한다.
vm_spec() {
  local vcpu mem
  read -r vcpu mem <<< "$(on_host "$1" "echo \$(nproc) \$(awk '/MemTotal/ {print int(\$2 / 1024)}' /proc/meminfo)" || true)"
  [[ $vcpu =~ ^[0-9]+$ ]] || vcpu=null
  [[ $mem =~ ^[0-9]+$ ]] || mem=null
  echo "$vcpu $mem"
}
read -r app_vcpu app_mem <<< "$(vm_spec "$app_host")"
read -r db_vcpu db_mem <<< "$(vm_spec "$db_host")"
read -r loadgen_vcpu loadgen_mem <<< "$(vm_spec "$loadgen_host")"
app_tag=$(ssh "$app_host" "sed -n 's/^APP_TAG=//p' /opt/gov-benefit/app/.env")
app_compose_sha=$(ssh "$app_host" "sha256sum /opt/gov-benefit/app/compose.yaml" | cut -d' ' -f1)
repo_compose_sha=$(openssl dgst -sha256 -r "$repo_root/deploy/app/compose.yaml" | cut -d' ' -f1)
repo_commit=$(git -C "$repo_root" rev-parse HEAD)
repo_dirty=$( [[ -z $(git -C "$repo_root" status --porcelain -- scripts/loadtest deploy/app) ]] && echo false || echo true )

# 6. 단계 실행. 실패해도 그때까지의 결과는 가져온다.
run_step() {
  on_host "$loadgen_host" "bash $remote_root/step.sh $work $k6_image $scenario $1 $2 ${3}m $max_vus" < "${password_file:-/dev/null}"
}

# 단계 요약으로 한계 판정 기준(결정 0037)을 계산한다. 출력: "<p95 ms> <사유, 없으면 빈 값>"
judge() {
  local rate=$1 base=$2
  on_host "$loadgen_host" "cat $work/rate-$rate.json" | jq -r \
    --argjson rate "$rate" --argjson seconds "$(( step_minutes * 60 ))" --argjson base "${base:-null}" '
    (.metrics.http_reqs.values.count // 0) as $count
    | (.metrics.dropped_iterations.values.count // 0) as $dropped
    | (.metrics.http_req_failed.values.rate // 0) as $failed
    | (.metrics.http_req_duration.values["p(95)"] // 0) as $p95
    | [ if $count < 0.95 * $rate * $seconds then "응답 수가 목표의 95% 미만" else empty end,
        if $dropped > 0 then "못 보낸 요청 \($dropped)건" else empty end,
        if $failed > 0.01 then "오류율 1% 초과" else empty end,
        if $base != null and $p95 > 2 * $base and $p95 - $base >= 50
          then "p95가 기준(\($base | floor)ms)의 2배 초과" else empty end ]
    | "\($p95) \(join(", "))"'
}

status=0
base_p95=${BASE_P95_MS:-}
limit_step=
limit_reasons=
run_step warmup "$warmup_rate" "$warmup_minutes" || status=$?
if (( status == 0 )); then
  for rate in "${rates[@]}"; do
    run_step "rate-$rate" "$rate" "$step_minutes" || { status=$?; break; }
    # 판정하지 못하면 한계를 넘었는지 모르므로 더 올리지 않는다.
    verdict=$(judge "$rate" "$base_p95") || { echo "rate-$rate의 요약을 판정하지 못했습니다." >&2; status=1; break; }
    [[ -n $base_p95 ]] || base_p95=${verdict%% *}
    if [[ -n ${verdict#* } ]]; then
      limit_step=rate-$rate
      limit_reasons=${verdict#* }
      echo "한계 단계: $limit_step($limit_reasons). 남은 단계는 실행하지 않는다."
      break
    fi
  done
fi

# 7. 결과 가져오기
if [[ $loadgen_host == local ]]; then cp -R "$work/." "$out/"; else scp -q -r "$loadgen_host:$work/*" "$out/"; fi
on_host "$loadgen_host" "rm -rf $work"
if [[ ! -s $out/steps.tsv ]]; then
  echo "끝난 단계가 없습니다." >&2
  exit 1
fi
first_start=$(head -n1 "$out/steps.tsv" | cut -f3)
last_end=$(tail -n1 "$out/steps.tsv" | cut -f4)

# 8. 측정 구간의 앱 로그에서 수집·메일 기록을 찾는다. 비어 있어야 한다.
{
  echo "# $first_start ~ $last_end(epoch 초) 앱 로그에서 '수집|메일'을 찾은 결과. 아래가 비어 있으면 기록이 없다."
  ssh "$app_host" "cd /opt/gov-benefit/app && docker compose logs --no-color --no-log-prefix --since $first_start --until $last_end app" \
    | grep -E '수집|메일' || true
} > "$out/app-log-check.txt"

jq -n \
  --arg scenario "$scenario" --arg runId "$run_id" --arg k6Image "$k6_image" \
  --argjson stepMinutes "$step_minutes" --argjson warmupMinutes "$warmup_minutes" --argjson warmupRate "$warmup_rate" \
  --argjson maxVus "$max_vus" --arg appTag "$app_tag" --arg appComposeSha256 "$app_compose_sha" \
  --arg repoCommit "$repo_commit" --argjson repoDirty "$repo_dirty" --arg repoComposeSha256 "$repo_compose_sha" \
  --argjson appVcpu "$app_vcpu" --argjson appMem "$app_mem" --argjson dbVcpu "$db_vcpu" --argjson dbMem "$db_mem" \
  --argjson loadgenVcpu "$loadgen_vcpu" --argjson loadgenMem "$loadgen_mem" \
  --argjson benefitCount "$(jq '.benefitCount' "$out/inputs.json")" \
  --arg baseP95 "$base_p95" --arg limitStep "$limit_step" --arg limitReasons "$limit_reasons" \
  --rawfile steps "$out/steps.tsv" \
  '{
    scenario: $scenario, runId: $runId, k6Image: $k6Image,
    stepMinutes: $stepMinutes, warmup: {rate: $warmupRate, minutes: $warmupMinutes}, maxVus: $maxVus,
    baseP95Ms: (if $baseP95 == "" then null else ($baseP95 | tonumber) end),
    limit: (if $limitStep == "" then null else {step: $limitStep, reasons: $limitReasons} end),
    app: {imageTag: $appTag, composeSha256: $appComposeSha256},
    repo: {commit: $repoCommit, dirty: $repoDirty, composeSha256: $repoComposeSha256},
    vms: {app: {vcpu: $appVcpu, memMiB: $appMem}, db: {vcpu: $dbVcpu, memMiB: $dbMem},
          loadgen: {vcpu: $loadgenVcpu, memMiB: $loadgenMem}},
    benefitCount: $benefitCount,
    steps: [$steps | split("\n")[] | select(length > 0) | split("\t")
            | {name: .[0], rate: (.[1] | tonumber), start: (.[2] | tonumber), end: (.[3] | tonumber)}]
  }' > "$out/meta.json"

# 9. 같은 구간의 서버 지표
if ! "$script_dir/export-metrics.sh" "$out"; then
  echo "서버 지표를 받지 못했습니다. 나중에 다시: scripts/loadtest/export-metrics.sh $out" >&2
  (( status != 0 )) || status=1
fi

if (( status != 0 )); then
  echo "단계 실행이 실패했습니다(종료 코드 $status). 끝난 단계까지의 결과: $out" >&2
  exit "$status"
fi
echo "완료: $out"
echo "측정을 모두 마쳤으면 docs/ops/load-test.md의 '끝난 뒤'를 따른다(계정 지우기 → API 풀기)."
