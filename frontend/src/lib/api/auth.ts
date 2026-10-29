import { apiRequest } from "./client";
import type { AccountResponse, EmailVerificationConfirmResponse, LoginResponse } from "./types";

// 계정 API(kr.govbenefit.account.controller). 액세스 토큰 없이 부른다.

export function requestSignupCode(email: string): Promise<void> {
  return apiRequest("POST", "/api/auth/email-verification", { body: { email } });
}

export function confirmSignupCode(email: string, code: string): Promise<EmailVerificationConfirmResponse> {
  return apiRequest("POST", "/api/auth/email-verification/confirm", { body: { email, code } });
}

export function signUp(email: string, password: string, verificationToken: string): Promise<AccountResponse> {
  return apiRequest("POST", "/api/auth/signup", { body: { email, password, verificationToken } });
}

export function login(email: string, password: string): Promise<LoginResponse> {
  return apiRequest("POST", "/api/auth/login", { body: { email, password } });
}

// 재발급은 CSRF 방어로 X-Requested-With 헤더가 있어야 한다. 값은 검사하지 않는다(결정 0017).
export function refresh(): Promise<LoginResponse> {
  return apiRequest("POST", "/api/auth/refresh", { headers: { "X-Requested-With": "fetch" } });
}

export function logout(): Promise<void> {
  return apiRequest("POST", "/api/auth/logout");
}

// 가입 여부·발송 제한과 관계없이 같은 204다(결정 0019).
export function requestPasswordReset(email: string): Promise<void> {
  return apiRequest("POST", "/api/auth/password-reset", { body: { email } });
}

export function resetPassword(token: string, newPassword: string): Promise<void> {
  return apiRequest("POST", "/api/auth/password-reset/confirm", { body: { token, newPassword } });
}

// 로그인한 직후처럼 세션에 토큰을 넣기 전에 계정 정보를 받는다. 그 밖에는 account.ts의 getMe를 쓴다.
export function getAccount(accessToken: string, signal?: AbortSignal): Promise<AccountResponse> {
  return apiRequest("GET", "/api/me", { headers: { Authorization: `Bearer ${accessToken}` }, signal });
}
