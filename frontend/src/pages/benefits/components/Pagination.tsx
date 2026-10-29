import { ChevronLeft, ChevronRight } from "lucide-react";
import { Link } from "react-router";
import styles from "./Pagination.module.css";

const WINDOW = 5;

// 쪽 수는 결과 건수로 계산한다. 건수는 이후 빠질 수 있는 항목이라(DEC-SEARCH) 그때 이전·다음만 남긴다.
// 검색·조건 비교·관심 목록이 함께 쓰며, 쪽 주소는 화면마다 hrefFor로 만든다.
export function Pagination({
  page: current,
  totalPages,
  hrefFor,
}: {
  page: number;
  totalPages: number;
  hrefFor: (page: number) => string;
}) {
  if (totalPages <= 1) return null;

  const start = Math.max(1, Math.min(current - Math.floor(WINDOW / 2), totalPages - WINDOW + 1));
  const end = Math.min(totalPages, start + WINDOW - 1);
  const pages = Array.from({ length: end - start + 1 }, (_, index) => start + index);

  return (
    <nav aria-label="페이지" className={styles.pagination}>
      {current > 1 && (
        <Link to={hrefFor(current - 1)} className={styles.step}>
          <ChevronLeft size={18} aria-hidden="true" />
          이전
        </Link>
      )}
      <ol className={styles.pages}>
        {pages.map((page) => (
          <li key={page}>
            <Link
              to={hrefFor(page)}
              className={styles.page}
              aria-current={page === current ? "page" : undefined}
              aria-label={`${page}쪽`}
            >
              {page}
            </Link>
          </li>
        ))}
      </ol>
      {current < totalPages && (
        <Link to={hrefFor(current + 1)} className={styles.step}>
          다음
          <ChevronRight size={18} aria-hidden="true" />
        </Link>
      )}
    </nav>
  );
}
