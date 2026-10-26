import type { ReactNode } from "react";
import styles from "./SearchBox.module.css";

// GET 폼이라 JS 없이도 검색된다(#72 설계 5). 키워드 길이는 API 한도(100자)에 맞춘다.
export function SearchBox({
  id,
  defaultValue = "",
  size = "default",
  children,
}: {
  id: string;
  defaultValue?: string;
  size?: "hero" | "default";
  // 검색할 때 함께 보낼 hidden input(현재 필터)
  children?: ReactNode;
}) {
  return (
    <form action="/benefits" method="get" role="search" className={`${styles.form} ${styles[size]}`}>
      <label htmlFor={id} className="visually-hidden">
        혜택 이름 검색
      </label>
      <input
        id={id}
        type="search"
        name="keyword"
        defaultValue={defaultValue}
        maxLength={100}
        placeholder="예: 청년 월세, 출산, 장학금"
        className={styles.input}
      />
      {children}
      <button type="submit" className={styles.button}>
        검색
      </button>
    </form>
  );
}
