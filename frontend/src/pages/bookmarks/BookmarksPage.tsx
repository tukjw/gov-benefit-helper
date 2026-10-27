import type { ReactNode } from "react";
import { Link, useLoaderData } from "react-router";
import { Breadcrumb } from "@/components/layout/Breadcrumb";
import { ViewToggle } from "@/components/preferences/ViewToggle";
import { EmptyState } from "@/components/ui/EmptyState";
import { usePageTitle } from "@/components/usePageTitle";
import { loginPath } from "@/lib/auth/guard";
import { BenefitCard } from "@/pages/benefits/components/BenefitCard";
import { Pagination } from "@/pages/benefits/components/Pagination";
import listStyles from "@/pages/benefits/components/ResultList.module.css";
import { bookmarksHref, type BookmarksData } from "./bookmarksRoute";
import styles from "./BookmarksPage.module.css";

// 관심 목록(F3). 마지막으로 저장한 순서다. 별을 눌러 해제한 카드는 다른 화면에 갔다 올 때까지 그 자리에 남긴다(#74 설계 8).
export function BookmarksPage() {
  usePageTitle("관심 혜택");
  const data = useLoaderData() as BookmarksData;

  if (data.kind === "guest") {
    return (
      <BookmarksFrame>
        <EmptyState
          title="로그인하면 관심 혜택을 모아 볼 수 있어요"
          description="혜택 카드와 상세의 별 버튼으로 저장한 혜택이 여기에 보여요."
          actions={
            <Link to={loginPath("/bookmarks")} className="button button-primary">
              로그인
            </Link>
          }
        />
      </BookmarksFrame>
    );
  }

  const { page, response } = data;
  const totalPages = Math.ceil(response.totalCount / response.size);
  const browse = (
    <>
      <Link to="/benefits" className="button button-primary">
        혜택 찾아보기
      </Link>
      <Link to="/match" className="button">
        조건 비교하기
      </Link>
    </>
  );

  return (
    <BookmarksFrame>
      {response.totalCount === 0 ? (
        <EmptyState
          title="저장한 관심 혜택이 없어요"
          description="혜택 카드와 상세의 별 버튼으로 저장해 보세요."
          actions={browse}
        />
      ) : response.items.length === 0 ? (
        <EmptyState
          title="이 쪽에는 관심 혜택이 없어요"
          actions={
            <Link to={bookmarksHref(1)} className="button button-primary">
              첫 쪽으로
            </Link>
          }
        />
      ) : (
        <>
          <div className={listStyles.toolbar}>
            <p className={listStyles.count}>
              관심 혜택 <strong>{response.totalCount.toLocaleString("ko-KR")}</strong>건
            </p>
            <ViewToggle />
          </div>
          <ul className={listStyles.list}>
            {response.items.map((item) => (
              <li key={item.benefit.serviceId}>
                <BenefitCard benefit={item.benefit} />
              </li>
            ))}
          </ul>
          <Pagination page={page} totalPages={totalPages} hrefFor={bookmarksHref} />
        </>
      )}
    </BookmarksFrame>
  );
}

export function BookmarksFallback() {
  return (
    <BookmarksFrame>
      <p className={styles.loading}>관심 혜택을 불러오는 중입니다.</p>
    </BookmarksFrame>
  );
}

function BookmarksFrame({ children }: { children: ReactNode }) {
  return (
    <div className="container">
      <Breadcrumb items={[{ label: "관심 혜택" }]} />
      <h1 className={styles.title}>관심 혜택</h1>
      <div className={styles.page}>{children}</div>
    </div>
  );
}
