import { useId, type ComponentProps, type ReactNode } from "react";
import styles from "./TextField.module.css";

type Props = Omit<ComponentProps<"input">, "id" | "className"> & {
  label: string;
  hint?: ReactNode;
  error?: string;
};

// 라벨·도움말·오류를 입력과 연결한다(시안 '입력과 에러 표시'). 오류는 제출 뒤에 나타나므로 role="alert"다.
export function TextField({ label, hint, error, ...input }: Props) {
  const id = useId();
  const hintId = `${id}-hint`;
  const errorId = `${id}-error`;
  const describedBy = [hint ? hintId : null, error ? errorId : null].filter(Boolean).join(" ");
  return (
    <div className={styles.field}>
      <label htmlFor={id} className={styles.label}>
        {label}
      </label>
      {hint && (
        <p id={hintId} className={styles.hint}>
          {hint}
        </p>
      )}
      <input
        id={id}
        className={styles.input}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy || undefined}
        {...input}
      />
      {error && (
        <p id={errorId} role="alert" className={styles.error}>
          {error}
        </p>
      )}
    </div>
  );
}
