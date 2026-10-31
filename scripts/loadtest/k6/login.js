// 시나리오: 로그인(결정 0037). 실행은 scripts/loadtest/run.sh login <요청 속도…>
// 테스트 계정(accounts.sh seed)이 가상 사용자 수(MAX_VUS)만큼 있어야 한다.
import { requirePassword, stepOptions, summarize } from './lib.js';
import { login } from './requests.js';

requirePassword();

export const options = stepOptions(['login']);

export default function () {
  login();
}

export function handleSummary(data) {
  return summarize(data);
}
