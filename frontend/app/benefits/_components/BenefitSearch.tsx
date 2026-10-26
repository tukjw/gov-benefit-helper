import { SearchBox } from "@/components/SearchBox";
import { getRegions, searchBenefits, type BenefitSearchParams } from "@/lib/api/benefits";
import { ApiError } from "@/lib/api/client";
import type { BenefitSearchResponse } from "@/lib/api/types";
import { benefitsHref, hasConditions, parseSearchParams } from "@/lib/benefits/searchParams";
import styles from "./BenefitSearch.module.css";
import { FilterDisclosure } from "./FilterDisclosure";
import { FilterPanel } from "./FilterPanel";
import { ResultList } from "./ResultList";

export type SearchOutcome =
  | { kind: "ok"; response: BenefitSearchResponse }
  // 400: 키워드 길이·단어 수, 지역 코드처럼 URL을 직접 고친 경우다. 오류 화면 대신 안내한다(#72 설계 4).
  | { kind: "invalid"; reasons: string[] };

export async function BenefitSearch({ searchParams }: { searchParams: PageProps<"/benefits">["searchParams"] }) {
  const params = parseSearchParams(await searchParams);
  const [regions, outcome] = await Promise.all([getRegions(), search(params)]);
  // 링크로 이동하면 클라이언트 컴포넌트와 입력값이 유지되므로, 조건이 바뀌면 폼을 새로 그린다.
  const formKey = benefitsHref(params, { page: 1 });
  const filtered = params.fields.length + params.agencyTypes.length > 0 || Boolean(params.agencySidoCode);

  return (
    <>
      <SearchBox key={formKey} id="benefits-search" defaultValue={params.keyword}>
        {/* 키워드를 바꿔도 필터는 유지하고 첫 쪽부터 본다(#72 설계 5). */}
        {params.fields.map((field) => (
          <input key={`field-${field}`} type="hidden" name="field" value={field} />
        ))}
        {params.agencyTypes.map((type) => (
          <input key={`type-${type}`} type="hidden" name="agencyType" value={type} />
        ))}
        {params.agencySidoCode && <input type="hidden" name="agencySidoCode" value={params.agencySidoCode} />}
        {params.agencySigunguCode && (
          <input type="hidden" name="agencySigunguCode" value={params.agencySigunguCode} />
        )}
      </SearchBox>
      <div className={styles.columns}>
        <FilterDisclosure active={filtered}>
          <FilterPanel key={formKey} params={params} sidos={regions.sidos} />
        </FilterDisclosure>
        <ResultList params={params} outcome={outcome} conditioned={hasConditions(params)} />
      </div>
    </>
  );
}

async function search(params: BenefitSearchParams): Promise<SearchOutcome> {
  try {
    return { kind: "ok", response: await searchBenefits(params) };
  } catch (error) {
    if (error instanceof ApiError && error.status === 400) {
      return { kind: "invalid", reasons: error.errors.map((item) => item.reason) };
    }
    throw error;
  }
}
