import { Breadcrumb } from "@/components/layout/Breadcrumb";
import { Badge } from "@/components/ui/Badge";
import { InfoBox } from "@/components/ui/InfoBox";
import type { BenefitDetailResponse } from "@/lib/api/types";
import { NO_INFO, applyPeriodLabel, formatDateTime, hasText, orNoInfo } from "@/lib/benefits/display";
import { agencyTypeLabel } from "@/lib/benefits/options";
import { ApplyBox } from "./ApplyBox";
import styles from "./BenefitDetailView.module.css";
import { ExternalLink } from "./ExternalLink";
import { TextSection } from "./TextSection";

export function BenefitDetailView({ benefit }: { benefit: BenefitDetailResponse }) {
  const { detail } = benefit;
  const periodLabel = applyPeriodLabel(benefit.applyPeriod.type);
  const agency = [benefit.agencyName, benefit.departmentName].filter(hasText).join(" ");

  return (
    <>
      <Breadcrumb
        items={[
          { label: "전체 혜택", href: "/benefits" },
          ...(benefit.field
            ? [{ label: benefit.field, href: `/benefits?${new URLSearchParams({ field: benefit.field })}` }]
            : []),
          { label: benefit.name },
        ]}
      />

      <header className={styles.header}>
        <div className={styles.badges}>
          {benefit.field && <Badge>{benefit.field}</Badge>}
          {periodLabel && <Badge tone="neutral">{periodLabel}</Badge>}
        </div>
        <h1 className={styles.title}>{benefit.name}</h1>
        {agency && (
          <p className={styles.agency}>
            {agency}
            {benefit.agencyType && <> · {agencyTypeLabel(benefit.agencyType)}</>}
          </p>
        )}
        {hasText(benefit.summary) && <p className={styles.summary}>{benefit.summary}</p>}
      </header>

      <div className={styles.columns}>
        <div className={styles.body}>
          <TextSection title="지원 내용" text={benefit.supportContent}>
            {hasText(benefit.supportType) && <p className={styles.supportType}>지원 유형: {benefit.supportType}</p>}
          </TextSection>
          <TextSection title="지원 대상" text={benefit.target} />
          <TextSection title="선정 기준" text={benefit.selectionCriteria} />

          {detail ? (
            <>
              <TextSection title="신청 방법" text={detail.applyMethod} />
              <section className={styles.section} aria-labelledby="documents-title">
                <h2 id="documents-title" className={styles.sectionTitle}>
                  구비서류
                </h2>
                <dl className={styles.documents}>
                  <DocumentRow label="제출 서류" value={detail.requiredDocuments} />
                  <DocumentRow label="담당 공무원이 확인하는 서류" value={detail.officialCheckDocuments} />
                  <DocumentRow label="본인이 확인에 동의하는 서류" value={detail.selfCheckDocuments} />
                </dl>
              </section>
            </>
          ) : (
            <InfoBox title="상세 정보를 아직 받지 못했어요">
              신청 방법과 구비서류는 공식 안내 페이지에서 확인하세요.
            </InfoBox>
          )}

          <section className={styles.section} aria-labelledby="contact-title">
            <h2 id="contact-title" className={styles.sectionTitle}>
              문의처
            </h2>
            <table className={styles.table}>
              <tbody>
                <tr>
                  <th scope="row">소관 기관</th>
                  <td>{orNoInfo(agency)}</td>
                </tr>
                <tr>
                  <th scope="row">접수 기관</th>
                  <td>{orNoInfo(detail?.receptionAgencyName ?? benefit.receptionAgency)}</td>
                </tr>
                <tr>
                  <th scope="row">문의처</th>
                  <td className={styles.preLine}>{orNoInfo(detail?.contact)}</td>
                </tr>
                <tr>
                  <th scope="row">전화 문의</th>
                  <td className={styles.preLine}>{orNoInfo(benefit.phoneInquiry)}</td>
                </tr>
              </tbody>
            </table>
          </section>

          <section className={styles.section} aria-labelledby="source-title">
            <h2 id="source-title" className={styles.sectionTitle}>
              출처와 수집 시각
            </h2>
            {/* 출처 수정 시각과 수집 시각은 다른 값이다. 출처 수정 시각이 없으면 수집 시각으로 채우지 않는다. */}
            <dl className={styles.source}>
              <div>
                <dt>출처 수정 시각</dt>
                <dd>{formatDateTime(benefit.sourceModifiedAt) ?? NO_INFO}</dd>
              </div>
              <div>
                <dt>수집 시각(목록에서 마지막으로 확인)</dt>
                <dd>{formatDateTime(benefit.lastSeenAt) ?? NO_INFO}</dd>
              </div>
              <div>
                <dt>공식 안내 페이지</dt>
                <dd>{benefit.guideUrl ? <ExternalLink href={benefit.guideUrl}>정부24에서 보기</ExternalLink> : NO_INFO}</dd>
              </div>
            </dl>
          </section>
        </div>

        <ApplyBox benefit={benefit} periodLabel={periodLabel} />
      </div>
    </>
  );
}

function DocumentRow({ label, value }: { label: string; value: string | null }) {
  return (
    <div>
      <dt>{label}</dt>
      <dd className={styles.preLine}>{orNoInfo(value)}</dd>
    </div>
  );
}
