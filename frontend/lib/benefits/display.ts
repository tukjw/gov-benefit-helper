import type { ApplyPeriodType } from "@/lib/api/types";

export const NO_INFO = "정보 없음";

export function orNoInfo(value: string | null | undefined): string {
  return value && value.trim() !== "" ? value : NO_INFO;
}

export function hasText(value: string | null | undefined): value is string {
  return Boolean(value && value.trim() !== "");
}

// 신청기한 분류의 표시 상태. 연월일이 적힌 기한은 원문을 그대로 보여준다(결정 0008).
const APPLY_PERIOD_LABELS: Record<ApplyPeriodType, string> = {
  FIXED_DATE: "기한 있음",
  ALWAYS: "상시 신청",
  CONDITIONAL: "조건부 마감",
  NOT_REQUIRED: "신청 절차 없음",
  UNKNOWN: "기한 확인 필요",
};

export function applyPeriodLabel(type: ApplyPeriodType | null): string | null {
  return type ? APPLY_PERIOD_LABELS[type] : null;
}

// 서버 컴포넌트에서만 쓴다. 서버와 브라우저의 시간대가 달라도 한국 시각으로 보인다(#72 설계 10).
const DATE_TIME = new Intl.DateTimeFormat("ko-KR", {
  timeZone: "Asia/Seoul",
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
  hour: "2-digit",
  minute: "2-digit",
  hourCycle: "h23",
});

const DATE = new Intl.DateTimeFormat("ko-KR", {
  timeZone: "Asia/Seoul",
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
});

export function formatDateTime(iso: string | null): string | null {
  return iso ? DATE_TIME.format(new Date(iso)) : null;
}

export function formatDate(iso: string | null): string | null {
  return iso ? DATE.format(new Date(iso)) : null;
}
