import { Form, useActionData, useLoaderData, useNavigation } from "react-router";
import { FormError } from "@/components/form/FormError";
import { TextField } from "@/components/form/TextField";
import { InfoBox } from "@/components/ui/InfoBox";
import { usePageTitle } from "@/components/usePageTitle";
import { AccountFrame } from "../AccountFrame";
import styles from "../AccountPage.module.css";
import type { WithdrawalResult, withdrawalLoader } from "./withdrawalRoute";

// 되돌릴 수 없으므로 비밀번호를 다시 받는다(DEC-AUTH). 지우는 데이터는 REQ-ACCOUNT-01 기준이다.
export function WithdrawalPage() {
  usePageTitle("회원 탈퇴");
  const { account } = useLoaderData<typeof withdrawalLoader>();
  const result = useActionData() as WithdrawalResult | undefined;
  const submitting = useNavigation().state === "submitting";
  return (
    <AccountFrame title="회원 탈퇴">
      <InfoBox tone="danger" title="탈퇴하면 되돌릴 수 없습니다">
        <ul className={styles.list}>
          <li>계정과 프로필, 관심 혜택, 로그인 정보가 바로 지워집니다.</li>
          <li>같은 이메일로 다시 가입할 수 있지만, 지운 정보는 돌아오지 않습니다.</li>
        </ul>
      </InfoBox>
      <dl className={styles.summary}>
        <dt>탈퇴할 계정</dt>
        <dd>{account.email}</dd>
      </dl>
      <Form method="post" noValidate className={styles.form}>
        {result?.formError && <FormError>{result.formError}</FormError>}
        <input type="email" name="username" value={account.email} autoComplete="username" readOnly hidden />
        <TextField
          label="비밀번호 확인"
          name="password"
          type="password"
          autoComplete="current-password"
          hint="본인 확인을 위해 지금 쓰는 비밀번호를 입력해 주세요."
          error={result?.fieldErrors?.password}
        />
        <button type="submit" className={`button ${styles.submit} ${styles.danger}`} disabled={submitting}>
          탈퇴하기
        </button>
      </Form>
    </AccountFrame>
  );
}
