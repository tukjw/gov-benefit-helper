import { TriangleAlert } from "lucide-react";
import type { MatchListItem } from "@/lib/api/types";
import { ITEM_KEYS, ITEM_LABELS } from "@/lib/match/labels";
import { BenefitCard } from "@/pages/benefits/components/BenefitCard";
import styles from "./MatchCard.module.css";

// 조건 비교 목록의 카드. 목록이 결과별로 묶여 있어 카드에는 결과 배지를 두지 않는다. 확인 필요 카드에는 확인할
// 조건의 이름만 한 줄로 붙인다. 이유는 대부분 같은 문구라 카드마다 반복하지 않고, 항목별 이유와 원문·공식 안내
// 링크는 상세에서 본다(#74 설계 9, 11).
export function MatchCard({ item }: { item: MatchListItem }) {
  const checks = ITEM_KEYS.filter((key) => item.items[key].status === "NEEDS_CHECK");
  return (
    <BenefitCard benefit={item.benefit}>
      {checks.length > 0 && (
        <p className={styles.checks}>
          <span className={styles.checksTitle}>
            <TriangleAlert size={16} aria-hidden="true" />
            확인할 조건
          </span>
          <span>{checks.map((key) => ITEM_LABELS[key]).join(", ")}</span>
        </p>
      )}
    </BenefitCard>
  );
}
