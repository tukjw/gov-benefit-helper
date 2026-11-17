#!/usr/bin/env bash
# 알림 발송 장애 측정(#137)의 로컬 리허설 준비. 지금 코드로 앱 이미지를 만들고, 리허설 디렉터리에 app 실행 구성을 둔다.
# 사용법: scripts/loadtest/notify/rehearsal/setup.sh <리허설 디렉터리>
# 로컬 compose MySQL(docker-compose.local.yml)이 떠 있어야 한다. app.env에는 로컬 전용 값만 넣는다(SES 키는 가짜).
# 준비가 끝나면 출력되는 환경 변수를 export하고 switch.sh on → run.sh <시나리오>로 리허설한다. 절차는 docs/ops/load-test.md.
set -euo pipefail

[[ $# -eq 1 ]] || { echo "사용법: $0 <리허설 디렉터리>" >&2; exit 2; }
dir=$(mkdir -p "$1" && cd "$1" && pwd)
script_dir=$(cd "$(dirname "$0")" && pwd)
repo_root=$(cd "$script_dir/../../../.." && pwd)

(cd "$repo_root/backend" && ./gradlew -q bootJar)
docker build -q -t gov-benefit-helper:rehearsal "$repo_root/backend" > /dev/null
cp "$script_dir/compose.yaml" "$dir/compose.yaml"
echo "APP_TAG=rehearsal" > "$dir/.env"
# 로컬 compose MySQL의 기본 계정(docker-compose.local.yml)이다. 운영 값이 아니다.
(umask 077 && cat > "$dir/app.env" <<ENV
DB_HOST=host.docker.internal
DB_PORT=3306
DB_NAME=gov_benefit
DB_USER=gov_benefit_user
DB_PASSWORD=gov_benefit_user
JWT_SECRET=$(openssl rand -base64 32)
GOV24_SERVICE_KEY=rehearsal-not-used
MAIL_SENDER=ses
SES_ACCESS_KEY_ID=AKIAREHEARSALFAKE000
SES_SECRET_ACCESS_KEY=rehearsal-fake-secret
SES_REGION=ap-northeast-2
SES_FROM="정부 혜택 도우미 <help@example.invalid>"
ENV
)
(cd "$dir" && docker compose up -d) > /dev/null
echo "리허설 app 실행 구성: $dir"
echo "다음 환경 변수로 switch.sh와 run.sh를 실행한다:"
echo "export APP_HOST=local APP_DIR=$dir LOADTEST_DB=local FAKE_SES_BIND=0.0.0.0 FAKE_SES_URL=http://host.docker.internal:8025 \\"
echo "  K6_NETWORK=bridge K6_BASE_URL=http://host.docker.internal:8080 SKIP_METRICS=1 XDG_STATE_HOME=$dir/state"
