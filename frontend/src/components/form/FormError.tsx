import type { ReactNode } from "react";
import styles from "./FormError.module.css";

// 입력 칸 하나에 붙일 수 없는 제출 오류. 폼 맨 위에 둔다.
export function FormError({ children }: { children: ReactNode }) {
  return (
    <div role="alert" className={styles.box}>
      {children}
    </div>
  );
}
