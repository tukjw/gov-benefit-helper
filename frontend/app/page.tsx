import Link from "next/link";
import { SearchBox } from "@/components/SearchBox";
import { FIELDS, RECOMMENDED_KEYWORDS } from "@/lib/benefits/options";
import styles from "./page.module.css";

// API를 부르지 않아 정적 화면으로 빌드된다. 조건 비교 카드는 #74에서 붙인다.
export default function Home() {
  return (
    <>
      <section className={styles.hero} aria-labelledby="home-title">
        <div className={`container ${styles.heroInner}`}>
          <h1 id="home-title" className={styles.title}>
            필요한 정부 혜택,
            <br />
            쉽게 찾고 신청처를 확인하세요
          </h1>
          <p className={styles.lead}>중앙부처·지자체·공공기관 혜택을 한곳에서 찾아봅니다.</p>
          <SearchBox id="home-search" size="hero" />
          <div className={styles.keywords}>
            <h2 className={styles.keywordsLabel}>추천 검색어</h2>
            <ul className={styles.chips}>
              {RECOMMENDED_KEYWORDS.map((keyword) => (
                <li key={keyword}>
                  <Link href={`/benefits?${new URLSearchParams({ keyword })}`} className={styles.chip}>
                    {keyword}
                  </Link>
                </li>
              ))}
            </ul>
          </div>
        </div>
      </section>

      <section className="container" aria-labelledby="fields-title">
        <h2 id="fields-title" className={styles.sectionTitle}>
          분야별 혜택 바로가기
        </h2>
        <ul className={styles.fields}>
          {FIELDS.map(({ value, icon: Icon }) => (
            <li key={value}>
              <Link href={`/benefits?${new URLSearchParams({ field: value })}`} className={styles.field}>
                <span className={styles.fieldIcon}>
                  <Icon size={28} aria-hidden="true" />
                </span>
                {value}
              </Link>
            </li>
          ))}
        </ul>
      </section>
    </>
  );
}
