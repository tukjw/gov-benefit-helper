import type { ReactNode } from "react";
import { orNoInfo } from "@/lib/benefits/display";
import styles from "./BenefitDetailView.module.css";

// 원문 그대로 보여준다. 줄바꿈은 살리고, 값이 없으면 '정보 없음'이다.
export function TextSection({ title, text, children }: { title: string; text: string | null; children?: ReactNode }) {
  return (
    <section className={styles.section}>
      <h2 className={styles.sectionTitle}>{title}</h2>
      {children}
      <p className={styles.preLine}>{orNoInfo(text)}</p>
    </section>
  );
}
