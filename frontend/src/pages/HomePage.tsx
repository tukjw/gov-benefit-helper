import { ListChecks } from "lucide-react";
import { Link } from "react-router";
import { SearchBox } from "@/components/SearchBox";
import { usePageTitle } from "@/components/usePageTitle";
import { FIELDS, RECOMMENDED_KEYWORDS } from "@/lib/benefits/options";
import styles from "./HomePage.module.css";

// API를 부르지 않는다.
export function HomePage() {
  usePageTitle();
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
                  <Link to={`/benefits?${new URLSearchParams({ keyword })}`} className={styles.chip}>
                    {keyword}
                  </Link>
                </li>
              ))}
            </ul>
          </div>
        </div>
      </section>

      <section className="container" aria-labelledby="match-card-title">
        <Link to="/match" className={styles.matchCard}>
          <span className={styles.fieldIcon}>
            <ListChecks size={28} aria-hidden="true" />
          </span>
          <span className={styles.matchText}>
            <strong id="match-card-title" className={styles.matchTitle}>
              로그인 없이 내 조건과 비교해 보기
            </strong>
            <span className={styles.matchDescription}>
              생년월일·거주지·가구 상황을 입력하면 공개된 대상 조건과 비교한 결과를 보여드려요.
            </span>
          </span>
        </Link>
      </section>

      <section className="container" aria-labelledby="fields-title">
        <h2 id="fields-title" className={styles.sectionTitle}>
          분야별 혜택 바로가기
        </h2>
        <ul className={styles.fields}>
          {FIELDS.map(({ value, icon: Icon }) => (
            <li key={value}>
              <Link to={`/benefits?${new URLSearchParams({ field: value })}`} className={styles.field}>
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
