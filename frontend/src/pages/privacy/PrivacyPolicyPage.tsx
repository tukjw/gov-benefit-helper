import type { ReactNode } from "react";
import { Link } from "react-router";
import { Breadcrumb } from "@/components/layout/Breadcrumb";
import { usePageTitle } from "@/components/usePageTitle";
import { NOTIFICATION_SETTINGS_PATH, WITHDRAWAL_PATH } from "@/lib/account/paths";
import styles from "./PrivacyPolicyPage.module.css";

// 개인정보 처리방침(#111, 개인정보 보호법 제30조, 결정 0044). 로그인 없이 본다.
// 본문의 기간·항목은 실제 동작·운영 설정과 같아야 한다. 처리 항목·보유 기간·위탁이 바뀌는 변경은 같은 PR에서 이 본문을 고친다.
// 수치마다 근거 설정을 주석으로 적어, 설정을 바꿀 때 검색으로 찾게 한다.

// 운영 배포일. 바꾸면 '처리방침의 변경'에 이전 시행일과 바뀐 내용을 남긴다(결정 0044).
const EFFECTIVE_DATE = "2026년 11월 8일";
const PREVIOUS_EFFECTIVE_DATE = "2026년 11월 5일";
const CONTACT_EMAIL = "contact@govbenefit.kr";

const SECTIONS = [
  { id: "purpose", title: "처리 목적" },
  { id: "items", title: "처리하는 개인정보 항목과 근거" },
  { id: "retention", title: "처리·보유 기간" },
  { id: "destruction", title: "파기 절차와 방법" },
  { id: "provision", title: "제3자 제공" },
  { id: "entrustment", title: "처리 위탁" },
  { id: "transfer", title: "국외 이전" },
  { id: "rights", title: "정보주체와 법정대리인의 권리와 행사 방법" },
  { id: "cookies", title: "자동 수집 장치의 설치·운영과 거부" },
  { id: "safety", title: "안전성 확보 조치" },
  { id: "officer", title: "개인정보 보호책임자" },
  { id: "remedy", title: "권익침해 구제 방법" },
  { id: "changes", title: "처리방침의 변경" },
] as const;

type SectionId = (typeof SECTIONS)[number]["id"];

