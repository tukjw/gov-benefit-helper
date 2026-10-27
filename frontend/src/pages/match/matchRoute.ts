import { redirect, type LoaderFunctionArgs } from "react-router";
import { ApiError } from "@/lib/api/client";
import { listGuestMatches, listMyMatches, type MatchListParams } from "@/lib/api/match";
import { getMyProfile, getProfileOptions } from "@/lib/api/profile";
import type { MatchListResponse, ProfileOptionsResponse, ProfileRequest, RegionScope } from "@/lib/api/types";
import { memberOnly } from "@/lib/auth/guard";
import { ensureSession, getSession } from "@/lib/auth/session";
import { loadBookmarks } from "@/lib/bookmarks/bookmarkStore";
import { clearGuestProfile, readGuestProfile } from "@/lib/profile/guestProfile";
import { requestFromResponse } from "@/lib/profile/profileForm";

export type MatchData = {
  member: boolean;
  options: ProfileOptionsResponse;
  profile: ProfileRequest;
  params: MatchListParams;
  response: MatchListResponse;
};

// API의 page는 int라 그보다 큰 값은 400이 된다.
const MAX_PAGE = 2_147_483_647;

// 화면 주소는 ?scope=MY_REGION|ALL&page=다. 잘못된 값은 오류 대신 기본값으로 본다(#72 설계 4와 같다).
export function parseMatchParams(raw: URLSearchParams): MatchListParams {
  const scope = raw.get("scope");
  const page = raw.get("page") ?? "";
  const pageNumber = /^\d+$/.test(page) ? Number(page) : 1;
  return {
    scope: scope === "MY_REGION" || scope === "ALL" ? scope : null,
    page: pageNumber >= 1 && pageNumber <= MAX_PAGE ? pageNumber : 1,
  };
}

export function matchHref(
  params: MatchListParams,
  overrides: { scope?: RegionScope | null; page?: number } = {},
): string {
  const next = { ...params, ...overrides };
  const query = new URLSearchParams();
  if (next.scope) query.set("scope", next.scope);
  if (next.page > 1) query.set("page", String(next.page));
  return query.size > 0 ? `/match?${query}` : "/match";
}

// 입력한 값이 없으면 입력 화면으로 보낸다(#74 설계 4). 비회원이 이 탭에 둔 값이 지금 기준으로 잘못됐으면
// (지역 코드 변경 등) 입력 화면에서 다시 제출하게 한다(설계 5).
export async function matchLoader({ request }: LoaderFunctionArgs): Promise<MatchData> {
  const params = parseMatchParams(new URL(request.url).searchParams);
  await ensureSession();
  const options = await getProfileOptions();
  if (getSession().status === "member") {
    return memberOnly(request, async () => {
      const [saved, response] = await Promise.all([
        getMyProfile(request.signal),
        listMyMatches(params, request.signal),
      ]);
      if (!saved.updatedAt) throw redirect("/profile");
      loadBookmarks(response.items.map((item) => item.benefit.serviceId));
      return { member: true, options, profile: requestFromResponse(saved), params, response };
    });
  }
  const profile = readGuestProfile();
  if (!profile) throw redirect("/profile");
  let response: MatchListResponse;
  try {
    response = await listGuestMatches(profile, params, request.signal);
  } catch (error) {
    if (error instanceof ApiError && error.status === 400) throw redirect("/profile");
    throw error;
  }
  return { member: false, options, profile, params, response };
}

// 비회원의 '처음부터 다시'. 이 탭에 둔 값을 지우고 입력 화면으로 간다.
export function matchAction(): Response {
  clearGuestProfile();
  return redirect("/profile");
}
