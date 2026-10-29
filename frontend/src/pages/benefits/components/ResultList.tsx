import { Link } from "react-router";
import { ViewToggle } from "@/components/preferences/ViewToggle";
import { EmptyState } from "@/components/ui/EmptyState";
import { InfoBox } from "@/components/ui/InfoBox";
import type { BenefitSearchParams } from "@/lib/api/benefits";
import { benefitsHref } from "@/lib/benefits/searchParams";
import { BenefitCard } from "./BenefitCard";
import type { SearchOutcome } from "../benefitsLoader";
import { Pagination } from "./Pagination";
import styles from "./ResultList.module.css";

const resetLink = (
  <Link to="/benefits" className="button">
    필터 초기화
  </Link>
);

export function ResultList({
  params,
  outcome,
  conditioned,
}: {
  params: BenefitSearchParams;
  outcome: SearchOutcome;
  conditioned: boolean;
}) {
  return (
    <section className={styles.results} aria-labelledby="results-title">
      <h2 id="results-title" className="visually-hidden">
        검색 결과
      </h2>
      <Results params={params} outcome={outcome} conditioned={conditioned} />
    </section>
  );
}

function Results({ params, outcome, conditioned }: Parameters<typeof ResultList>[0]) {
  if (outcome.kind === "invalid") {
    return (
      <InfoBox tone="warning" title="검색 조건을 확인해 주세요">
        {outcome.reasons.length > 0 && (
          <ul className={styles.reasons}>
            {[...new Set(outcome.reasons)].map((reason) => (
              <li key={reason}>{reason}</li>
            ))}
          </ul>
        )}
        <div className={styles.invalidActions}>{resetLink}</div>
      </InfoBox>
    );
  }

  const { totalCount, size, items } = outcome.response;
  if (totalCount === 0) {
    return (
      <EmptyState
        title="검색 결과가 없습니다"
        description={conditioned ? "검색어를 바꾸거나 필터를 줄여 보세요." : undefined}
        actions={resetLink}
      />
    );
  }

  const totalPages = Math.ceil(totalCount / size);
  if (items.length === 0) {
    // 마지막 쪽을 넘은 번호는 API가 빈 목록으로 준다.
    return (
      <EmptyState
        title="이 쪽에는 결과가 없습니다"
        description={`검색 결과는 총 ${totalCount.toLocaleString("ko-KR")}건, ${totalPages.toLocaleString("ko-KR")}쪽까지 있습니다.`}
        actions={
          <>
            <Link to={benefitsHref(params, { page: 1 })} className="button button-primary">
              첫 쪽으로
            </Link>
            {resetLink}
          </>
        }
      />
    );
  }

  return (
    <>
      <div className={styles.toolbar}>
        <p className={styles.count}>
          {params.keyword && <>‘{params.keyword}’ 검색 결과 </>}총{" "}
          <strong>{totalCount.toLocaleString("ko-KR")}</strong>건
        </p>
        <ViewToggle />
      </div>
      <ul className={styles.list}>
        {items.map((item) => (
          <li key={item.serviceId}>
            <BenefitCard benefit={item} />
          </li>
        ))}
      </ul>
      <Pagination page={params.page} totalPages={totalPages} hrefFor={(page) => benefitsHref(params, { page })} />
    </>
  );
}
