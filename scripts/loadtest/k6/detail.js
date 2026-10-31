// 시나리오: 혜택 상세(결정 0037). 실행은 scripts/loadtest/run.sh detail <요청 속도…>
import { stepOptions, summarize } from './lib.js';
import { detail } from './requests.js';

export const options = stepOptions(['detail']);

export default function () {
  detail();
}

export function handleSummary(data) {
  return summarize(data);
}
