// 알림 발송 장애 측정(#137)의 account-mail 시나리오. 알림을 보내는 동안 가입 인증번호를 요청해 계정 메일을 보낸다.
// run.sh가 알림 발송 시작(가짜 SES의 첫 알림 요청)에 맞춰 부하 생성 VM의 k6 컨테이너로 실행한다.
// 처음부터 끝까지 초당 BASE_RATE건, PEAK_START_MIN분부터 PEAK_MIN분 동안은 합쳐 초당 PEAK_RATE건이다.
// 이메일은 요청마다 다르다(같은 이메일은 60초에 한 번만 보낸다). 앱은 발송 실패와 상관없이 204로 응답하므로,
// 실패한 계정 메일은 가짜 SES 기록으로 센다(count.py). 인증 기록은 data.sh cleanup이 지운다.
//
// 환경 변수: BASE_URL(기본 운영 app VM의 내부 브리지 주소), WORK_DIR(요약 파일 위치), RUN_TAG(이메일 구분),
// MAIL_MINUTES(30), BASE_RATE(1), PEAK_RATE(10), PEAK_START_MIN(5), PEAK_MIN(2)
import exec from 'k6/execution';
import http from 'k6/http';

const BASE_URL = __ENV.BASE_URL || 'http://10.10.0.11:8080';
const WORK_DIR = __ENV.WORK_DIR || '/work';
const RUN_TAG = __ENV.RUN_TAG || 'run';
const minutes = Number(__ENV.MAIL_MINUTES || 30);
const baseRate = Number(__ENV.BASE_RATE || 1);
const peakRate = Number(__ENV.PEAK_RATE || 10);
const peakStart = Number(__ENV.PEAK_START_MIN || 5);
const peakMinutes = Number(__ENV.PEAK_MIN || 2);

http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }));

export const options = {
  scenarios: {
    base: {
      executor: 'constant-arrival-rate',
      rate: baseRate,
      timeUnit: '1s',
      duration: `${minutes}m`,
      preAllocatedVUs: 5,
      maxVUs: 50,
    },
    peak: {
      executor: 'constant-arrival-rate',
      rate: peakRate - baseRate,
      timeUnit: '1s',
      startTime: `${peakStart}m`,
      duration: `${peakMinutes}m`,
      preAllocatedVUs: 20,
      maxVUs: 100,
    },
  },
  // 요약에 시나리오별 값을 남기려고 두는 항상 통과하는 기준이다.
  thresholds: {
    'http_reqs{scenario:base}': ['count>=0'],
    'http_reqs{scenario:peak}': ['count>=0'],
    'http_req_failed{scenario:base}': ['rate>=0'],
    'http_req_failed{scenario:peak}': ['rate>=0'],
  },
};

// 가상 사용자마다 다른 요청자 IP(scripts/loadtest/k6/lib.js와 같은 대역)
function clientIp() {
  const id = exec.vu.idInTest;
  return `198.18.${Math.floor(id / 256)}.${id % 256}`;
}

export default function () {
  const email = `notify-mail-${RUN_TAG}-${exec.scenario.name}-${exec.scenario.iterationInTest}@loadtest.invalid`;
  http.post(`${BASE_URL}/api/auth/email-verification`, JSON.stringify({ email, ageConfirmed: true }), {
    headers: { 'Content-Type': 'application/json', 'CF-Connecting-IP': clientIp() },
    tags: { name: 'email-verification' },
  });
}

export function handleSummary(data) {
  return { [`${WORK_DIR}/k6-summary.json`]: JSON.stringify(data, null, 2) };
}
