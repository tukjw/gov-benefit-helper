import Link from "next/link";
import { Badge } from "@/components/ui/Badge";
import type { BenefitSummary } from "@/lib/api/types";
import { applyPeriodLabel, formatDate, hasText } from "@/lib/benefits/display";
import { agencyTypeLabel } from "@/lib/benefits/options";
import styles from "./BenefitCard.module.css";

// 카드 전체가 상세 링크다. 관심 저장 버튼(#74)은 링크 안이 아니라 형제 요소로 붙인다.
export function BenefitCard({ benefit }: { benefit: BenefitSummary }) {
  const periodLabel = applyPeriodLabel(benefit.applyPeriodType);
  const agency = [benefit.agencyName, agencyTypeLabel(benefit.agencyType)].filter(Boolean).join(" · ");
  const lastSeen = formatDate(benefit.lastSeenAt);

  return (
    <article className={styles.card}>
      <Link href={`/benefits/${encodeURIComponent(benefit.serviceId)}`} className={styles.link}>
        <div className={styles.badges}>
          {benefit.field && <Badge>{benefit.field}</Badge>}
          {periodLabel && <Badge tone="neutral">{periodLabel}</Badge>}
        </div>
        <h3 className={styles.title}>{benefit.name}</h3>
        {hasText(benefit.summary) && <p className={styles.summary}>{benefit.summary}</p>}
        <dl className={styles.meta}>
          {agency && (
            <div>
              <dt>운영 기관</dt>
              <dd>{agency}</dd>
            </div>
          )}
          {hasText(benefit.applyPeriodText) && (
            <div>
              <dt>신청기한</dt>
              <dd>{benefit.applyPeriodText}</dd>
            </div>
          )}
          {lastSeen && (
            <div>
              <dt>마지막 확인</dt>
              <dd>{lastSeen}</dd>
            </div>
          )}
        </dl>
      </Link>
    </article>
  );
}
