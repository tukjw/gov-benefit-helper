import { Menu, X } from "lucide-react";
import { useId, useRef, useState, type KeyboardEvent } from "react";
import { NavLinks } from "./NavLinks";
import styles from "./SiteHeader.module.css";

// 900px 미만에서만 보인다. 넓은 화면에서는 CSS로 숨긴다.
export function MobileMenu() {
  const [open, setOpen] = useState(false);
  const panelId = useId();
  const buttonRef = useRef<HTMLButtonElement>(null);

  function close() {
    setOpen(false);
  }

  function handleKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key === "Escape" && open) {
      close();
      buttonRef.current?.focus();
    }
  }

  return (
    <div className={styles.mobile} onKeyDown={handleKeyDown}>
      <button
        ref={buttonRef}
        type="button"
        className={styles.menuButton}
        aria-expanded={open}
        aria-controls={panelId}
        onClick={() => setOpen((value) => !value)}
      >
        {open ? <X size={20} aria-hidden="true" /> : <Menu size={20} aria-hidden="true" />}
        {open ? "닫기" : "메뉴"}
      </button>
      <div id={panelId} className={styles.mobilePanel} hidden={!open}>
        <nav aria-label="주 메뉴">
          <NavLinks className={styles.mobileNav} linkClassName={styles.mobileLink} onNavigate={close} />
        </nav>
      </div>
    </div>
  );
}
