import styles from "./SiteFooter.module.css";

const DATA_SOURCE_URL = "https://www.data.go.kr/data/15113968/openapi.do";
const CONTACT_EMAIL = "contact@govbenefit.kr";

// 주소·콜센터 자리에 데이터 출처와 운영자 연락처를 둔다. 받지 않은 품질인증 링크는 두지 않는다.
export function SiteFooter() {
  return (
    <footer className={styles.footer}>
      <div className="container">
        <p className={styles.name}>정부 혜택 도우미</p>
        <p className={styles.disclaimer}>
          정부 공식 누리집이 아닌 민간 서비스입니다. 신청 자격과 절차는 각 기관의 공식 안내에서 확인하세요.
        </p>
        <dl className={styles.info}>
          <div>
            <dt>데이터 출처</dt>
            <dd>
              <a href={DATA_SOURCE_URL} className={styles.link} target="_blank" rel="noopener noreferrer">
                공공데이터포털 · 행정안전부 공공서비스(혜택) 정보
                <span className="visually-hidden">(새 창)</span>
              </a>
            </dd>
          </div>
          <div>
            <dt>문의</dt>
            <dd>
              <a href={`mailto:${CONTACT_EMAIL}`} className={styles.link}>
                {CONTACT_EMAIL}
              </a>
            </dd>
          </div>
        </dl>
      </div>
    </footer>
  );
}
