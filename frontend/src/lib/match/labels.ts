import type {
  ItemStatus,
  MatchItemKey,
  MatchNotice,
  MatchReason,
  MatchResult,
  UncomparedCondition,
} from "@/lib/api/types";

// 조건 비교 응답 코드의 화면 문구(REQ-MATCH-01, docs/design/README.md F3). '지원조건 코드' 같은 내부 용어와
// 지원 자격을 확정하는 표현을 쓰지 않는다.

export const RESULT_LABELS: Record<MatchResult, string> = {
  MATCH: "해당",
  NEEDS_CHECK: "확인 필요",
  MISMATCH: "미해당",
};

export const RESULT_DESCRIPTIONS: Record<MatchResult, string> = {
  MATCH: "공개된 대상 조건과 입력한 정보를 비교한 결과예요. 실제 지원 여부는 담당 기관 심사로 정해져요.",
  NEEDS_CHECK: "일부 조건은 직접 확인해야 해요.",
  MISMATCH: "개인이 아닌 기관·사업자 대상 혜택이에요.",
};

export const RESULT_TONES: Record<MatchResult, "success" | "warning" | "neutral"> = {
  MATCH: "success",
  NEEDS_CHECK: "warning",
  MISMATCH: "neutral",
};

export const ITEM_KEYS: MatchItemKey[] = [
  "personalTarget",
  "age",
  "gender",
  "lifeAttributes",
  "householdAttributes",
  "agencyRegion",
];

export const ITEM_LABELS: Record<MatchItemKey, string> = {
  personalTarget: "개인 대상",
  age: "나이",
  gender: "성별",
  lifeAttributes: "생애·직업·건강",
  householdAttributes: "가구",
  agencyRegion: "운영 기관 지역",
};

export const STATUS_LABELS: Record<ItemStatus, string> = {
  MET: "충족",
  NEEDS_CHECK: "확인 필요",
  NOT_MET: "미충족",
  NOT_EVALUATED: "비교 안 함",
};

const REASONS: Record<MatchReason, string> = {
  PERSONAL_USER_TYPE: "개인·가구 대상 혜택이에요.",
  NON_PERSONAL_USER_TYPE: "개인이 아닌 기관·사업자 대상 혜택이에요.",
  USER_TYPE_MISSING: "대상 구분이 공개되지 않았어요.",
  CONDITIONS_NOT_COLLECTED: "대상 조건 정보를 아직 받지 못했어요.",
  CODE_MISSING: "공개된 대상 조건에 이 항목이 없어요.",
  AGE_RANGE_INVALID: "공개된 대상 나이를 해석할 수 없어요.",
  PROFILE_MISSING: "입력하지 않아 비교하지 못했어요.",
  NO_RESTRICTION: "이 항목의 제한이 없어요.",
  CODE_INCLUDES: "입력한 값이 대상에 포함돼요.",
  CODE_EXCLUDES: "입력한 값이 공개된 대상 조건에 없어요.",
  AGE_ON_BOUNDARY: "나이가 대상 나이의 경계예요. 경계 나이가 포함되는지 확인하지 못했어요.",
  REGION_INCLUDES: "내 거주지에 있는 기관의 혜택이에요.",
  REGION_EXCLUDES: "다른 지역 기관의 혜택이에요. 다른 지역 주민도 대상인지 원문을 확인해 주세요.",
  AGENCY_REGION_UNKNOWN: "운영 기관의 지역을 확인하지 못했어요.",
  NOT_PERSONAL_TARGET: "개인 대상이 아니라 비교하지 않았어요.",
};

// 운영 기관 지역은 같은 이유 코드도 뜻이 달라 문구를 따로 둔다(결정 0025).
const REGION_REASONS: Partial<Record<MatchReason, string>> = {
  PROFILE_MISSING: "거주지를 입력하지 않았거나, 시군구 기관과 비교할 만큼 입력하지 않았어요.",
  NO_RESTRICTION: "지역과 관계없는 기관(중앙행정기관·공공기관)의 혜택이에요.",
};

export function reasonText(item: MatchItemKey, reason: MatchReason): string {
  return (item === "agencyRegion" ? REGION_REASONS[reason] : undefined) ?? REASONS[reason];
}

export const NOTICE_TEXTS: Record<MatchNotice, string> = {
  CODE_BASED: "공개된 대상 조건 정보로 비교했어요. 원문과 다를 수 있어요.",
  GROUP_RULE_INTERPRETED:
    "생애·가구 속성은 고른 것 중 하나라도 대상이면 충족으로, 항목끼리는 모두 충족해야 해당으로 해석했어요. 확인된 규칙이 아니라 해석이에요.",
  AGENCY_REGION_BASED: "거주 요건이 아니라 운영 기관이 있는 지역으로 비교했어요.",
  NOT_FINAL: RESULT_DESCRIPTIONS.MATCH,
};

export const UNCOMPARED_LABELS: Record<UncomparedCondition, string> = {
  INCOME: "소득",
  RESIDENCE: "원문의 거주 요건",
  OTHER_SOURCE_CONDITIONS: "원문의 그 밖의 조건",
};
