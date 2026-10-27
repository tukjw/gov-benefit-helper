import { authFetch } from "@/lib/auth/authFetch";
import type { AccountResponse } from "./types";

// 로그인이 필요한 계정 API. 액세스 토큰이 만료되면 authFetch가 재발급해 다시 보낸다(결정 0030).

export function getMe(signal?: AbortSignal): Promise<AccountResponse> {
  return authFetch("GET", "/api/me", { signal });
}

export function withdraw(password: string): Promise<void> {
  return authFetch("POST", "/api/me/withdrawal", { body: { password } });
}
