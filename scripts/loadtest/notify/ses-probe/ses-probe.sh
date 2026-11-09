#!/usr/bin/env bash
# 실제 SES 사전 측정(#137). 메일박스 시뮬레이터로만 보내 응답 지연과 속도 초과 응답을 잰다. 절차는 docs/ops/load-test.md.
# 사용법:
#   ses-probe.sh local          내 PC에서 실행한다. SES_ACCESS_KEY_ID·SES_SECRET_ACCESS_KEY·SES_REGION·SES_FROM을 환경 변수로 준다.
#   ses-probe.sh app-vm         app VM에서 운영 앱 이미지와 app.env로 실행하고 결과를 가져온다. 키는 VM 밖으로 나오지 않는다.
#                               실행 중인 앱은 건드리지 않는다. 측정용 실행 구성(switch.sh on)으로 바꾸기 전에 실행한다.
#   ses-probe.sh latency <results.jsonl>
#                               결과의 지연 단계 성공 지연(ms)을 가짜 SES의 지연 표본(latency-ms.txt)으로 쓴다.
# 내 PC에서 실행한다(JDK 21과 backend의 Gradle이 필요하다). 기본 모드는 지연 측정(차례로 LATENCY_COUNT건)과 몰아 보내기
# (BURST_COUNT건)이고, app-vm은 몰아 보내기를 하지 않는다(BURST_COUNT=0). MODE=sustained는 SesRealProbe.java를 본다.
#
# 환경 변수(괄호는 기본값)
#   OUT              결과 디렉터리(docs/research/notify-send-fault-baseline/results/ses-probe/<날짜>-<local | app-vm>)
#   APP_HOST(govbenefit-app)  APP_DIR(/opt/gov-benefit/app)
set -euo pipefail

usage() { echo "사용법: $0 <local | app-vm | latency <results.jsonl>>" >&2; exit 2; }
[[ $# -ge 1 ]] || usage

script_dir=$(cd "$(dirname "$0")" && pwd)
repo_root=$(cd "$script_dir/../../../.." && pwd)
probe_root=$repo_root/docs/research/notify-send-fault-baseline/results/ses-probe
build=$repo_root/backend/build/ses-probe
app_host=${APP_HOST:-govbenefit-app}
app_dir=${APP_DIR:-/opt/gov-benefit/app}
remote=/opt/gov-benefit/loadtest/ses-probe

# backend의 런타임 클래스패스(AWS SDK 포함). 앱과 같은 SDK 버전으로 잰다.
runtime_classpath() {
  local init
  init=$(mktemp "${TMPDIR:-/tmp}/ses-probe-cp.XXXXXX.gradle")
  cat > "$init" <<'EOF'
allprojects {
    tasks.register('printRuntimeCp') {
        doLast { println "RUNTIME_CP=" + project.sourceSets.main.runtimeClasspath.asPath }
    }
}
EOF
  (cd "$repo_root/backend" && ./gradlew -q --init-script "$init" printRuntimeCp) | sed -n 's/^RUNTIME_CP=//p'
  rm -f "$init"
}

case $1 in
  local)
    [[ $# -eq 1 ]] || usage
    out=${OUT:-$probe_root/$(TZ=Asia/Seoul date +%F)-local}
    cp=$(runtime_classpath)
    PROBE_OUT=$out java -cp "$cp" "$script_dir/SesRealProbe.java"
    echo "결과: $out"
    ;;
  app-vm)
    [[ $# -eq 1 ]] || usage
    out=${OUT:-$probe_root/$(TZ=Asia/Seoul date +%F)-app-vm}
    cp=$(runtime_classpath)
    rm -rf "$build" && mkdir -p "$build/classes"
    javac --release 21 -cp "$cp" -d "$build/classes" "$script_dir/SesRealProbe.java"
    ssh "$app_host" "rm -rf $remote && mkdir -p $remote/out"
    scp -q -r "$build/classes" "$app_host:$remote/"
    # compose run은 app 서비스의 이미지 태그와 env_file(app.env)을 그대로 쓴다. docker run --env-file은 값의 따옴표를
    # 벗기지 않아 SES_FROM이 달라진다. 포트를 열지 않는 일회용 컨테이너라 실행 중인 앱과 겹치지 않는다.
    # 앱 이미지는 JRE뿐이고 jar 안의 라이브러리를 꺼내야 클래스패스에 넣을 수 있다.
    ssh "$app_host" "cd $app_dir && docker compose run --rm --no-deps --user \"\$(id -u):\$(id -g)\" \
      -e LATENCY_COUNT=${LATENCY_COUNT:-100} -e LATENCY_RATE=${LATENCY_RATE:-5} -e BURST_COUNT=0 -e PROBE_OUT=/out \
      -v $remote/classes:/probe:ro -v $remote/out:/out --entrypoint sh app -c \
      'java -Djarmode=tools -jar /app/app.jar extract --destination /tmp/app > /dev/null && exec java -cp \"/tmp/app/lib/*:/probe\" SesRealProbe'"
    mkdir -p "$out"
    scp -q "$app_host:$remote/out/*" "$out/"
    ssh "$app_host" "rm -rf $remote"
    echo "결과: $out"
    ;;
  latency)
    [[ $# -eq 2 ]] || usage
    target=$probe_root/latency-ms.txt
    {
      echo "# 가짜 SES 지연 표본(ms). 실제 SES에 차례로 보낸 성공 요청의 지연이다. 출처: ${2#"$repo_root"/}"
      jq -r 'select(.phase == "latency" and .status == 200) | .latencyMs' "$2"
    } > "$target"
    grep -v '^#' "$target" | sort -n | awk '{ v[NR] = $1 } END {
      i = int(NR * 0.5 + 0.999); printf "표본 %d개, p50 %.1fms → %s\n", NR, v[i], FILENAME }' FILENAME="$target"
    ;;
  *)
    usage
    ;;
esac
