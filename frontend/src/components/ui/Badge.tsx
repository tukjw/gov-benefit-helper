import type { ReactNode } from "react";
import styles from "./Badge.module.css";

type Tone = "primary" | "neutral" | "warning";

export function Badge({ tone = "primary", children }: { tone?: Tone; children: ReactNode }) {
  return <span className={`${styles.badge} ${styles[tone]}`}>{children}</span>;
}
