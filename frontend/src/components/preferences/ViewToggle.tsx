import { LayoutGrid, List } from "lucide-react";
import { VIEW_MODES, writePreference, type ViewMode } from "@/lib/preferences";
import styles from "./ViewToggle.module.css";
import { usePreference } from "./usePreference";

const ICONS: Record<ViewMode, typeof List> = { card: LayoutGrid, list: List };

export function ViewToggle() {
  const current = usePreference("view");
  return (
    <div role="group" aria-label="보기 방식" className={styles.options}>
      {VIEW_MODES.map((option) => {
        const Icon = ICONS[option.value];
        return (
          <button
            key={option.value}
            type="button"
            className={styles.option}
            data-value={option.value}
            aria-pressed={current === option.value}
            onClick={() => writePreference("view", option.value)}
          >
            <Icon size={18} aria-hidden="true" />
            {option.label}
          </button>
        );
      })}
    </div>
  );
}
