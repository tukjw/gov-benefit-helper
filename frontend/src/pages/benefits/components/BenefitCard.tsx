import type { ReactNode } from "react";
import { Link } from "react-router";
import { BookmarkButton } from "@/components/BookmarkButton";
import { Badge } from "@/components/ui/Badge";
import type { BenefitSummary } from "@/lib/api/types";
import { applyPeriodLabel, hasText } from "@/lib/benefits/display";
import { agencyTypeLabel } from "@/lib/benefits/options";
import styles from "./BenefitCard.module.css";

// 카드 전체가 상세 링크다. 관심 저장 버튼은 링크 안에 넣을 수 없어 형제 요소로 두고 오른쪽 위에 겹친다.
// 조건 비교 목록은 확인할 조건을 children으로 넘기고, 요약 아래·운영 기관 정보 위에 둔다.
export function BenefitCard({ benefit, children }: { benefit: BenefitSummary; children?: ReactNode }) {
  const periodLabel = applyPeriodLabel(benefit.applyPeriodType);
  const agency = [benefit.agencyName, agencyTypeLabel(benefit.agencyType)].filter(Boolean).join(" · ");
  const applyPeriodText = hasText(benefit.applyPeriodText) ? benefit.applyPeriodText : null;

  return (
    <article className={styles.card}>
      <Link to={`/benefits/${encodeURIComponent(benefit.serviceId)}`} className={styles.link}>
        <div className={styles.badges}>
          {benefit.field && <Badge>{benefit.field}</Badge>}
          {periodLabel && <Badge tone="neutral">{periodLabel}</Badge>}
        </div>
        <h3 className={styles.title}>{benefit.name}</h3>
        {hasText(benefit.summary) && <p className={styles.summary}>{benefit.summary}</p>}
        {children}
        {(agency || applyPeriodText) && (
          <dl className={styles.meta}>
            {agency && (
              <div>
                <dt>운영 기관</dt>
                <dd>{agency}</dd>
              </div>
            )}
            {applyPeriodText && (
              <div>
                <dt>신청기한</dt>
                <dd>{applyPeriodText}</dd>
              </div>
            )}
          </dl>
        )}
      </Link>
      <div className={styles.bookmark}>
        <BookmarkButton serviceId={benefit.serviceId} name={benefit.name} />
      </div>
    </article>
  );
}
