import { useSyncExternalStore } from "react";
import { getAccount, refresh } from "@/lib/api/auth";
import { ApiError } from "@/lib/api/client";
import type { LoginResponse } from "@/lib/api/types";

// 로그인 상태(결정 0030). 액세스 토큰은 이 모듈의 변수에만 두고 저장소·쿠키에 쓰지 않는다(DEC-AUTH).
// 컴포넌트 밖의 loader·action도 같은 값을 쓰도록 React 상태가 아니라 모듈에 둔다.

export type Session =
  // 앱을 연 뒤 첫 재발급의 결과를 아직 모른다.
  | { status: "unknown" }
  | { status: "guest" }
  | { status: "member"; email: string };

const GUEST: Session = { status: "guest" };

let session: Session = { status: "unknown" };
let accessToken: string | null = null;
const listeners = new Set<() => void>();

function setSession(next: Session, token: string | null) {
  session = next;
  accessToken = token;
  listeners.forEach((listener) => listener());
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function getSession(): Session {
  return session;
}

export function useSession(): Session {
  return useSyncExternalStore(subscribe, getSession);
}

export function getAccessToken(): string | null {
  return accessToken;
}

// 로그인 응답을 받은 뒤. 헤더에 보일 이메일을 함께 받는다.
export async function signIn(tokens: LoginResponse): Promise<void> {
  const account = await getAccount(tokens.accessToken);
  setSession({ status: "member", email: account.email }, tokens.accessToken);
}

// 로그아웃·탈퇴·비밀번호 재설정 뒤. 쿠키는 서버 응답이 지운다.
export function signOut(): void {
  setSession(GUEST, null);
}

let restoring: Promise<void> | null = null;

// 앱을 열 때 리프레시 토큰 쿠키로 로그인 상태를 되살린다. 한 번만 실행하고 결과를 같이 쓴다.
// 공개 화면은 기다리지 않고, 로그인이 필요한 경로의 loader만 기다린다.
export function ensureSession(): Promise<void> {
  restoring ??= refreshAccessToken().then(
    () => undefined,
    // API에 닿지 못하면 비회원으로 둔다. 로그인이 필요한 화면은 다음 요청에서 다시 재발급한다.
    () => {
      if (session.status === "unknown") setSession(GUEST, null);
    },
  );
  return restoring;
}

let refreshing: Promise<string | null> | null = null;

// 새 액세스 토큰. 리프레시 토큰을 쓸 수 없으면(401) null이고 비회원이 된다. 그 밖의 실패는 던진다.
// 탭 안에서는 진행 중인 재발급을 함께 기다리고, 탭 사이에서는 잠금으로 차례로 보낸다. 앞 탭이 교체한
// 쿠키로 다음 탭이 보내므로 교체된 토큰을 다시 쓰는 일(결정 0017의 재사용 감지)이 생기지 않는다.
export function refreshAccessToken(): Promise<string | null> {
  refreshing ??= withRefreshLock(runRefresh).finally(() => {
    refreshing = null;
  });
  return refreshing;
}

async function runRefresh(): Promise<string | null> {
  let tokens: LoginResponse;
  try {
    tokens = await refresh();
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      setSession(GUEST, null);
      return null;
    }
    throw error;
  }
  const email = session.status === "member" ? session.email : (await getAccount(tokens.accessToken)).email;
  setSession({ status: "member", email }, tokens.accessToken);
  return tokens.accessToken;
}

// Web Locks는 같은 출처의 탭끼리 공유된다. 지원하지 않는 브라우저에서는 잠금 없이 보낸다.
async function withRefreshLock<T>(run: () => Promise<T>): Promise<T> {
  if (!navigator.locks) {
    return run();
  }
  return await navigator.locks.request("gov-benefit-helper:refresh", run);
}
