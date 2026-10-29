import styles from "./SkipLink.module.css";

export function SkipLink() {
  return (
    <a href="#main" className={styles.skip}>
      본문 바로가기
    </a>
  );
}
