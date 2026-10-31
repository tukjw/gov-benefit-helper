// 시나리오: 혼합(결정 0037). 실행은 scripts/loadtest/run.sh mixed <요청 속도…>
// 요청 수 기준 비율이다. 실사용 표본이 적어 공개 화면 위주로 잡은 가정 비율이며, 측정 기록에 함께 남긴다.
import { requirePassword, stepOptions, summarize } from './lib.js';
import { detail, guestMatch, login, search } from './requests.js';

requirePassword();

const MIX = [
  [0.40, search],
  [0.35, detail],
  [0.20, guestMatch],
  [0.05, login],
];

export const options = stepOptions(['search', 'detail', 'guest-match', 'login']);

export default function () {
  let roll = Math.random();
  for (const [weight, send] of MIX) {
    if (roll < weight) {
      send();
      return;
    }
    roll -= weight;
  }
  search();
}

export function handleSummary(data) {
  return summarize(data);
}
