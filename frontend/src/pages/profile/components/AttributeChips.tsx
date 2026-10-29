import { useState } from "react";
import type { AttributeOption } from "@/lib/api/types";
import { attributeLabel, NONE } from "@/lib/profile/profileForm";
import styles from "../ProfilePage.module.css";

// 여러 개 고르는 칩(시안 '칩 버튼'). '해당 없음'은 다른 칩과 함께 고를 수 없다(결정 0022).
// 아무것도 고르지 않은 상태는 미입력이고, '해당 없음'과 다르다는 것을 칩 아래에 보여준다.
export function AttributeChips({
  name,
  options,
  defaultValue,
  describedBy,
}: {
  name: string;
  options: AttributeOption[];
  defaultValue: string[] | null;
  describedBy?: string;
}) {
  const [selected, setSelected] = useState<string[]>(defaultValue ?? []);

  function toggle(value: string, checked: boolean) {
    if (!checked) {
      setSelected(selected.filter((item) => item !== value));
    } else if (value === NONE) {
      setSelected([NONE]);
    } else {
      setSelected([...selected.filter((item) => item !== NONE), value]);
    }
  }

  return (
    <>
      <ul className={styles.chips} aria-describedby={describedBy}>
        {options.map((option) => (
          <li key={option.value}>
            <label className={`${styles.chip} ${option.value === NONE ? styles.noneChip : ""}`}>
              <input
                type="checkbox"
                name={name}
                value={option.value}
                checked={selected.includes(option.value)}
                onChange={(event) => toggle(option.value, event.target.checked)}
              />
              <span>{attributeLabel(option)}</span>
            </label>
          </li>
        ))}
      </ul>
      <p className={styles.state} aria-live="polite">
        {selected.length === 0
          ? "선택하지 않음(입력 안 함). 이 항목은 비교 결과에서 확인 필요로 보여요."
          : selected.includes(NONE)
            ? "해당 없음을 골랐어요."
            : `${selected.length}개 골랐어요.`}
      </p>
    </>
  );
}
