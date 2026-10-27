import { apiBaseUrl } from "@/lib/config";
import type { ApiErrorResponse } from "./types";

// 실패 응답(결정 0009). 분기는 message가 아니라 code와 status로 한다.
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    readonly errors: ApiErrorResponse["errors"],
    // 429의 Retry-After(다시 시도할 수 있을 때까지의 초). 없으면 null이다.
    readonly retryAfterSeconds: number | null = null,
  ) {
    super(`API 요청 실패: ${status} ${code}`);
    this.name = "ApiError";
  }
}

export type HttpMethod = "GET" | "POST" | "PUT" | "DELETE";

export type RequestOptions = {
  query?: URLSearchParams;
  body?: unknown;
  headers?: Record<string, string>;
  signal?: AbortSignal;
};

// 경로의 loader에서 부른다(결정 0029). 다른 화면으로 이동해 loader가 취소되면 signal로 요청도 끊는다.
export function apiGet<T>(path: string, options: Pick<RequestOptions, "query" | "signal"> = {}): Promise<T> {
  return apiRequest<T>("GET", path, options);
}

// 리프레시 토큰 쿠키를 주고받도록 쿠키를 싣는다(결정 0029). 쿠키 경로가 /api/auth라 다른 API에는 실리지 않는다.
// 본문이 없는 응답(204)은 undefined다.
export async function apiRequest<T>(method: HttpMethod, path: string, options: RequestOptions = {}): Promise<T> {
  const { query, body, headers, signal } = options;
  const search = query && query.size > 0 ? `?${query}` : "";
  const response = await fetch(`${apiBaseUrl()}${path}${search}`, {
    method,
    headers: {
      Accept: "application/json",
      ...(body === undefined ? {} : { "Content-Type": "application/json" }),
      ...headers,
    },
    body: body === undefined ? undefined : JSON.stringify(body),
    credentials: "include",
    signal,
  });
  if (!response.ok) {
    const error = await readError(response);
    throw new ApiError(response.status, error?.code ?? "REQUEST_FAILED", error?.errors ?? [], readRetryAfter(response));
  }
  if (response.status === 204) {
    return undefined as T;
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

// 서버는 초 단위 정수로 보낸다. 날짜 형식은 쓰지 않는다.
function readRetryAfter(response: Response): number | null {
  const value = Number(response.headers.get("Retry-After"));
  return Number.isFinite(value) && value > 0 ? value : null;
}
