import { data, type LoaderFunctionArgs } from "react-router";
import { getBenefitDetail } from "@/lib/api/benefits";
import { ApiError } from "@/lib/api/client";
import { getGuestMatch, getMyMatch } from "@/lib/api/match";
import { getProfileOptions } from "@/lib/api/profile";
import type { BenefitDetailResponse, MatchResponse, ProfileOptionsResponse } from "@/lib/api/types";
import { AuthRequiredError } from "@/lib/auth/authFetch";
import { ensureSession, getSession } from "@/lib/auth/session";
import { loadBookmarks } from "@/lib/bookmarks/bookmarkStore";
import { readGuestProfile } from "@/lib/profile/guestProfile";

// entered가 false면 입력한 값 없이 비교한 비회원의 결과다.
export type DetailMatch = { match: MatchResponse; options: ProfileOptionsResponse; entered: boolean };

export type BenefitDetailData = { benefit: BenefitDetailResponse; match: Promise<DetailMatch> };

// 없는 서비스 ID면 404 응답을 던져 상세 경로의 errorElement가 찾을 수 없음 화면을 그린다.
// 조건 비교 결과와 관심 저장 여부는 기다리지 않고 상세를 먼저 그린다(#74 설계 7).
export async function benefitDetailLoader({ params, request }: LoaderFunctionArgs): Promise<BenefitDetailData> {
  const benefit = await getBenefitDetail(params.serviceId ?? "", request.signal);
  if (!benefit) {
    throw data(null, { status: 404 });
  }
  loadBookmarks([benefit.serviceId]);
  return { benefit, match: loadMatch(benefit.serviceId, request.signal) };
}

// 회원은 저장된 프로필로, 비회원은 이 탭에 둔 값(없으면 빈 값)으로 비교한다. 빈 값이어도 개인 대상이 아닌 혜택은
// 미해당으로 나온다(설계 9). 비회원 값이 지금 기준으로 잘못됐으면 빈 값으로 비교한다.
async function loadMatch(serviceId: string, signal: AbortSignal): Promise<DetailMatch> {
  await ensureSession();
  const options = await getProfileOptions();
  if (getSession().status === "member") {
    try {
      return { match: await getMyMatch(serviceId, signal), options, entered: true };
    } catch (error) {
      if (!(error instanceof AuthRequiredError)) throw error;
    }
  }
  const profile = readGuestProfile();
  try {
    return { match: await getGuestMatch(serviceId, profile, signal), options, entered: profile !== null };
  } catch (error) {
    if (!(error instanceof ApiError && error.status === 400 && profile)) throw error;
    return { match: await getGuestMatch(serviceId, null, signal), options, entered: false };
  }
}
