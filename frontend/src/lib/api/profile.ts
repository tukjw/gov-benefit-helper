import { authFetch } from "@/lib/auth/authFetch";
import { apiGet } from "./client";
import type { ProfileOptionsResponse, ProfileRequest, ProfileResponse } from "./types";

let options: Promise<ProfileOptionsResponse> | null = null;

// 입력 선택지(로그인 불필요). 앱을 쓰는 동안 바뀌지 않으므로 한 번 받아 같이 쓴다. 실패하면 다음에 다시 받는다.
// 화면을 떠나도 다른 화면이 같은 응답을 기다릴 수 있어 signal을 넘기지 않는다.
export function getProfileOptions(): Promise<ProfileOptionsResponse> {
  options ??= apiGet<ProfileOptionsResponse>("/api/profile/options").catch((error: unknown) => {
    options = null;
    throw error;
  });
  return options;
}

export function getMyProfile(signal?: AbortSignal): Promise<ProfileResponse> {
  return authFetch("GET", "/api/me/profile", { signal });
}

export function saveMyProfile(profile: ProfileRequest): Promise<ProfileResponse> {
  return authFetch("PUT", "/api/me/profile", { body: profile });
}
