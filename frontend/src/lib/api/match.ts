import { authFetch } from "@/lib/auth/authFetch";
import { apiRequest } from "./client";
import type { MatchListResponse, MatchResponse, ProfileRequest, RegionScope } from "./types";

// 조건 비교(REQ-MATCH-01). 회원은 저장된 프로필로, 비회원은 본문의 값으로 비교하고 서버는 그 값을 저장하지 않는다.

export const MATCH_PAGE_SIZE = 20;

export type MatchListParams = {
  // 없으면 서버가 거주지에 따라 정한다.
  scope: RegionScope | null;
  page: number;
  size?: number;
};

function listQuery({ scope, page, size = MATCH_PAGE_SIZE }: MatchListParams): URLSearchParams {
  const query = new URLSearchParams();
  if (scope) query.set("regionScope", scope);
  query.set("page", String(page));
  query.set("size", String(size));
  return query;
}

export function listMyMatches(params: MatchListParams, signal?: AbortSignal): Promise<MatchListResponse> {
  return authFetch("GET", "/api/me/matches", { query: listQuery(params), signal });
}

export function getMyMatch(serviceId: string, signal?: AbortSignal): Promise<MatchResponse> {
  return authFetch("GET", `/api/me/matches/${encodeURIComponent(serviceId)}`, { signal });
}

export function listGuestMatches(
  profile: ProfileRequest,
  params: MatchListParams,
  signal?: AbortSignal,
): Promise<MatchListResponse> {
  return apiRequest("POST", "/api/matches", { query: listQuery(params), body: profile, signal });
}

export function getGuestMatch(
  serviceId: string,
  profile: ProfileRequest | null,
  signal?: AbortSignal,
): Promise<MatchResponse> {
  return apiRequest("POST", `/api/matches/${encodeURIComponent(serviceId)}`, { body: profile ?? {}, signal });
}
