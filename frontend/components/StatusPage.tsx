import type { ReactNode } from "react";
import styles from "./StatusPage.module.css";

// 찾을 수 없음·오류 화면의 공통 틀
export function StatusPage({ title, description, actions }: { title: string; description: string; actions: ReactNode }) {
  return (
    <div className={`container ${styles.page}`}>
      <h1 className={styles.title}>{title}</h1>
      <p className={styles.description}>{description}</p>
      <div className={styles.actions}>{actions}</div>
    </div>
  );
}
