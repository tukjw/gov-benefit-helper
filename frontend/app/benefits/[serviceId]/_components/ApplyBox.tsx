import type { BenefitDetailResponse } from "@/lib/api/types";
import { hasText, orNoInfo } from "@/lib/benefits/display";
import styles from "./ApplyBox.module.css";
import { ExternalLink } from "./ExternalLink";

// 신청 버튼은 링크로 쓸 수 있는 온라인 신청 주소가 있을 때만 둔다. 없으면 접수 방법과 공식 안내 페이지를
// 보여주고, 이 서비스에서 신청이 끝나는 것처럼 표시하지 않는다(REQ-DETAIL-01, 결정 0011).
export function ApplyBox({ benefit, periodLabel }: { benefit: BenefitDetailResponse; periodLabel: string | null }) {
  const { detail, applyPeriod } = benefit;
  const onlineApplyUrl = detail?.onlineApplyUrl ?? null;
  const applyMethod = detail?.applyMethod ?? null;

  return (
    <aside className={styles.box} aria-labelledby="apply-title">
      <h2 id="apply-title" className={styles.title}>
        신청 안내
      </h2>
      <dl className={styles.facts}>
        <div>
          <dt>신청기한</dt>
          <dd>
            {hasText(applyPeriod.text) ? applyPeriod.text : (periodLabel ?? orNoInfo(null))}
            {applyPeriod.needsReview && <span className={styles.note}>기한 표기가 불분명해요. 원문을 확인하세요.</span>}
          </dd>
        </div>
        <div>
          <dt>신청 방법</dt>
          <dd>{orNoInfo(benefit.applyMethodType)}</dd>
        </div>
        <div>
          <dt>접수 기관</dt>
          <dd>{orNoInfo(detail?.receptionAgencyName ?? benefit.receptionAgency)}</dd>
        </div>
      </dl>

      {onlineApplyUrl ? (
        <div className={styles.actions}>
          <ExternalLink href={onlineApplyUrl} className={`button button-primary ${styles.primary}`}>
            온라인 신청하기
          </ExternalLink>
          <p className={styles.help}>신청은 담당 기관 누리집에서 진행돼요.</p>
        </div>
      ) : (
        <div className={styles.actions}>
          <p className={styles.method}>
            <strong>온라인 신청 주소가 없어요.</strong> 아래 접수 방법을 확인하세요.
          </p>
          <p className={styles.methodText}>{orNoInfo(applyMethod ?? benefit.applyMethodType)}</p>
        </div>
      )}

      {benefit.guideUrl && (
        <ExternalLink href={benefit.guideUrl} className={`button ${styles.secondary}`}>
          공식 안내 페이지
        </ExternalLink>
      )}
    </aside>
  );
}
