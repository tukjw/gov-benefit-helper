import { useLoaderData } from "react-router";
import { usePageTitle } from "@/components/usePageTitle";
import type { BenefitDetailData } from "./benefitDetailLoader";
import styles from "./BenefitDetailPage.module.css";
import { BenefitDetailView } from "./components/BenefitDetailView";

export function BenefitDetailPage() {
  const { benefit, match } = useLoaderData() as BenefitDetailData;
  usePageTitle(benefit.name);
  return (
    <div className="container">
      <BenefitDetailView benefit={benefit} match={match} />
    </div>
  );
}

// 첫 화면에서 loader가 끝나기 전에 보인다.
export function BenefitDetailFallback() {
  return (
    <div className="container">
      <p className={styles.loading}>혜택 정보를 불러오는 중입니다.</p>
    </div>
  );
}
