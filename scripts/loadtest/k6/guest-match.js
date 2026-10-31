// 시나리오: 비회원 조건 비교 목록(결정 0037). 실행은 scripts/loadtest/run.sh guest-match <요청 속도…>
import { stepOptions, summarize } from './lib.js';
import { guestMatch } from './requests.js';

export const options = stepOptions(['guest-match']);

export default function () {
  guestMatch();
}

export function handleSummary(data) {
  return summarize(data);
}
