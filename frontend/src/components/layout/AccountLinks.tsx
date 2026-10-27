import { Form, Link, useLocation } from "react-router";
import { loginPath } from "@/lib/auth/guard";
import { useSession } from "@/lib/auth/session";
import styles from "./SiteHeader.module.css";

// 로그인·회원가입 화면에서 다시 누르면 그 화면으로 돌아오지 않게 returnTo를 붙이지 않는다.
const ACCOUNT_PATHS = ["/login", "/signup", "/forgot-password", "/reset-password"];

// 헤더 오른쪽 위 유틸리티 줄의 로그인 상태(#73 설계 3). 화면 폭과 관계없이 같은 자리에 둔다.
// 첫 재발급 결과가 나오기 전에는 비워 둬, 로그인 버튼이 잠깐 보였다가 바뀌지 않게 한다.
export function AccountLinks() {
  const session = useSession();
  const { pathname, search } = useLocation();
  if (session.status === "unknown") {
    return null;
  }
  if (session.status === "guest") {
    const returnTo = ACCOUNT_PATHS.includes(pathname) ? undefined : `${pathname}${search}`;
    const query = returnTo ? `?returnTo=${encodeURIComponent(returnTo)}` : "";
    return (
      <ul className={styles.utility}>
        <li>
          <Link to={loginPath(returnTo)} className={styles.utilityLink}>
            로그인
          </Link>
        </li>
        <li>
          <Link to={`/signup${query}`} className={styles.utilityLink}>
            회원가입
          </Link>
        </li>
      </ul>
    );
  }
  // 계정 화면은 아직 탈퇴뿐이라 이메일에서 바로 연결한다. #74의 개인 화면이 생기면 옮길 수 있다.
  return (
    <ul className={styles.utility}>
      <li className={styles.utilityEmail}>
        <Link to="/account/withdrawal" className={styles.utilityLink} title="계정 관리">
          <strong>{session.email}</strong>
          <span className="visually-hidden"> 계정 관리(회원 탈퇴)</span>
        </Link>
      </li>
      <li>
        <Form method="post" action="/logout">
          <button type="submit" className={styles.utilityLink}>
            로그아웃
          </button>
        </Form>
      </li>
    </ul>
  );
}
