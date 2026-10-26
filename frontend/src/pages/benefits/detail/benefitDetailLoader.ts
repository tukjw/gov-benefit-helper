import { data, type LoaderFunctionArgs } from "react-router";
import { getBenefitDetail } from "@/lib/api/benefits";
import type { BenefitDetailResponse } from "@/lib/api/types";

// 없는 서비스 ID면 404 응답을 던져 상세 경로의 errorElement가 찾을 수 없음 화면을 그린다.
export async function benefitDetailLoader({ params, request }: LoaderFunctionArgs): Promise<BenefitDetailResponse> {
  const benefit = await getBenefitDetail(params.serviceId ?? "", request.signal);
  if (!benefit) {
    throw data(null, { status: 404 });
  }
  return benefit;
}
