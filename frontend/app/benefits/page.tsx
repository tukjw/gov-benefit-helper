import type { Metadata } from "next";
import { Suspense } from "react";
import { Breadcrumb } from "@/components/layout/Breadcrumb";
import { BenefitSearch } from "./_components/BenefitSearch";
import styles from "./page.module.css";

export const metadata: Metadata = { title: "전체 혜택" };

// 검색 조건(searchParams)을 읽는 부분만 <Suspense> 안에서 요청마다 렌더링한다(#72 설계 2).
export default function BenefitsPage({ searchParams }: PageProps<"/benefits">) {
  return (
    <div className="container">
      <Breadcrumb items={[{ label: "전체 혜택" }]} />
      <h1 className={styles.title}>전체 혜택</h1>
      <Suspense fallback={<p className={styles.loading}>검색 결과를 불러오는 중입니다.</p>}>
        <BenefitSearch searchParams={searchParams} />
      </Suspense>
    </div>
  );
}
