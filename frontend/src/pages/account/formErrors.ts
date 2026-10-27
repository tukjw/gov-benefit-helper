import { ApiError } from "@/lib/api/client";

// 계정 화면 action의 결과. 화면은 useActionData()로 받아 입력 칸과 폼 위에 붙인다.
export type FieldErrors = Record<string, string>;

export const GENERIC_ERROR = "요청을 처리하지 못했습니다. 잠시 뒤 다시 시도해 주세요.";

// INVALID_INPUT의 항목별 이유. 같은 항목에 이유가 여럿이면 처음 것만 보인다.
export function fieldErrorsOf(error: unknown): FieldErrors | null {
  if (!(error instanceof ApiError) || error.code !== "INVALID_INPUT" || error.errors.length === 0) {
    return null;
  }
  const result: FieldErrors = {};
  for (const item of error.errors) {
    result[item.field] ??= item.reason;
  }
  return result;
}

export function codeOf(error: unknown): string | null {
  return error instanceof ApiError ? error.code : null;
}

// 발송 제한의 Retry-After를 다시 시도할 수 있는 시각으로 바꾼다. 하루 횟수 제한이면 다음 날일 수 있어 날짜를 붙인다.
export function retryAtOf(error: unknown): { retryAt: number; retryText: string } | null {
  if (!(error instanceof ApiError) || error.code !== "TOO_MANY_REQUESTS") {
    return null;
  }
  const retryAt = Date.now() + (error.retryAfterSeconds ?? 60) * 1000;
  const at = new Date(retryAt);
  const sameDay = at.toDateString() === new Date().toDateString();
  const text = new Intl.DateTimeFormat("ko-KR", {
    ...(sameDay ? {} : { month: "long", day: "numeric" }),
    hour: "numeric",
    minute: "2-digit",
  }).format(at);
  return { retryAt, retryText: `${text} 이후에 다시 받을 수 있습니다.` };
}

export function formText(form: FormData, name: string): string {
  const value = form.get(name);
  return typeof value === "string" ? value : "";
}
