import { ApiError, apiRequest, type HttpMethod, type RequestOptions } from "@/lib/api/client";
import { ensureSession, getAccessToken, refreshAccessToken, signOut } from "./session";

// 로그인이 필요한데 리프레시 토큰을 쓸 수 없다. loader·action에서는 memberOnly가 로그인 화면으로 보낸다.
export class AuthRequiredError extends Error {
  constructor() {
    super("로그인이 필요합니다.");
    this.name = "AuthRequiredError";
  }
}

// 로그인이 필요한 API(결정 0030). 액세스 토큰이 만료돼 401이면 재발급하고 한 번 다시 보낸다.
// 만료 시각으로 미리 재발급하지 않는다. 시계 차이를 따질 필요 없이 401만 보면 된다.
export async function authFetch<T>(method: HttpMethod, path: string, options: RequestOptions = {}): Promise<T> {
  await ensureSession();
  const token = getAccessToken();
  if (!token) {
    throw new AuthRequiredError();
  }
  try {
    return await apiRequest<T>(method, path, withBearer(options, token));
  } catch (error) {
    if (!isUnauthenticated(error)) throw error;
  }
  // 보내는 사이 다른 요청이 이미 재발급했으면 그 토큰을 쓴다.
  const current = getAccessToken();
  const next = current && current !== token ? current : await refreshAccessToken();
  if (!next) {
    throw new AuthRequiredError();
  }
  try {
    return await apiRequest<T>(method, path, withBearer(options, next));
  } catch (error) {
    // 새 토큰도 거절되면 계정이 없어졌다(다른 탭에서 탈퇴 등).
    if (isUnauthenticated(error)) {
      signOut();
      throw new AuthRequiredError();
    }
    throw error;
  }
}

function withBearer(options: RequestOptions, token: string): RequestOptions {
  return { ...options, headers: { ...options.headers, Authorization: `Bearer ${token}` } };
}

function isUnauthenticated(error: unknown): boolean {
  return error instanceof ApiError && error.status === 401;
}
