import { Link } from "react-router";
import { AccountLinks } from "./AccountLinks";
import { MobileMenu } from "./MobileMenu";
import { NavLinks } from "./NavLinks";
import styles from "./SiteHeader.module.css";

export function SiteHeader() {
  return (
    <header className={styles.header}>
      <div className="container">
        <AccountLinks />
        <div className={styles.main}>
          {/* 정부 상징 대신 서비스 이름 글자 로고를 쓴다(docs/design/README.md). */}
          <Link to="/" className={styles.logo}>
            정부 혜택 도우미
          </Link>
          <nav aria-label="주 메뉴" className={styles.desktopNav}>
            <NavLinks className={styles.navList} linkClassName={styles.navLink} />
          </nav>
          <MobileMenu />
        </div>
      </div>
    </header>
  );
}
