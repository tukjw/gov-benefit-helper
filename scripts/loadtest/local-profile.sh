#!/usr/bin/env bash
# 로컬 앱에 비회원 조건 비교 목록 요청을 보내며 요청 1건의 비용을 잰다. 운영 측정(run.sh)과 따로, 원인 확인과 개선 전후의
# 상대 비교에 쓴다. 절대값은 운영과 다르다.
# 사용법: scripts/loadtest/local-profile.sh <결과 디렉터리> <inputs.json> [요청 속도...(기본 1 4)]
#   앱(app.jar)은 같은 사용자로 미리 띄워 둔다. 초당 1건으로 1분 워밍업한 뒤, 속도마다 2분씩 잰다.
#   구간마다 JFR(profile 설정), 앞뒤 스레드 덤프, 앱 프로세스의 CPU 시간을 남기고 local-breakdown.py로 나눠 본다.
# 결과: rate-<속도>.json(k6 요약), rate-<속도>-breakdown.md, cpu.tsv(구간, 요청 수, 프로세스 CPU 초, 요청 1건당 CPU ms)
# 환경 변수: BASE_URL(http://localhost:8080)  DURATION(2m)  WARMUP(1m)
set -euo pipefail

[[ $# -ge 2 ]] || { echo "사용법: $0 <결과 디렉터리> <inputs.json> [요청 속도...]" >&2; exit 2; }
out=$1
inputs=$(cd "$(dirname "$2")" && pwd)/$(basename "$2")
shift 2
rates=("$@")
(( ${#rates[@]} > 0 )) || rates=(1 4)
here=$(cd "$(dirname "$0")" && pwd)
base_url=${BASE_URL:-http://localhost:8080}

pid=$(jcmd | awk '/app\.jar/ { print $1; exit }')
[[ -n $pid ]] || { echo "app.jar 프로세스를 찾지 못했습니다." >&2; exit 1; }
mkdir -p "$out"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
ln -s "$inputs" "$work/inputs.json"

# ps의 누적 CPU 시간([일-]시:분:초 또는 분:초)을 초로 바꾼다.
cpu_seconds() {
  ps -o time= -p "$pid" | awk '{ n = split($1, p, ":"); s = 0; for (i = 1; i <= n; i++) s = s * 60 + p[i]; print s }'
}

k6_step() {  # <단계 이름> <속도> <지속 시간>
  k6 run --quiet -e BASE_URL="$base_url" -e WORK_DIR="$work" -e STEP="$1" -e RATE="$2" -e DURATION="$3" \
    "$here/k6/guest-match.js"
}

k6_step warmup 1 "${WARMUP:-1m}"
printf '구간\t요청 수\tCPU 초\t요청 1건당 CPU ms\n' > "$out/cpu.tsv"
for rate in "${rates[@]}"; do
  step=rate-$rate
  jcmd "$pid" JFR.start name="$step" settings=profile > /dev/null
  jcmd "$pid" Thread.print > "$work/$step-threads-before.txt"
  before=$(cpu_seconds)
  k6_step "$step" "$rate" "${DURATION:-2m}"
  after=$(cpu_seconds)
  jcmd "$pid" Thread.print > "$work/$step-threads-after.txt"
  jcmd "$pid" JFR.stop name="$step" filename="$work/$step.jfr" > /dev/null
  cp "$work/$step.json" "$out/"
  requests=$(python3 -I -c 'import json, sys; print(json.load(open(sys.argv[1]))["metrics"]["http_reqs"]["values"]["count"])' \
    "$work/$step.json")
  awk -v s="$step" -v n="$requests" -v a="$before" -v b="$after" \
    'BEGIN { printf "%s\t%d\t%.1f\t%.0f\n", s, n, b - a, (b - a) * 1000 / n }' >> "$out/cpu.tsv"
  python3 -I "$here/local-breakdown.py" "$work/$step.jfr" "$work/$step-threads-before.txt" \
    "$work/$step-threads-after.txt" --requests "$requests" > "$out/$step-breakdown.md"
done
cat "$out/cpu.tsv"
