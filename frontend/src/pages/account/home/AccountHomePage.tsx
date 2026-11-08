import { ChevronRight } from "lucide-react";
import { Link, useLoaderData } from "react-router";
import { usePageTitle } from "@/components/usePageTitle";
import { ACCOUNT_TITLE, NOTIFICATION_SETTINGS_PATH, WITHDRAWAL_PATH } from "@/lib/account/paths";
import { AccountFrame } from "../AccountFrame";
import styles from "../AccountPage.module.css";
import type { accountLoader } from "./accountRoute";

const MENU = [
  { to: NOTIFICATION_SETTINGS_PATH, label: "알림 설정", description: "새 혜택 알림을 켜고 끕니다." },
  { to: WITHDRAWAL_PATH, label: "회원 탈퇴", description: "계정과 저장한 정보를 모두 지웁니다." },
];

// 헤더의 이메일에서 여는 계정 화면(#134 설계 4). 알림이 켜졌는지는 알림 설정 화면에서만 보여 준다.
export function AccountHomePage() {
  usePageTitle(ACCOUNT_TITLE);
  const { account } = useLoaderData<typeof accountLoader>();
  return (
    <AccountFrame title={ACCOUNT_TITLE}>
      <dl className={styles.summary}>
        <dt>계정</dt>
        <dd>{account.email}</dd>
      </dl>
      <ul className={styles.menu}>
        {MENU.map((item) => (
          <li key={item.to}>
            <Link to={item.to} className={styles.menuLink}>
              <span className={styles.menuText}>
                <strong>{item.label}</strong>
                <span>{item.description}</span>
              </span>
              <ChevronRight size={20} aria-hidden="true" />
            </Link>
          </li>
        ))}
      </ul>
    </AccountFrame>
  );
}
