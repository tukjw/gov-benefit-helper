// 백엔드 응답 DTO(kr.govbenefit.benefit.dto, region.dto, common.api)를 그대로 옮긴다.
// 값이 없는 필드는 null이며 서버는 기본 문구로 채우지 않는다. 시각은 +09:00 오프셋 ISO-8601 문자열이다(결정 0010).

export type ApplyPeriodType = "FIXED_DATE" | "ALWAYS" | "CONDITIONAL" | "NOT_REQUIRED" | "UNKNOWN";

export type BenefitSummary = {
  serviceId: string;
  name: string;
  summary: string | null;
  agencyName: string | null;
  agencyType: string | null;
  field: string | null;
  applyPeriodText: string | null;
  applyPeriodType: ApplyPeriodType | null;
  applyPeriodNeedsReview: boolean | null;
  lastSeenAt: string | null;
};

export type BenefitSearchResponse = {
  totalCount: number;
  page: number;
  size: number;
  items: BenefitSummary[];
};

export type Freshness = {
  stale: boolean;
  referenceAt: string | null;
};

export type BenefitDetailResponse = {
  serviceId: string;
  name: string;
  summary: string | null;
  field: string | null;
  agencyName: string | null;
  agencyType: string | null;
  departmentName: string | null;
  supportType: string | null;
  supportContent: string | null;
  target: string | null;
  selectionCriteria: string | null;
  applyPeriod: {
    text: string | null;
    type: ApplyPeriodType | null;
    needsReview: boolean | null;
  };
  applyMethodType: string | null;
  receptionAgency: string | null;
  phoneInquiry: string | null;
  guideUrl: string | null;
  sourceModifiedAt: string | null;
  lastSeenAt: string | null;
  // null이면 상세를 아직 받지 못한 혜택이다.
  detail: {
    purpose: string | null;
    applyMethod: string | null;
    requiredDocuments: string | null;
    officialCheckDocuments: string | null;
    selfCheckDocuments: string | null;
    contact: string | null;
    receptionAgencyName: string | null;
    onlineApplyUrl: string | null;
    onlineApplyUrlText: string | null;
  } | null;
  freshness: Freshness;
};

export type Region = { code: string; name: string };

export type SidoOption = Region & { sigungus: Region[] };

export type RegionListResponse = { sidos: SidoOption[] };

export type ApiErrorResponse = {
  code: string;
  message: string;
  errors: { field: string; value: string | null; reason: string }[];
};
