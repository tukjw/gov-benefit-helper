import { ChevronLeft, ChevronRight } from "lucide-react";
import Link from "next/link";
import type { BenefitSearchParams } from "@/lib/api/benefits";
import { benefitsHref } from "@/lib/benefits/searchParams";
import styles from "./Pagination.module.css";

const WINDOW = 5;

// 쪽 수는 결과 건수로 계산한다. 건수는 이후 빠질 수 있는 항목이라(DEC-SEARCH) 그때 이전·다음만 남긴다.
export function Pagination({ params, totalPages }: { params: BenefitSearchParams; totalPages: number }) {
  if (totalPages <= 1) return null;

  const current = params.page;
  const start = Math.max(1, Math.min(current - Math.floor(WINDOW / 2), totalPages - WINDOW + 1));
  const end = Math.min(totalPages, start + WINDOW - 1);
  const pages = Array.from({ length: end - start + 1 }, (_, index) => start + index);

  return (
    <nav aria-label="페이지" className={styles.pagination}>
      {current > 1 && (
        <Link href={benefitsHref(params, { page: current - 1 })} className={styles.step}>
          <ChevronLeft size={18} aria-hidden="true" />
          이전
        </Link>
      )}
      <ol className={styles.pages}>
        {pages.map((page) => (
          <li key={page}>
            <Link
              href={benefitsHref(params, { page })}
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
        <Link href={benefitsHref(params, { page: current + 1 })} className={styles.step}>
          다음
          <ChevronRight size={18} aria-hidden="true" />
        </Link>
      )}
    </nav>
  );
}
