import type { LoaderFunctionArgs } from "react-router";
import { getRegions, searchBenefits, type BenefitSearchParams } from "@/lib/api/benefits";
import { ApiError } from "@/lib/api/client";
import type { BenefitSearchResponse } from "@/lib/api/types";
import { parseSearchParams } from "@/lib/benefits/searchParams";
import { loadBookmarks } from "@/lib/bookmarks/bookmarkStore";

export type SearchOutcome =
  | { kind: "ok"; response: BenefitSearchResponse }
  // 400: 키워드 길이·단어 수, 지역 코드처럼 URL을 직접 고친 경우다. 오류 화면 대신 안내한다(#72 설계 4).
  | { kind: "invalid"; reasons: string[] };

export async function benefitsLoader({ request }: LoaderFunctionArgs) {
  const params = parseSearchParams(new URL(request.url).searchParams);
  const [regions, outcome] = await Promise.all([getRegions(request.signal), search(params, request.signal)]);
  if (outcome.kind === "ok") loadBookmarks(outcome.response.items.map((item) => item.serviceId));
  return { params, sidos: regions.sidos, outcome };
}

async function search(params: BenefitSearchParams, signal: AbortSignal): Promise<SearchOutcome> {
  try {
    return { kind: "ok", response: await searchBenefits(params, signal) };
  } catch (error) {
    if (error instanceof ApiError && error.status === 400) {
      return { kind: "invalid", reasons: error.errors.map((item) => item.reason) };
    }
    throw error;
  }
}
