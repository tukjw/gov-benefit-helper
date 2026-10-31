// 시나리오가 보내는 요청(결정 0037). 메일이 나가는 경로와 수집은 보내지 않는다.
// 입력은 prepare.py가 만든 WORK_DIR/inputs.json(서비스 ID, 시도 코드, 프로필)과 data/keywords.json이다.
import { SharedArray } from 'k6/data';
import exec from 'k6/execution';
import http from 'k6/http';
import { BASE_URL, WORK_DIR, clientIp, pick, randomInt } from './lib.js';

const keywords = new SharedArray('keywords', () => JSON.parse(open('./data/keywords.json')));
const serviceIds = new SharedArray('serviceIds', () => JSON.parse(open(`${WORK_DIR}/inputs.json`)).serviceIds);
const sidoCodes = new SharedArray('sidoCodes', () => JSON.parse(open(`${WORK_DIR}/inputs.json`)).sidoCodes);
const profiles = new SharedArray('profiles', () => JSON.parse(open(`${WORK_DIR}/inputs.json`)).profiles);

function headers(extra = {}) {
  return { 'CF-Connecting-IP': clientIp(), ...extra };
}

/** 혜택 검색. 검색어 하나, 쪽 1~5, 크기 20. 30%는 운영 기관 시도 필터를 더한다. */
export function search() {
  const query = [`page=${randomInt(1, 5)}`, 'size=20'];
  const keyword = pick(keywords);
  if (keyword) {
    query.push(`keyword=${encodeURIComponent(keyword)}`);
  }
  if (Math.random() < 0.3) {
    query.push(`agencySidoCode=${pick(sidoCodes)}`);
  }
  http.get(`${BASE_URL}/api/benefits?${query.join('&')}`, { headers: headers(), tags: { name: 'search' } });
}

/** 혜택 상세. 준비 단계에서 받은 서비스 ID 중 하나. */
export function detail() {
  http.get(`${BASE_URL}/api/benefits/${pick(serviceIds)}`, { headers: headers(), tags: { name: 'detail' } });
}

/** 비회원 조건 비교 목록. 준비 단계에서 만든 프로필 중 하나, 지역 범위는 기본값, 쪽 1~3. */
export function guestMatch() {
  http.post(`${BASE_URL}/api/matches?page=${randomInt(1, 3)}`, JSON.stringify(pick(profiles)), {
    headers: headers({ 'Content-Type': 'application/json' }),
    tags: { name: 'guest-match' },
  });
}

/**
 * 성공하는 로그인. 가상 사용자 번호의 테스트 계정(accounts.sh seed)을 쓴다. 가상 사용자마다 계정과 요청자 IP가 달라
 * 같은 (이메일+IP)에서 동시에 처리되는 시도가 하나를 넘지 않는다(결정 0035의 한도에 걸리지 않는다).
 */
export function login() {
  const email = `loadtest-${String(exec.vu.idInTest).padStart(4, '0')}@loadtest.invalid`;
  http.post(`${BASE_URL}/api/auth/login`, JSON.stringify({ email, password: __ENV.LOADTEST_PASSWORD }), {
    headers: headers({ 'Content-Type': 'application/json' }),
    tags: { name: 'login' },
  });
}
