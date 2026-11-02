// 시나리오: 혜택 검색(결정 0037). 실행은 scripts/loadtest/run.sh search <요청 속도…>
import { stepOptions, summarize } from './lib.js';
import { search } from './requests.js';

export const options = stepOptions(['search']);

export default function () {
  search();
}

export function handleSummary(data) {
  return summarize(data);
}
