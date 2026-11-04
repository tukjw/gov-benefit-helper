import type { AttributeOption } from "@/lib/api/types";
import styles from "../ProfilePage.module.css";

// 건강 관련 생애 속성의 별도 동의(#110, DEC-PROFILE). 장애인·질병/질환자·임산부·예비부모/난임을 하나라도 고르면 보인다.
// 안내는 처리 목적, 항목, 보유 기간, 거부할 권리와 불이익이다. 서버는 이 속성을 보낼 때마다 동의를 요구한다.
export function SensitiveConsent({
  member,
  selected,
  checked,
  onChange,
  describedBy,
}: {
  member: boolean;
  selected: AttributeOption[];
  checked: boolean;
  onChange: (checked: boolean) => void;
  describedBy?: string;
}) {
  return (
    <div className={styles.consent}>
      <p className={styles.consentTitle}>건강 관련 정보 처리 안내</p>
      <dl className={styles.consentTerms}>
        <dt>처리 목적</dt>
        <dd>혜택의 대상 조건과 비교해요.{member && " 새 혜택 알림 대상을 고를 때도 써요."}</dd>
        <dt>항목</dt>
        <dd>{selected.map((option) => option.label).join(", ")}</dd>
        <dt>보유 기간</dt>
        <dd>{member ? "탈퇴하거나 이 항목을 모두 빼고 저장할 때까지 보관해요." : "서버에 저장하지 않아요."}</dd>
        <dt>동의 거부</dt>
        <dd>
          동의하지 않아도 돼요. 그러면 이 항목을 고를 수 없고, 이 조건이 있는 혜택은 비교 결과에서 확인 필요로 보여요.
          {member && " 새 혜택 알림에서 빠질 수 있어요."}
        </dd>
      </dl>
      <label className={styles.consentCheck}>
        <input
          type="checkbox"
          name="sensitiveAttributeConsent"
          value="true"
          checked={checked}
          onChange={(event) => onChange(event.target.checked)}
          aria-invalid={describedBy ? true : undefined}
          aria-describedby={describedBy}
        />
        <span>건강 관련 정보 처리에 동의해요(선택)</span>
      </label>
    </div>
  );
}
