import { Form, Link, useActionData, useNavigation } from "react-router";
import { FormError } from "@/components/form/FormError";
import { TextField } from "@/components/form/TextField";
import { InfoBox } from "@/components/ui/InfoBox";
import { usePageTitle } from "@/components/usePageTitle";
import { AccountFrame } from "../AccountFrame";
import styles from "../AccountPage.module.css";
import type { ForgotPasswordResult } from "./passwordRoutes";

export function ForgotPasswordPage() {
  usePageTitle("비밀번호 재설정");
  const result = useActionData() as ForgotPasswordResult | undefined;
  const submitting = useNavigation().state === "submitting";
  return (
    <AccountFrame title="비밀번호 재설정" description="가입한 이메일로 비밀번호를 다시 정할 수 있는 링크를 보내 드립니다.">
      {result?.sentTo && (
        <div role="status">
          <InfoBox tone="success" title="메일을 확인해 주세요">
            {result.sentTo}(으)로 가입한 계정이 있으면 비밀번호 재설정 링크를 보냈습니다. 링크는 30분 동안 쓸 수 있습니다.
          </InfoBox>
        </div>
      )}
      <Form method="post" noValidate className={styles.form}>
        {result?.formError && <FormError>{result.formError}</FormError>}
        <TextField
          label="이메일"
          name="email"
          type="email"
          autoComplete="email"
          defaultValue={result?.sentTo}
          error={result?.fieldErrors?.email}
        />
        <button type="submit" className={`button button-primary ${styles.submit}`} disabled={submitting}>
          {result?.sentTo ? "링크 다시 받기" : "재설정 링크 받기"}
        </button>
      </Form>
      <p className={styles.note}>
        메일이 오지 않으면 스팸함을 확인해 주세요. 링크는 1분에 한 번, 하루 10번까지 다시 받을 수 있고, 새로 받으면 이전
        링크는 쓸 수 없습니다.
      </p>
      <p className={styles.links}>
        <Link to="/login">로그인</Link>
      </p>
    </AccountFrame>
  );
}
