import type { ReactNode } from "react";
import { Breadcrumb } from "@/components/layout/Breadcrumb";
import styles from "./AccountPage.module.css";

// 계정 화면의 가운데 단일 열(시안 7. 로그인). 가입·재설정·탈퇴도 같은 틀을 쓴다(docs/design/README.md F2·F4).
export function AccountFrame({ title, description, children }: { title: string; description?: ReactNode; children: ReactNode }) {
  return (
    <div className="container">
      <Breadcrumb items={[{ label: title }]} />
      <div className={styles.page}>
        <div className={styles.heading}>
          <h1 className={styles.title}>{title}</h1>
          {description && <p className={styles.description}>{description}</p>}
        </div>
        {children}
      </div>
    </div>
  );
}

// 첫 화면에서 로그인 상태를 확인하는 동안
export function AccountFallback({ title }: { title: string }) {
  return (
    <AccountFrame title={title}>
      <p className={styles.loading}>불러오는 중입니다.</p>
    </AccountFrame>
  );
}
