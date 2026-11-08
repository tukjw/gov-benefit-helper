import type { ReactNode } from "react";
import { Breadcrumb, type BreadcrumbItem } from "@/components/layout/Breadcrumb";
import { ACCOUNT_PATH, ACCOUNT_TITLE } from "@/lib/account/paths";
import styles from "./AccountPage.module.css";

type FrameProps = {
  title: string;
  description?: ReactNode;
  // 계정 관리 아래 화면(알림 설정·탈퇴)이면 위치 표시에 계정 관리를 넣는다(#134 설계 5).
  underAccount?: boolean;
};

// 계정 화면의 가운데 단일 열(시안 7. 로그인). 가입·재설정·탈퇴·계정 관리도 같은 틀을 쓴다(docs/design/README.md F2·F4·F5).
export function AccountFrame({ title, description, underAccount = false, children }: FrameProps & { children: ReactNode }) {
  const items: BreadcrumbItem[] = underAccount
    ? [{ label: ACCOUNT_TITLE, href: ACCOUNT_PATH }, { label: title }]
    : [{ label: title }];
  return (
    <div className="container">
      <Breadcrumb items={items} />
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
export function AccountFallback({ title, underAccount }: Omit<FrameProps, "description">) {
  return (
    <AccountFrame title={title} underAccount={underAccount}>
      <p className={styles.loading}>불러오는 중입니다.</p>
    </AccountFrame>
  );
}
