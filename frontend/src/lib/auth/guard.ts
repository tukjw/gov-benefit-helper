import { redirect } from "react-router";
import { AuthRequiredError } from "./authFetch";
import { ensureSession, getSession } from "./session";

// 로그인이 필요한 경로의 loader·action(결정 0030). 비회원이거나 도중에 리프레시 토큰을 쓸 수 없게 되면
// 로그인 화면으로 보내고, 로그인하면 지금 주소로 돌아온다.
export async function memberOnly<T>(request: Request, run: () => Promise<T>): Promise<T> {
  await ensureSession();
  if (getSession().status !== "member") {
    throw redirect(loginPath(currentPath(request)));
  }
  try {
    return await run();
  } catch (error) {
    if (error instanceof AuthRequiredError) {
      throw redirect(loginPath(currentPath(request)));
    }
    throw error;
  }
}

export function loginPath(returnTo?: string): string {
  return returnTo ? `/login?returnTo=${encodeURIComponent(returnTo)}` : "/login";
}

// 다른 사이트로 보내지 않게 같은 사이트의 경로만 받는다. "//host"와 "/\host"는 브라우저가 다른 호스트로 읽는다.
export function safeReturnTo(value: FormDataEntryValue | string | null | undefined): string {
  if (typeof value !== "string" || !value.startsWith("/") || value.startsWith("//") || value.startsWith("/\\")) {
    return "/";
  }
  return value;
}

function currentPath(request: Request): string {
  const url = new URL(request.url);
  return `${url.pathname}${url.search}`;
}
