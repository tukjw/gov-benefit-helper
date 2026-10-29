import { Form, Link } from "react-router";
import type { BenefitSearchParams } from "@/lib/api/benefits";
import type { SidoOption } from "@/lib/api/types";
import { AGENCY_TYPES, FIELDS } from "@/lib/benefits/options";
import styles from "./FilterPanel.module.css";
import { RegionSelect } from "./RegionSelect";

// 체크박스 GET 폼이라 선택한 값이 그대로 URL이 되고 키보드로 쓸 수 있다(#72 설계 5).
export function FilterPanel({ params, sidos }: { params: BenefitSearchParams; sidos: SidoOption[] }) {
  return (
    <Form action="/benefits" method="get" className={styles.form}>
      {params.keyword && <input type="hidden" name="keyword" value={params.keyword} />}

      <fieldset className={styles.group}>
        {/* 필터 이름으로 거주 요건이 아님을 드러낸다(REQ-SEARCH-01, 결정 0025). */}
        <legend className={styles.legend}>운영 기관 지역</legend>
        <div className={styles.region}>
          <RegionSelect
            sidos={sidos}
            defaultSido={params.agencySidoCode}
            defaultSigungu={params.agencySigunguCode}
          />
        </div>
      </fieldset>

      <fieldset className={styles.group}>
        <legend className={styles.legend}>분야</legend>
        <ul className={styles.options}>
          {FIELDS.map(({ value }) => (
            <li key={value}>
              <label className={styles.option}>
                <input type="checkbox" name="field" value={value} defaultChecked={params.fields.includes(value)} />
                {value}
              </label>
            </li>
          ))}
        </ul>
      </fieldset>

      <fieldset className={styles.group}>
        <legend className={styles.legend}>운영 기관 유형</legend>
        <ul className={styles.options}>
          {AGENCY_TYPES.map(({ value, label }) => (
            <li key={value}>
              <label className={styles.option}>
                <input
                  type="checkbox"
                  name="agencyType"
                  value={value}
                  defaultChecked={params.agencyTypes.includes(value)}
                />
                {label}
              </label>
            </li>
          ))}
        </ul>
      </fieldset>

      <div className={styles.actions}>
        <button type="submit" className="button button-primary">
          필터 적용
        </button>
        <Link to="/benefits" className="button">
          필터 초기화
        </Link>
      </div>
    </Form>
  );
}
