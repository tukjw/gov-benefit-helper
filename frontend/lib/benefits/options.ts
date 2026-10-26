import {
  Baby,
  BriefcaseBusiness,
  GraduationCap,
  HandHeart,
  HeartPulse,
  House,
  Palette,
  ShieldCheck,
  Tractor,
  Wallet,
  type LucideIcon,
} from "lucide-react";

// 필터 값은 API가 검증하지 않는 원문 값이라 한 글자라도 다르면 결과가 0건이 된다.
// 로컬 DB(2026-10-26, 10,950건)의 값을 그대로 옮겼다. 공공 데이터에 새 값이 생기면 여기를 고친다(#72 설계 6).

export const FIELDS: { value: string; icon: LucideIcon }[] = [
  { value: "생활안정", icon: Wallet },
  { value: "농림축산어업", icon: Tractor },
  { value: "보육·교육", icon: GraduationCap },
  { value: "보건·의료", icon: HeartPulse },
  { value: "임신·출산", icon: Baby },
  { value: "고용·창업", icon: BriefcaseBusiness },
  { value: "문화·환경", icon: Palette },
  { value: "보호·돌봄", icon: HandHeart },
  { value: "행정·안전", icon: ShieldCheck },
  { value: "주거·자립", icon: House },
];

export const AGENCY_TYPES: { value: string; label: string }[] = [
  { value: "중앙행정기관", label: "중앙행정기관" },
  { value: "광역시도", label: "광역시도" },
  { value: "시군구", label: "시군구" },
  { value: "교육청", label: "교육청" },
  { value: "공공기관", label: "공공기관" },
  { value: "지방공기업", label: "지방공기업" },
  { value: "지방출자_출연기관", label: "지방 출자·출연기관" },
];

export function agencyTypeLabel(value: string | null): string | null {
  if (!value) return null;
  return AGENCY_TYPES.find((type) => type.value === value)?.label ?? value;
}

// 검색 기록을 모으지 않으므로 '많이 찾는'이 아니라 '추천' 검색어다.
// 혜택 이름에 들어 있어 결과가 0건이 아닌 검색어를 골랐다(#72 설계 7).
export const RECOMMENDED_KEYWORDS = ["청년 월세", "출산", "장학금", "난임", "전세", "취업", "기초연금", "아동수당"];
