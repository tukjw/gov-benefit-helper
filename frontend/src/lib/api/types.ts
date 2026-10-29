// 백엔드 요청·응답 DTO(kr.govbenefit.benefit.dto, region.dto, account.dto, profile.dto, match.dto, bookmark.dto,
// common.api)를 그대로 옮긴다.
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
};

export type Region = { code: string; name: string };

export type SidoOption = Region & { sigungus: Region[] };

export type RegionListResponse = { sidos: SidoOption[] };

export type ApiErrorResponse = {
  code: string;
  message: string;
  errors: { field: string; value: string | null; reason: string }[];
};

export type LoginResponse = {
  accessToken: string;
  tokenType: "Bearer";
  expiresAt: string;
};

export type EmailVerificationConfirmResponse = {
  verificationToken: string;
  expiresAt: string;
};

export type AccountResponse = {
  email: string;
  createdAt: string;
};

export type Gender = "MALE" | "FEMALE";

// 속성 값은 null이면 미입력, ["NONE"]이면 해당 없음이다(결정 0022).
export type ProfileRequest = {
  birthDate: string | null;
  gender: Gender | null;
  sidoCode: string | null;
  sigunguCode: string | null;
  lifeAttributes: string[] | null;
  householdAttributes: string[] | null;
};

export type ProfileResponse = {
  birthDate: string | null;
  age: number | null;
  gender: Gender | null;
  sido: Region | null;
  sigungu: Region | null;
  lifeAttributes: string[] | null;
  householdAttributes: string[] | null;
  // 저장한 적이 없으면 null이다.
  updatedAt: string | null;
};

export type AttributeOption = { value: string; label: string };

export type ProfileOptionsResponse = {
  sidos: SidoOption[];
  lifeAttributes: AttributeOption[];
  householdAttributes: AttributeOption[];
};

export type MatchResult = "MATCH" | "NEEDS_CHECK" | "MISMATCH";

export type ItemStatus = "MET" | "NEEDS_CHECK" | "NOT_MET" | "NOT_EVALUATED";

export type MatchReason =
  | "PERSONAL_USER_TYPE"
  | "NON_PERSONAL_USER_TYPE"
  | "USER_TYPE_MISSING"
  | "CONDITIONS_NOT_COLLECTED"
  | "CODE_MISSING"
  | "AGE_RANGE_INVALID"
  | "PROFILE_MISSING"
  | "NO_RESTRICTION"
  | "CODE_INCLUDES"
  | "CODE_EXCLUDES"
  | "AGE_ON_BOUNDARY"
  | "REGION_INCLUDES"
  | "REGION_EXCLUDES"
  | "AGENCY_REGION_UNKNOWN"
  | "NOT_PERSONAL_TARGET";

export type MatchNotice = "CODE_BASED" | "GROUP_RULE_INTERPRETED" | "AGENCY_REGION_BASED" | "NOT_FINAL";

export type UncomparedCondition = "INCOME" | "RESIDENCE" | "OTHER_SOURCE_CONDITIONS";

export type RegionScope = "MY_REGION" | "ALL";

export type MatchItemKey =
  | "personalTarget"
  | "age"
  | "gender"
  | "lifeAttributes"
  | "householdAttributes"
  | "agencyRegion";

export type MatchItemResult = { status: ItemStatus; reason: MatchReason };

export type MatchListItem = {
  benefit: BenefitSummary;
  result: Exclude<MatchResult, "MISMATCH">;
  items: Record<MatchItemKey, MatchItemResult>;
};

export type MatchListResponse = {
  totalCount: number;
  matchCount: number;
  needsCheckCount: number;
  regionScope: RegionScope;
  page: number;
  size: number;
  judgedOn: string;
  notices: MatchNotice[];
  uncomparedConditions: UncomparedCondition[];
  items: MatchListItem[];
};

export type RegionValue = { sido: Region; sigungu: Region | null };

export type MatchResponse = {
  serviceId: string;
  name: string;
  result: MatchResult;
  judgedOn: string;
  notices: MatchNotice[];
  uncomparedConditions: UncomparedCondition[];
  items: {
    personalTarget: MatchItemResult & { userType: string | null };
    age: MatchItemResult & { ageStart: number | null; ageEnd: number | null; profileAge: number | null };
    gender: MatchItemResult & { allowedGenders: Gender[] | null; profileGender: Gender | null };
    lifeAttributes: MatchItemResult & { includedAttributes: string[] | null; profileAttributes: string[] | null };
    householdAttributes: MatchItemResult & { includedAttributes: string[] | null; profileAttributes: string[] | null };
    agencyRegion: MatchItemResult & {
      agencyType: string | null;
      agencyRegion: RegionValue | null;
      profileRegion: RegionValue | null;
    };
  };
  source: { target: string | null; selectionCriteria: string | null; guideUrl: string | null };
};

export type BookmarkListResponse = {
  totalCount: number;
  page: number;
  size: number;
  items: { bookmarkedAt: string; benefit: BenefitSummary }[];
};

export type BookmarkSavedResponse = { serviceIds: string[] };
