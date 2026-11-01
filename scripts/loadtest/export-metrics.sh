#!/usr/bin/env bash
# 측정 구간의 서버 지표를 Prometheus에서 받아 결과 디렉터리에 저장한다(결정 0037). run.sh가 끝에 부른다.
# 사용법: scripts/loadtest/export-metrics.sh <결과 디렉터리>
# <결과 디렉터리>/steps.tsv의 단계 구간마다 queries.tsv의 쿼리를 query_range(15초 간격)로 받고, 범위가 all인 쿼리는 측정 전체
# 구간으로 받는다. 응답 원본을 prometheus.json 하나에 {"<단계 | all>": {"<이름>": 응답}} 형태로 쓴다. 쿼리 하나가 한 줄이다.
# 쿼리마다 파일을 나누면 결과 하나에 파일이 수백 개 생긴다.
# Prometheus는 지표 VM의 127.0.0.1:9090에만 열려 있어(결정 0037) SSH 터널(METRICS_HOST, 기본 govbenefit-metrics)로 읽는다.
set -euo pipefail

[[ $# -eq 1 ]] || { echo "사용법: $0 <결과 디렉터리>" >&2; exit 2; }
out=$1
[[ -s $out/steps.tsv ]] || { echo "$out/steps.tsv가 없습니다." >&2; exit 1; }

script_dir=$(cd "$(dirname "$0")" && pwd)
metrics_host=${METRICS_HOST:-govbenefit-metrics}
local_port=${PROMETHEUS_LOCAL_PORT:-19090}
socket=$(mktemp -u "${TMPDIR:-/tmp}/gbh-prometheus.XXXXXX")
responses=$(mktemp "${TMPDIR:-/tmp}/gbh-prometheus-responses.XXXXXX")

ssh -M -S "$socket" -fN -o ExitOnForwardFailure=yes -L "$local_port:127.0.0.1:9090" "$metrics_host"
trap 'ssh -S "$socket" -O exit "$metrics_host" 2>/dev/null || true; rm -f "$responses"' EXIT

# 범위(step | all)에 맞는 쿼리를 구간 하나로 받아, 키와 함께 한 줄씩 덧붙인다.
export_range() {
  local scope=$1 start=$2 end=$3 key=$4 name query_scope query
  while IFS=$'\t' read -r name query_scope query; do
    [[ -z $name || $name == \#* || $query_scope != "$scope" ]] && continue
    curl -fsS -G "http://127.0.0.1:$local_port/api/v1/query_range" \
      --data-urlencode "query=$query" --data-urlencode "start=$start" --data-urlencode "end=$end" \
      --data-urlencode "step=15s" | jq -c --arg key "$key" --arg name "$name" '{key: $key, name: $name, response: .}' >> "$responses"
  done < "$script_dir/queries.tsv"
}

while IFS=$'\t' read -r step _ start end; do
  export_range step "$start" "$end" "$step"
done < "$out/steps.tsv"
export_range all "$(head -n1 "$out/steps.tsv" | cut -f3)" "$(tail -n1 "$out/steps.tsv" | cut -f4)" all

jq -rs '
  reduce .[] as $r ({}; .[$r.key][$r.name] = $r.response)
  | "{",
    (to_entries | map("  \(.key | tojson): {\n"
      + (.value | to_entries | map("    \(.key | tojson): \(.value | tojson)") | join(",\n"))
      + "\n  }") | join(",\n")),
    "}"' "$responses" > "$out/prometheus.json"

echo "서버 지표: $out/prometheus.json"
