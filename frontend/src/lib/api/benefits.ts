import { ApiError, apiGet } from "./client";
import type { BenefitDetailResponse, BenefitSearchResponse, RegionListResponse } from "./types";

export const PAGE_SIZE = 20;

export type BenefitSearchParams = {
  keyword: string;
  fields: string[];
  agencyTypes: string[];
  agencySidoCode: string;
  agencySigunguCode: string;
  page: number;
};

export function searchBenefits(params: BenefitSearchParams, signal?: AbortSignal): Promise<BenefitSearchResponse> {
  const query = new URLSearchParams();
  if (params.keyword) query.set("keyword", params.keyword);
  params.fields.forEach((field) => query.append("field", field));
  params.agencyTypes.forEach((type) => query.append("agencyType", type));
  if (params.agencySidoCode) query.set("agencySidoCode", params.agencySidoCode);
  if (params.agencySigunguCode) query.set("agencySigunguCode", params.agencySigunguCode);
  query.set("page", String(params.page));
  query.set("size", String(PAGE_SIZE));
  return apiGet<BenefitSearchResponse>("/api/benefits", { query, signal });
}

// 없는 서비스 ID면 null이다. 상세를 아직 받지 못한 혜택은 detail이 null인 응답이 온다.
export async function getBenefitDetail(serviceId: string, signal?: AbortSignal): Promise<BenefitDetailResponse | null> {
  try {
    return await apiGet<BenefitDetailResponse>(`/api/benefits/${encodeURIComponent(serviceId)}`, { signal });
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) {
      return null;
    }
    throw error;
  }
}

export function getRegions(signal?: AbortSignal): Promise<RegionListResponse> {
  return apiGet<RegionListResponse>("/api/benefits/regions", { signal });
}
