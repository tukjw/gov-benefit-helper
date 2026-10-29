import type { BenefitSearchParams } from "@/lib/api/benefits";

// 화면 URL의 파라미터 이름은 API와 같다(#72 설계 3). 쪽 크기는 고정이라 URL에 두지 않는다.

// API의 page는 int라 그보다 큰 값은 400이 된다.
const MAX_PAGE = 2_147_483_647;

function all(values: string[]): string[] {
  return [...new Set(values.filter((item) => item.trim() !== ""))];
}

// 잘못된 page(숫자가 아님, 1 미만, 너무 큼)는 오류 대신 첫 쪽으로 본다(#72 설계 4).
function parsePage(value: string): number {
  if (!/^\d+$/.test(value)) return 1;
  const page = Number(value);
  return page >= 1 && page <= MAX_PAGE ? page : 1;
}

export function parseSearchParams(raw: URLSearchParams): BenefitSearchParams {
  const first = (name: string) => raw.get(name) ?? "";
  return {
    keyword: first("keyword").trim(),
    fields: all(raw.getAll("field")),
    agencyTypes: all(raw.getAll("agencyType")),
    agencySidoCode: first("agencySidoCode").trim(),
    agencySigunguCode: first("agencySigunguCode").trim(),
    page: parsePage(first("page")),
  };
}

export function hasConditions(params: BenefitSearchParams): boolean {
  return Boolean(
    params.keyword ||
      params.fields.length ||
      params.agencyTypes.length ||
      params.agencySidoCode ||
      params.agencySigunguCode,
  );
}

// 페이지 이동 링크처럼 조건을 유지한 채 일부만 바꾼 주소를 만든다. 첫 쪽은 page를 생략한다.
export function benefitsHref(params: BenefitSearchParams, overrides: Partial<BenefitSearchParams> = {}): string {
  const next = { ...params, ...overrides };
  const query = new URLSearchParams();
  if (next.keyword) query.set("keyword", next.keyword);
  next.fields.forEach((field) => query.append("field", field));
  next.agencyTypes.forEach((type) => query.append("agencyType", type));
  if (next.agencySidoCode) query.set("agencySidoCode", next.agencySidoCode);
  if (next.agencySigunguCode) query.set("agencySigunguCode", next.agencySigunguCode);
  if (next.page > 1) query.set("page", String(next.page));
  return query.size > 0 ? `/benefits?${query}` : "/benefits";
}
