import { useLoaderData } from "react-router";
import { SearchBox } from "@/components/SearchBox";
import { benefitsHref, hasConditions } from "@/lib/benefits/searchParams";
import type { benefitsLoader } from "../benefitsLoader";
import styles from "./BenefitSearch.module.css";
import { FilterDisclosure } from "./FilterDisclosure";
import { FilterPanel } from "./FilterPanel";
import { ResultList } from "./ResultList";

export function BenefitSearch() {
  const { params, sidos, outcome } = useLoaderData<typeof benefitsLoader>();
  // 같은 화면 안에서 이동하면 컴포넌트와 입력값이 유지되므로, 조건이 바뀌면 폼을 새로 그린다.
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
          <FilterPanel key={formKey} params={params} sidos={sidos} />
        </FilterDisclosure>
        <ResultList params={params} outcome={outcome} conditioned={hasConditions(params)} />
      </div>
    </>
  );
}

