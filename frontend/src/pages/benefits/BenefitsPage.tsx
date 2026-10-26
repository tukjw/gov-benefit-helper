import type { ReactNode } from "react";
import { Breadcrumb } from "@/components/layout/Breadcrumb";
import { usePageTitle } from "@/components/usePageTitle";
import { BenefitSearch } from "./components/BenefitSearch";
import styles from "./BenefitsPage.module.css";

export function BenefitsPage() {
  usePageTitle("전체 혜택");
  return (
    <BenefitsFrame>
      <BenefitSearch />
    </BenefitsFrame>
  );
}

// 첫 화면에서 loader가 끝나기 전에는 검색 결과 자리에 안내 문구를 둔다.
export function BenefitsFallback() {
  return (
    <BenefitsFrame>
      <p className={styles.loading}>검색 결과를 불러오는 중입니다.</p>
    </BenefitsFrame>
  );
}

function BenefitsFrame({ children }: { children: ReactNode }) {
  return (
    <div className="container">
      <Breadcrumb items={[{ label: "전체 혜택" }]} />
      <h1 className={styles.title}>전체 혜택</h1>
      {children}
    </div>
  );
}
