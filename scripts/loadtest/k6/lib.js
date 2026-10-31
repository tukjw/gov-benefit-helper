// 부하 테스트 공통 설정(결정 0037). run.sh가 부하 VM에서 요청 속도 단계마다 k6를 한 번씩 실행한다.
// 환경 변수: RATE(초당 요청 수), DURATION(단계 길이, 기본 3m), MAX_VUS(기본 1000), STEP(단계 이름),
// BASE_URL(기본 운영 app VM의 내부 브리지 주소), WORK_DIR(준비 데이터와 결과를 두는 디렉터리).
import exec from 'k6/execution';
import http from 'k6/http';

export const BASE_URL = __ENV.BASE_URL || 'http://10.10.0.11:8080';
export const WORK_DIR = __ENV.WORK_DIR || '/work';

// 2xx만 성공으로 센다. k6 기본값(200~399)은 리다이렉트도 성공으로 본다.
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }));

/**
 * 가상 사용자마다 다른 요청자 IP(벤치마크 대역 198.18.0.0/15). 앱은 사설 대역에서 온 요청의 CF-Connecting-IP를
 * 요청자 주소로 쓴다(결정 0037). 헤더가 없으면 모든 요청이 부하 VM 주소 하나로 보인다. 지금은 동시에 처리되는 로그인이
 * DB 커넥션 풀 크기로 묶여 로그인 시도 제한(결정 0035)의 IP 한도에 닿지 않지만, 풀을 키워도 결과가 제한기에 가려지지 않게
 * 실제 사용자처럼 IP를 나눈다.
 */
export function clientIp() {
  const id = exec.vu.idInTest;
  return `198.18.${Math.floor(id / 256)}.${id % 256}`;
}

export function randomInt(min, max) {
  return min + Math.floor(Math.random() * (max - min + 1));
}

export function pick(items) {
  return items[Math.floor(Math.random() * items.length)];
}

/**
 * 단계 하나의 옵션. 응답이 느려져도 요청 속도를 유지하도록 도착률 실행기를 쓴다. 가상 사용자가 모자라 보내지 못한
 * 요청은 dropped_iterations로 남는다. 반복 한 번이 요청 하나라 RATE가 곧 초당 요청 수다.
 *
 * @param names 이 시나리오가 보내는 요청 이름(name 태그). 요약에 이름별 값이 남는다.
 */
export function stepOptions(names) {
  const rate = Number(__ENV.RATE);
  if (!Number.isInteger(rate) || rate <= 0) {
    throw new Error('RATE(초당 요청 수, 양의 정수)가 필요합니다.');
  }
  const maxVUs = Number(__ENV.MAX_VUS || 1000);
  const thresholds = {};
  for (const name of names) {
    // 요약에 이름별 하위 지표를 남기려고 두는 항상 통과하는 기준이다. 성능 기준이 아니다.
    thresholds[`http_req_duration{name:${name}}`] = ['max>=0'];
    thresholds[`http_req_failed{name:${name}}`] = ['rate>=0'];
  }
  return {
    scenarios: {
      step: {
        executor: 'constant-arrival-rate',
        rate,
        timeUnit: '1s',
        duration: __ENV.DURATION || '3m',
        preAllocatedVUs: Math.min(maxVUs, Math.max(20, rate)),
        maxVUs,
      },
    },
    thresholds,
    summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max', 'count'],
    discardResponseBodies: true,
  };
}

/** 단계 요약을 WORK_DIR/<STEP>.json에 쓰고 한 줄로 출력한다. 지연(http_req_duration)은 실패 요청을 포함한다. */
export function summarize(data) {
  const step = __ENV.STEP || 'step';
  const metrics = data.metrics;
  const duration = metrics.http_req_duration ? metrics.http_req_duration.values : null;
  const reqs = metrics.http_reqs ? metrics.http_reqs.values : { count: 0, rate: 0 };
  const failed = metrics.http_req_failed ? metrics.http_req_failed.values.rate : 0;
  const dropped = metrics.dropped_iterations ? metrics.dropped_iterations.values.count : 0;
  const ms = (value) => (duration ? `${value.toFixed(1)}ms` : '-');
  const line = `${step}: 목표 ${__ENV.RATE}/s, 응답 ${reqs.count}건(${reqs.rate.toFixed(1)}/s), `
    + `p50 ${ms(duration && duration.med)}, p95 ${ms(duration && duration['p(95)'])}, `
    + `p99 ${ms(duration && duration['p(99)'])}, 오류율 ${(failed * 100).toFixed(2)}%, 못 보낸 요청 ${dropped}\n`;
  return {
    stdout: line,
    [`${WORK_DIR}/${step}.json`]: JSON.stringify(data, null, 2),
  };
}

/** 로그인을 보내는 시나리오에서 init 단계에 부른다. 비밀번호는 accounts.sh seed가 만든 값이다. */
export function requirePassword() {
  if (!__ENV.LOADTEST_PASSWORD) {
    throw new Error('LOADTEST_PASSWORD가 없습니다. scripts/loadtest/accounts.sh seed로 테스트 계정을 먼저 넣으세요.');
  }
}
