#!/usr/bin/env bash
# 부하 VM에서 요청 속도 단계 하나를 실행한다(결정 0037). run.sh가 부하 VM에 복사해 부른다. 직접 실행하지 않는다.
# 사용법: step.sh <작업 디렉터리> <k6 이미지> <시나리오> <단계 이름> <요청 속도> <단계 길이> <가상 사용자 상한>
# 테스트 계정 비밀번호는 표준 입력 첫 줄로 받는다. 명령줄과 프로세스 목록에 보이지 않게 환경 변수로만 넘긴다.
# BASE_URL 환경 변수가 있으면 k6에 넘긴다(없으면 k6/lib.js의 기본값, 운영 app VM). 스크립트를 로컬에서 확인할 때 쓴다.
# 단계 동안 vmstat을 남기고, 단계 이름·요청 속도·시작·끝 시각(epoch 초)을 <작업 디렉터리>/steps.tsv에 더한다.
set -euo pipefail

[[ $# -eq 7 ]] || { echo "사용법: $0 <작업 디렉터리> <k6 이미지> <시나리오> <단계 이름> <요청 속도> <단계 길이> <가상 사용자 상한>" >&2; exit 2; }
work=$1 image=$2 scenario=$3 step=$4 rate=$5 duration=$6 max_vus=$7
k6_dir=$(cd "$(dirname "$0")" && pwd)/k6

LOADTEST_PASSWORD=
IFS= read -r LOADTEST_PASSWORD || true
export LOADTEST_PASSWORD

vmstat -n -t 5 > "$work/vmstat-$step.txt" &
vmstat_pid=$!
trap 'kill $vmstat_pid 2>/dev/null || true' EXIT

start=$(date +%s)
docker run --rm --network host -u "$(id -u):$(id -g)" \
  -v "$k6_dir:/k6:ro" -v "$work:/work" \
  -e LOADTEST_PASSWORD -e BASE_URL -e WORK_DIR=/work -e STEP="$step" -e RATE="$rate" -e DURATION="$duration" -e MAX_VUS="$max_vus" \
  "$image" run --quiet --no-color "/k6/$scenario.js"
end=$(date +%s)

printf '%s\t%s\t%s\t%s\n' "$step" "$rate" "$start" "$end" >> "$work/steps.tsv"
