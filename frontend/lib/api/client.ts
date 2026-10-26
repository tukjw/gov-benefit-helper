import { apiBaseUrl } from "@/lib/config";
import type { ApiErrorResponse } from "./types";

// 실패 응답(결정 0009). 분기는 message가 아니라 code와 status로 한다.
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    readonly errors: ApiErrorResponse["errors"],
  ) {
    super(`API 요청 실패: ${status} ${code}`);
    this.name = "ApiError";
  }
}

// 서버 컴포넌트에서만 부른다. 화면은 캐시하지 않고 요청마다 렌더링한다(#72 설계 2).
export async function apiGet<T>(path: string, query?: URLSearchParams): Promise<T> {
  const search = query && query.size > 0 ? `?${query}` : "";
  const response = await fetch(`${apiBaseUrl()}${path}${search}`, {
    cache: "no-store",
    headers: { Accept: "application/json" },
  });
  if (!response.ok) {
    const body = await readError(response);
    throw new ApiError(response.status, body?.code ?? "REQUEST_FAILED", body?.errors ?? []);
  }
  return (await response.json()) as T;
}

async function readError(response: Response): Promise<ApiErrorResponse | null> {
  try {
    return (await response.json()) as ApiErrorResponse;
  } catch {
    return null;
  }
}
