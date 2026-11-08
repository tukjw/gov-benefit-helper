import { Form, Link, useActionData, useLoaderData, useNavigation } from "react-router";
import { FormError } from "@/components/form/FormError";
import { InfoBox } from "@/components/ui/InfoBox";
import { usePageTitle } from "@/components/usePageTitle";
import { AccountFrame } from "../AccountFrame";
import styles from "../AccountPage.module.css";
import type { NotificationResult, notificationLoader } from "./notificationRoute";

// 새 혜택 알림 켜기·끄기(REQ-NOTIFY-03, DEC-NOTIFY). 지금 저장된 상태를 글자로 보여 주고, 반대로 바꾸는
// 버튼 하나를 둔다. 저장 전에 화면 값을 먼저 바꾸지 않는다(#134 설계 2).
export function NotificationSettingsPage() {
  usePageTitle("알림 설정");
  const { email, newBenefit } = useLoaderData<typeof notificationLoader>();
  const result = useActionData() as NotificationResult | undefined;
  const navigation = useNavigation();
  // 저장 요청과 그 뒤 다시 받는 동안 막아 연타로 요청이 겹치지 않게 한다.
  const saving = navigation.state !== "idle" && navigation.formMethod !== undefined;
  return (
    <AccountFrame title="알림 설정" underAccount>
      <section className={styles.setting} aria-labelledby="new-benefit-setting">
        <div className={styles.settingRow}>
          <div className={styles.settingLabel}>
            <strong id="new-benefit-setting">새 혜택 알림</strong>
            <span>받는 주소: {email}</span>
          </div>
          <p className={`${styles.settingState} ${newBenefit ? styles.settingOn : ""}`}>
            {newBenefit ? "켜짐" : "꺼짐"}
          </p>
        </div>
        <Form method="post" className={styles.form}>
          {result?.formError && <FormError>{result.formError}</FormError>}
          <button
            type="submit"
            name="newBenefit"
            value={String(!newBenefit)}
            className={`button ${newBenefit ? "" : "button-primary"} ${styles.submit}`}
            disabled={saving}
          >
            {saving ? "저장하는 중" : newBenefit ? "알림 끄기" : "알림 켜기"}
          </button>
        </Form>
      </section>
      <InfoBox title="새 혜택 알림은 이렇게 보냅니다">
        <ul className={styles.infoList}>
          <li>
            새로 등록된 혜택 중 조건 비교 결과가 &lsquo;해당&rsquo;인 혜택을 모아 매일 오전 8시(한국 시간)에 메일 한
            통으로 보냅니다.
          </li>
          <li>해당하는 새 혜택이 없는 날은 보내지 않습니다.</li>
          <li>
            프로필에서 비워 둔 항목은 &lsquo;확인 필요&rsquo;가 되어, 그 항목이 조건인 혜택은 알림에서 빠집니다.{" "}
            <Link to="/profile" className="text-link">
              프로필
            </Link>
            에서 입력한 값을 확인해 주세요.
          </li>
        </ul>
      </InfoBox>
    </AccountFrame>
  );
}
