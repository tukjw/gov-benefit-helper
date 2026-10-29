import { Link } from "react-router";
import type { MatchListParams } from "@/lib/api/match";
import type { RegionScope } from "@/lib/api/types";
import { matchHref } from "../matchRoute";
import styles from "../MatchPage.module.css";

const SCOPES: { value: RegionScope; label: string }[] = [
  { value: "MY_REGION", label: "내 지역" },
  { value: "ALL", label: "전체 지역" },
];

// 운영 기관 지역 범위(DEC-MATCH). 현재 값은 서버가 실제로 적용한 범위다. 거주지가 없으면 전체 지역뿐이다.
export function RegionScopeToggle({
  params,
  applied,
  hasResidence,
}: {
  params: MatchListParams;
  applied: RegionScope;
  hasResidence: boolean;
}) {
  return (
    <div className={styles.scope}>
      {hasResidence ? (
        <ul className={styles.scopeTabs} aria-label="운영 기관 지역 범위">
          {SCOPES.map((scope) => (
            <li key={scope.value}>
              <Link
                to={matchHref(params, { scope: scope.value, page: 1 })}
                className={styles.scopeTab}
                aria-current={applied === scope.value ? "true" : undefined}
              >
                {scope.label}
              </Link>
            </li>
          ))}
        </ul>
      ) : (
        <p className={styles.scopeOnly}>
          <strong>전체 지역</strong> 거주지를 입력하면 내 지역으로 볼 수 있어요.
        </p>
      )}
      <p className={styles.scopeNote}>운영 기관이 있는 지역 기준이며 거주 요건이 아니에요.</p>
    </div>
  );
}
