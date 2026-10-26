import type { ReactNode } from "react";
import styles from "./InfoBox.module.css";

type Tone = "info" | "success" | "warning" | "danger";

// 화면을 열 때부터 있는 안내라 role="alert"를 쓰지 않는다. 입력 오류처럼 나중에 나타나는 알림에만 쓴다.
export function InfoBox({ tone = "info", title, children }: { tone?: Tone; title?: string; children?: ReactNode }) {
  return (
    <div className={`${styles.box} ${styles[tone]}`}>
      {title && <p className={styles.title}>{title}</p>}
      {children && <div className={styles.body}>{children}</div>}
    </div>
  );
}
