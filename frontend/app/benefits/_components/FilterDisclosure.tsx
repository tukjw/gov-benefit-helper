"use client";

import { SlidersHorizontal } from "lucide-react";
import { useId, useState, type ReactNode } from "react";
import styles from "./FilterDisclosure.module.css";

// 900px 미만에서만 필터를 접는다. 넓은 화면에서는 버튼을 숨기고 필터를 항상 보여준다.
export function FilterDisclosure({ active, children }: { active: boolean; children: ReactNode }) {
  const [open, setOpen] = useState(false);
  const panelId = useId();
  const label = open ? "필터 닫기" : active ? "필터 (적용 중)" : "필터";

  return (
    <aside className={styles.sidebar} aria-label="검색 필터">
      <button
        type="button"
        className={styles.toggle}
        aria-expanded={open}
        aria-controls={panelId}
        onClick={() => setOpen((value) => !value)}
      >
        <SlidersHorizontal size={18} aria-hidden="true" />
        {label}
      </button>
      <div id={panelId} className={styles.panel} data-open={open}>
        {children}
      </div>
    </aside>
  );
}
