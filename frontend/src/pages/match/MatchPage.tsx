import type { ReactNode } from "react";
import { Form, Link, useLoaderData } from "react-router";
import { Breadcrumb } from "@/components/layout/Breadcrumb";
import { ViewToggle } from "@/components/preferences/ViewToggle";
import { Badge } from "@/components/ui/Badge";
import { EmptyState } from "@/components/ui/EmptyState";
import { InfoBox } from "@/components/ui/InfoBox";
import { usePageTitle } from "@/components/usePageTitle";
import type { MatchListParams } from "@/lib/api/match";
import type { MatchListItem, MatchListResponse } from "@/lib/api/types";
import { NOTICE_TEXTS, RESULT_DESCRIPTIONS, RESULT_LABELS, RESULT_TONES, UNCOMPARED_LABELS } from "@/lib/match/labels";
import { GUEST_NOTICE } from "@/lib/profile/guestProfile";
import { hasResidence, summarizeProfile } from "@/lib/profile/profileForm";
import { Pagination } from "@/pages/benefits/components/Pagination";
import listStyles from "@/pages/benefits/components/ResultList.module.css";
import { MatchCard } from "./components/MatchCard";
import { RegionScopeToggle } from "./components/RegionScopeToggle";
import { matchHref, type MatchData } from "./matchRoute";
import styles from "./MatchPage.module.css";

// 조건 비교 목록(F3). 해당 → 확인 필요, 이름순은 서버가 정한다. 미해당(개인 대상 아님)은 목록에 없다.
export function MatchPage() {
  usePageTitle("조건 비교");
  const { member, options, profile, params, response } = useLoaderData() as MatchData;
  const { totalCount, size, items } = response;
  const totalPages = Math.ceil(totalCount / size);

  return (
    <MatchFrame>
      <section className={styles.summary} aria-labelledby="summary-title">
        <div className={styles.summaryHead}>
          <h2 id="summary-title" className={styles.summaryTitle}>
            입력한 조건
          </h2>
          <div className={styles.summaryActions}>
            <Link to="/profile" className="button">
              조건 수정
            </Link>
            {!member && (
              <Form method="post">
                <button type="submit" className="button">
                  처음부터 다시
                </button>
              </Form>
            )}
          </div>
        </div>
        <dl className={styles.summaryList}>
          {summarizeProfile(profile, options).map((row) => (
            <div key={row.label}>
              <dt>{row.label}</dt>
              <dd>{row.value}</dd>
            </div>
          ))}
        </dl>
        {!member && <p className={styles.guestNotice}>{GUEST_NOTICE}</p>}
      </section>

      <RegionScopeToggle params={params} applied={response.regionScope} hasResidence={hasResidence(profile)} />

      <InfoBox title="결과를 보는 방법">
        <ul className={styles.notices}>
          {response.notices.map((notice) => (
            <li key={notice}>{NOTICE_TEXTS[notice]}</li>
          ))}
          <li>
            비교하지 않은 조건: {response.uncomparedConditions.map((condition) => UNCOMPARED_LABELS[condition]).join(", ")}.
            혜택 상세의 원문과 공식 안내에서 확인해 주세요.
          </li>
        </ul>
      </InfoBox>

      <section aria-labelledby="results-title">
        <h2 id="results-title" className="visually-hidden">
          조건 비교 결과
        </h2>
        {totalCount === 0 ? (
          <EmptyState
            title="조건 비교 결과가 없어요"
            description={response.regionScope === "MY_REGION" ? "전체 지역으로 넓혀 보세요." : undefined}
            actions={
              response.regionScope === "MY_REGION" ? (
                <Link to={matchHref(params, { scope: "ALL", page: 1 })} className="button button-primary">
                  전체 지역 보기
                </Link>
              ) : undefined
            }
          />
        ) : items.length === 0 ? (
          <EmptyState
            title="이 쪽에는 결과가 없어요"
            description={`조건 비교 결과는 ${totalPages.toLocaleString("ko-KR")}쪽까지 있어요.`}
            actions={
              <Link to={matchHref(params, { page: 1 })} className="button button-primary">
                첫 쪽으로
              </Link>
            }
          />
        ) : (
          <>
            <div className={listStyles.toolbar}>
              <p className={listStyles.count}>
                조건 비교 결과 <strong>{totalCount.toLocaleString("ko-KR")}</strong>건
              </p>
              <ViewToggle />
            </div>
            <ResultCounts params={params} response={response} />
            {/* 해당 → 확인 필요 순이라 한 쪽에 두 결과가 함께 있으면 결과별로 나눠 보여준다. */}
            {RESULT_GROUPS.map(({ result, label }) => {
              const group = items.filter((item) => item.result === result);
              if (group.length === 0) return null;
              return (
                <section key={result} className={styles.group} aria-labelledby={`group-${result}`}>
                  <h3 id={`group-${result}`} className={styles.groupTitle}>
                    <Badge tone={RESULT_TONES[result]}>{label}</Badge>
                    <span>{countOf(response, result).toLocaleString("ko-KR")}건</span>
                  </h3>
                  <p className={styles.groupDescription}>{RESULT_DESCRIPTIONS[result]}</p>
                  <ul className={listStyles.list}>
                    {group.map((item) => (
                      <li key={item.benefit.serviceId}>
                        <MatchCard item={item} />
                      </li>
                    ))}
                  </ul>
                </section>
              );
            })}
            <Pagination page={params.page} totalPages={totalPages} hrefFor={(page) => matchHref(params, { page })} />
          </>
        )}
      </section>
    </MatchFrame>
  );
}

const RESULT_GROUPS = [
  { result: "MATCH", label: RESULT_LABELS.MATCH },
  { result: "NEEDS_CHECK", label: RESULT_LABELS.NEEDS_CHECK },
] as const;

function countOf(response: MatchListResponse, result: MatchListItem["result"]): number {
  return result === "MATCH" ? response.matchCount : response.needsCheckCount;
}

// 결과별 건수. 누르면 그 결과가 시작하는 쪽으로 간다(해당이 먼저, 확인 필요가 뒤에 온다).
function ResultCounts({ params, response }: { params: MatchListParams; response: MatchListResponse }) {
  const needsCheckPage = Math.floor(response.matchCount / response.size) + 1;
  return (
    <ul className={styles.counts} aria-label="결과별 건수">
      {RESULT_GROUPS.map(({ result, label }) => {
        const count = countOf(response, result);
        const page = result === "MATCH" ? 1 : needsCheckPage;
        const body = (
          <>
            <Badge tone={RESULT_TONES[result]}>{label}</Badge>
            <span>
              <strong>{count.toLocaleString("ko-KR")}</strong>건
            </span>
          </>
        );
        return (
          <li key={result}>
            {count > 0 ? (
              <Link to={matchHref(params, { page })} className={styles.countItem}>
                {body}
              </Link>
            ) : (
              <span className={styles.countItem}>{body}</span>
            )}
          </li>
        );
      })}
    </ul>
  );
}

export function MatchFallback() {
  return (
    <MatchFrame>
      <p className={styles.loading}>조건 비교 결과를 불러오는 중입니다.</p>
    </MatchFrame>
  );
}

function MatchFrame({ children }: { children: ReactNode }) {
  return (
    <div className="container">
      <Breadcrumb items={[{ label: "조건 비교" }]} />
      <h1 className={styles.title}>조건 비교</h1>
      <div className={styles.page}>{children}</div>
    </div>
  );
}
