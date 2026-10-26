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

// 경로의 loader에서 부른다(결정 0027). 다른 화면으로 이동해 loader가 취소되면 signal로 요청도 끊는다.
export async function apiGet<T>(path: string, options: { query?: URLSearchParams; signal?: AbortSignal } = {}): Promise<T> {
  const { query, signal } = options;
  const search = query && query.size > 0 ? `?${query}` : "";
  const response = await fetch(`${apiBaseUrl()}${path}${search}`, {
    headers: { Accept: "application/json" },
    signal,
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