export function PrivacyPolicyPage() {
  usePageTitle("개인정보 처리방침");
  return (
    <div className="container">
      <Breadcrumb items={[{ label: "개인정보 처리방침" }]} />
      <article className={styles.page}>
        <header className={styles.heading}>
          <h1 className={styles.title}>개인정보 처리방침</h1>
          <p className={styles.lead}>
            정부 혜택 도우미(이하 &lsquo;서비스&rsquo;)는 개인이 운영하는 민간 서비스입니다. 서비스는 「개인정보 보호법」
            제30조에 따라 이용자의 개인정보를 어떻게 처리하고 보호하는지 알리기 위해 이 처리방침을 둡니다.
          </p>
          <p className={styles.meta}>시행일: {EFFECTIVE_DATE}</p>
        </header>

        <nav aria-labelledby="privacy-toc" className={styles.toc}>
          <h2 id="privacy-toc" className={styles.tocTitle}>
            목차
          </h2>
          <ol>
            {SECTIONS.map((section, index) => (
              <li key={section.id}>
                <a href={`#${section.id}`}>
                  {index + 1}. {section.title}
                </a>
              </li>
            ))}
          </ol>
        </nav>

        <Section id="purpose">
          <p>서비스는 다음 목적으로만 개인정보를 처리하며, 목적이 바뀌면 미리 알립니다.</p>
          <ul>
            <li>
              회원 가입과 관리: 가입할 이메일의 소유 확인, 회원 식별, 로그인 유지, 비밀번호 재설정, 부정 로그인과
              과도한 요청 방지, 탈퇴 처리
            </li>
            <li>
              혜택 정보 제공: 입력한 프로필과 혜택의 대상 조건 비교, 프로필·관심 혜택 저장, 새 혜택 알림 설정 보관과
              알림 메일 발송
            </li>
            <li>비회원 조건 비교: 로그인하지 않은 이용자가 입력한 조건으로 비교 결과 계산</li>
          </ul>
        </Section>

        <Section id="items">
          <Table caption="동의 없이 처리하는 개인정보" headers={["구분", "항목", "처리 근거"]}>
            <tr>
              <th scope="row">회원 가입</th>
              <td>이메일, 비밀번호(되돌릴 수 없는 값으로 바꿔 저장), 가입 일시</td>
              <td rowSpan={5}>계약의 체결·이행(「개인정보 보호법」 제15조 제1항 제4호)</td>
            </tr>
            <tr>
              <th scope="row">이메일 인증</th>
              <td>이메일, 인증번호와 가입 확인 값(되돌릴 수 없는 값으로 바꿔 저장), 틀린 횟수, 발송·확인 일시</td>
            </tr>
            <tr>
              <th scope="row">로그인 유지·비밀번호 재설정</th>
              <td>로그인 유지 값과 재설정 링크 값(되돌릴 수 없는 값으로 바꿔 저장), 발급·만료·사용 일시</td>
            </tr>
            <tr>
              <th scope="row">프로필(선택 입력)</th>
              <td>
                생년월일, 성별, 거주 시도·시군구, 생애·직업 속성, 가구 속성(아래 건강 관련 정보 제외)
              </td>
            </tr>
            <tr>
              <th scope="row">관심 혜택·알림</th>
              {/* 알림 기록: new_benefit_digest(발송 상태·시도 횟수·접수 시각)와 new_benefit_target(묶음의 혜택).
                  보내지 않고 끝난 묶음도 탈퇴할 때까지 남는다(결정 0048) */}
              <td>저장한 혜택, 새 혜택 알림 켜기·끄기, 알림 기록(알림에 담은 새 혜택 목록과 발송 결과)</td>
            </tr>
            <tr>
              <th scope="row">로그인 실패 기록</th>
              <td>입력한 이메일, 접속 IP 주소, 실패 일시</td>
              <td rowSpan={2}>
                서비스의 정당한 이익(같은 법 제15조 제1항 제6호): 비밀번호 대입과 과도한 요청을 막아 회원 계정과 서비스를
                보호
              </td>
            </tr>
            <tr>
              <th scope="row">서비스 접속</th>
              <td>접속 IP 주소, 브라우저 정보, 접속 일시, 접속 오류 보고(Cloudflare를 거치며 처리됩니다)</td>
            </tr>
          </Table>

          <h3>별도 동의를 받아 처리하는 민감정보</h3>
          <p>
            프로필의 장애인, 질병/질환자, 임산부, 예비부모/난임은 건강에 관한 민감정보라 고를 때 별도 동의를 받습니다
            (같은 법 제23조 제1항 제1호). 회원은 처음 동의한 일시도 기록합니다.
          </p>
          <ul>
            <li>처리 목적: 혜택의 대상 조건과 비교합니다. 회원은 새 혜택 알림 대상을 고를 때도 씁니다.</li>
            <li>
              동의 거부: 동의하지 않아도 됩니다. 그러면 이 항목을 고를 수 없고, 이 조건이 있는 혜택은 비교 결과에서
              &lsquo;확인 필요&rsquo;로 보입니다. 회원은 새 혜택 알림에서 빠질 수 있습니다.
            </li>
            <li>민감정보는 다른 이용자에게 공개되지 않습니다.</li>
          </ul>

          <h3>비회원이 입력한 조건</h3>
          <p>
            로그인하지 않고 조건 비교를 쓰면 입력한 프로필 값을 요청에 담아 비교 결과만 계산하고, 서버에는 저장하지
            않습니다. 입력한 값은 이용자의 브라우저 탭에만 남고 탭을 닫으면 사라집니다.
          </p>

          <h3>만 14세 미만 아동</h3>
          <p>
            서비스는 만 14세 미만 아동의 개인정보를 처리하지 않습니다. 가입할 때 만 14세 이상인지 확인하며, 확인하지
            않으면 인증번호를 보내지 않고 가입되지 않습니다.
          </p>
        </Section>

        <Section id="retention">
          <Table caption="항목별 보유 기간" headers={["항목", "보유 기간"]}>
            <tr>
              <th scope="row">계정(이메일·비밀번호), 프로필, 관심 혜택, 알림 설정, 알림 기록</th>
              <td>탈퇴할 때까지. 탈퇴하면 즉시 삭제합니다.</td>
            </tr>
            <tr>
              <th scope="row">로그인 유지·비밀번호 재설정 기록</th>
              <td>탈퇴할 때까지(만료되거나 사용한 기록 포함). 탈퇴하면 즉시 삭제합니다.</td>
            </tr>
            <tr>
              <th scope="row">건강 관련 정보와 동의 일시</th>
              <td>이 항목을 모두 빼고 저장(동의 철회)하거나 탈퇴할 때까지</td>
            </tr>
            <tr>
              <th scope="row">이메일 인증 기록</th>
              {/* auth.mail.window(24h), auth.email-verification.purge-cron(매일 04:30) */}
              <td>
                발송 후 24시간. 24시간이 지난 기록은 매일 새벽 4시 30분에 파기하며, 가입·미가입·탈퇴와 관계없이
                같습니다.
              </td>
            </tr>
            <tr>
              <th scope="row">로그인 실패 기록</th>
              {/* auth.login-attempt.window(15m), LoginAttemptLimiter는 앱 메모리에만 둔다 */}
              <td>15분. 서버 메모리에만 두며, 서버를 다시 시작하면 사라집니다.</td>
            </tr>
            <tr>
              <th scope="row">비회원이 입력한 조건</th>
              <td>서버에 저장하지 않습니다.</td>
            </tr>
            <tr>
              <th scope="row">메일이 반송되거나 수신 거부를 신고한 이메일 주소</th>
              {/* SES 계정 수준 억제 목록: 지울 때까지 남는다(SES 개발자 안내서 Using the account-level suppression list,
                  Deleting personal data). 2026-11-05 확인. 삭제 절차는 docs/ops/mail-domain.md */}
              <td>
                같은 주소로 다시 보내지 않도록 메일 발송 업체(Amazon SES)의 수신 거부 목록에 남습니다. 운영자가 지울
                때까지 보관하며, 삭제를 요구하면 지체 없이 지웁니다.
              </td>
            </tr>
          </Table>
          <p>
            {/* SES는 발송 기록의 보관 기간을 공개하지 않는다(SES 개발자 안내서 Data protection, Deleting personal data).
                2026-11-05 확인 */}
            메일 발송 업체(Amazon SES)는 그 밖의 발송 기록을 위탁 목적을 이루는 데 필요한 기간 동안 AWS의 보관 기준에
            따라 보관합니다. Cloudflare가 보관하는 기간은 <a href="#transfer">국외 이전</a>에 적었습니다. 법령에 따라
            따로 보존하는 개인정보는 없습니다.
          </p>
        </Section>

        <Section id="destruction">
          <ul>
            <li>보유 기간이 지나거나 탈퇴하면 지체 없이 파기합니다.</li>
            <li>
              전자 파일은 데이터베이스에서 삭제해 서비스에서 다시 볼 수 없게 합니다. 데이터베이스의 변경 기록(바이너리
              로그)에는 삭제한 값이 최대 30일 남았다가 자동으로 지워집니다.
            </li>
            <li>종이 문서로는 처리하지 않으며, 데이터베이스 백업을 따로 두지 않습니다.</li>
          </ul>
        </Section>

        <Section id="provision">
          <p>서비스는 이용자의 개인정보를 제3자에게 제공하지 않습니다.</p>
        </Section>

        <Section id="entrustment">
          <Table caption="처리 위탁" headers={["수탁자", "위탁 업무"]}>
            <tr>
              {/* AWS Customer Agreement(2026-10-29 개정): 계정 국가가 대한민국이면 계약 당사자는 Amazon Web Services Korea
                  LLC. 발송 리전은 서울(ap-northeast-2). 2026-11-05 확인 */}
              <th scope="row">Amazon Web Services Korea LLC(Amazon SES)</th>
              <td>가입 인증번호·비밀번호 재설정·새 혜택 알림 메일 발송, 반송·수신 거부 주소 관리</td>
            </tr>
            <tr>
              <th scope="row">Cloudflare, Inc.</th>
              <td>웹 화면 제공, 서비스 서버로의 연결 전달, 과도한 요청 차단</td>
            </tr>
          </Table>
          <p>수탁자가 바뀌면 이 처리방침을 고쳐 알립니다.</p>
        </Section>

        <Section id="transfer">
          <p>
            위탁 업무를 위해 개인정보가 국외로 이전됩니다. 이용자와의 계약을 이행하는 데 필요한 위탁으로, 아래 사항을 이
            처리방침에 공개하고 이전합니다(「개인정보 보호법」 제28조의8 제1항 제3호 가목).
          </p>
          <Table caption="국외 이전" headers={["구분", "Cloudflare"]}>
            <tr>
              <th scope="row">이전받는 자와 연락처</th>
              <td>
                Cloudflare, Inc.
                <br />
                privacyquestions@cloudflare.com
              </td>
            </tr>
            <tr>
              <th scope="row">이전되는 국가</th>
              <td>미국 등(주로 미국·유럽경제지역)</td>
            </tr>
            <tr>
              <th scope="row">이전되는 항목</th>
              <td>
                접속 IP 주소, 브라우저 정보, 서비스를 쓰며 주고받는 내용(이메일·비밀번호·프로필 등), 접속 오류 보고
              </td>
            </tr>
            <tr>
              <th scope="row">이전 시기와 방법</th>
              <td>서비스에 접속할 때마다 암호화된 통신(HTTPS)으로 전송</td>
            </tr>
            <tr>
              <th scope="row">이용 목적</th>
              <td>웹 화면 제공, 서비스 서버로의 연결 전달, 과도한 요청 차단</td>
            </tr>
            <tr>
              <th scope="row">보유·이용 기간</th>
              <td>위탁 목적을 이루는 데 필요한 기간(Cloudflare의 보관 기준)</td>
            </tr>
          </Table>
          <p>
            이전을 거부하려면 가입하지 않거나 탈퇴하면 됩니다. 서비스는 위 업체를 거쳐서만 제공되므로, 이전을 거부하면
            가입과 회원 기능을 이용할 수 없습니다.
          </p>
        </Section>

        <Section id="rights">
          <ul>
            <li>이용자는 언제든지 개인정보의 열람, 정정, 삭제, 처리정지, 동의 철회를 요구할 수 있습니다.</li>
            <li>
              회원은 직접 할 수 있습니다. <Link to="/profile">프로필</Link>에서 값을 확인·수정·삭제하고, 건강 관련
              정보를 모두 빼고 저장하면 동의가 철회됩니다. 관심 혜택은 해제하고, 새 혜택 알림은{" "}
              <Link to={NOTIFICATION_SETTINGS_PATH}>알림 설정</Link>에서 끌 수 있습니다.{" "}
              <Link to={WITHDRAWAL_PATH}>회원 탈퇴</Link>를 하면 개인정보를 즉시 삭제합니다.
            </li>
            <li>
              그 밖의 요구는 <a href={`mailto:${CONTACT_EMAIL}`}>{CONTACT_EMAIL}</a>로 보내 주세요. 가입한 이메일에서 보낸
              요청인지 확인한 뒤 10일 안에 처리 결과를 알립니다.
            </li>
            <li>법정대리인이나 위임을 받은 사람을 통해서도 요구할 수 있습니다. 이때는 위임장을 함께 보내 주세요.</li>
          </ul>
        </Section>

        <Section id="cookies">
          <Table caption="쿠키와 브라우저 저장소" headers={["이름", "목적", "보관 기간"]}>
            <tr>
              <th scope="row">refresh_token 쿠키</th>
              {/* RefreshTokenCookies, auth.refresh-token.ttl(14d) */}
              <td>로그인 유지. 서비스 서버 주소로만 보내지며 스크립트가 읽을 수 없습니다.</td>
              <td>로그인 후 14일(쓸 때마다 다시 14일). 로그아웃·탈퇴하면 삭제</td>
            </tr>
            <tr>
              <th scope="row">보기 방식(브라우저 저장소)</th>
              <td>혜택 목록의 카드형·목록형 설정. 개인정보가 아닙니다.</td>
              <td>이용자가 지울 때까지</td>
            </tr>
            <tr>
              <th scope="row">비회원 조건(브라우저 저장소)</th>
              <td>로그인하지 않고 입력한 프로필 값. 조건 비교를 요청할 때만 서버로 보냅니다.</td>
              <td>브라우저 탭을 닫을 때까지</td>
            </tr>
          </Table>
          <ul>
            <li>방문 기록 분석, 맞춤형 광고 같은 행태정보는 수집하지 않습니다.</li>
            <li>
              브라우저 설정에서 쿠키와 저장소를 막거나 지울 수 있습니다. 쿠키를 막으면 로그인이 유지되지 않아 회원
              기능을 이용할 수 없습니다.
            </li>
          </ul>
        </Section>

        <Section id="safety">
          <ul>
            <li>
              비밀번호는 되돌릴 수 없는 방식(BCrypt)으로 바꿔 저장하고, 인증번호와 로그인 유지 값도 원문 대신 바꾼 값만
              저장합니다.
            </li>
            <li>
              모든 통신은 암호화(HTTPS)합니다. 로그인 유지 값은 스크립트가 읽을 수 없는 쿠키에 두고, 짧게(15분) 쓰는
              로그인 값은 브라우저 저장소에 남기지 않습니다.
            </li>
            <li>로그인 실패 횟수와 메일 발송 횟수를 제한해 비밀번호 대입과 악용을 막습니다.</li>
            <li>
              데이터베이스는 외부에서 접속할 수 없게 두고, 운영자 1명만 서버에 접근합니다. 서버는 운영자가 관리하는
              장소에 둡니다.
            </li>
          </ul>
        </Section>

        <Section id="officer">
          <dl className={styles.contact}>
            <dt>개인정보 보호책임자</dt>
            <dd>운영자</dd>
            <dt>연락처</dt>
            <dd>
              <a href={`mailto:${CONTACT_EMAIL}`}>{CONTACT_EMAIL}</a>
            </dd>
          </dl>
          <p>개인정보에 관한 문의, 불만, 피해 구제 요청도 같은 연락처로 받습니다.</p>
        </Section>

        <Section id="remedy">
          <p>개인정보 침해에 대한 신고나 상담이 필요하면 아래 기관에 문의할 수 있습니다.</p>
          <ul>
            <li>
              개인정보분쟁조정위원회: 국번 없이 1833-6972, <ExternalLink href="https://www.kopico.go.kr">www.kopico.go.kr</ExternalLink>
            </li>
            <li>
              개인정보침해신고센터: 국번 없이 118,{" "}
              <ExternalLink href="https://privacy.kisa.or.kr">privacy.kisa.or.kr</ExternalLink>
            </li>
            <li>
              대검찰청: 국번 없이 1301, <ExternalLink href="https://www.spo.go.kr">www.spo.go.kr</ExternalLink>
            </li>
            <li>
              경찰청: 국번 없이 182, <ExternalLink href="https://ecrm.police.go.kr">ecrm.police.go.kr</ExternalLink>
            </li>
          </ul>
        </Section>

        <Section id="changes">
          <ul>
            <li>이 처리방침은 {EFFECTIVE_DATE}부터 적용합니다.</li>
            <li>내용이 바뀌면 바뀐 내용과 시행일을 이 화면에 알리고, 이전 내용을 함께 남깁니다.</li>
            <li>
              이전 처리방침({PREVIOUS_EFFECTIVE_DATE} 시행)에서 바뀐 내용
              <ul>
                <li>처리 목적: 혜택 정보 제공에 새 혜택 알림 메일 발송을 더했습니다.</li>
                <li>
                  처리 항목: 관심 혜택·알림에 알림 기록(알림에 담은 새 혜택 목록과 발송 결과)을 더했습니다.
                </li>
                <li>보유 기간: 알림 기록을 탈퇴할 때까지 보관합니다.</li>
                <li>처리 위탁: Amazon SES의 위탁 업무에 새 혜택 알림 메일 발송을 더했습니다.</li>
              </ul>
            </li>
            <li>
              2026년 11월 5일 시행 처리방침이 그 전 처리방침(2026년 11월 4일 시행)에서 바뀐 내용
              <ul>
                <li>
                  처리 위탁: 메일 발송 수탁자를 Plus Five Five, Inc.(Resend)에서 Amazon Web Services Korea LLC(Amazon
                  SES)로 바꾸고, 위탁 업무에 반송·수신 거부 주소 관리를 더했습니다.
                </li>
                <li>
                  국외 이전: 메일 발송을 위한 국외 이전(Plus Five Five, Inc., 일본·미국, 받는 이메일 주소와 메일 내용)이
                  없어졌습니다. 메일은 대한민국(서울) 지역에서 처리합니다.
                </li>
                <li>
                  보유 기간: 메일 발송 업체의 보관 기간이 &lsquo;발송 기록 30일, 위탁 계약이 끝나면 90일 안에
                  삭제&rsquo;에서 &lsquo;반송·수신 거부 주소는 운영자가 지울 때까지, 그 밖의 발송 기록은 AWS의 보관
                  기준&rsquo;으로 바뀌었습니다.
                </li>
                <li>
                  처리방침의 변경: &lsquo;시행 전에 이 화면에 알린다&rsquo;를 &lsquo;바뀐 내용과 시행일을 이 화면에
                  알린다&rsquo;로 바꿨습니다.
                </li>
              </ul>
            </li>
          </ul>
        </Section>
      </article>
    </div>
  );
}

function Section({ id, children }: { id: SectionId; children: ReactNode }) {
  const index = SECTIONS.findIndex((section) => section.id === id);
  return (
    <section id={id} aria-labelledby={`${id}-title`} className={styles.section}>
      <h2 id={`${id}-title`} className={styles.sectionTitle}>
        {index + 1}. {SECTIONS[index].title}
      </h2>
      {children}
    </section>
  );
}

function Table({ caption, headers, children }: { caption: string; headers: string[]; children: ReactNode }) {
  return (
    <div className={styles.tableWrap}>
      <table className={styles.table}>
        <caption>{caption}</caption>
        <thead>
          <tr>
            {headers.map((header) => (
              <th key={header} scope="col">
                {header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>{children}</tbody>
      </table>
    </div>
  );
}

function ExternalLink({ href, children }: { href: string; children: ReactNode }) {
  return (
    <a href={href} target="_blank" rel="noopener noreferrer">
      {children}
      <span className="visually-hidden">(새 창)</span>
    </a>
  );
}
