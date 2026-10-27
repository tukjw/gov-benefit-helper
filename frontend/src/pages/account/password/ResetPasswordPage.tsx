import { useEffect, useState } from "react";
import { Form, Link, useActionData, useNavigation } from "react-router";
import { FormError } from "@/components/form/FormError";
import { TextField } from "@/components/form/TextField";
import { InfoBox } from "@/components/ui/InfoBox";
import { usePageTitle } from "@/components/usePageTitle";
import { PASSWORD_RULE_TEXT } from "@/lib/auth/password";
import { AccountFrame } from "../AccountFrame";
import styles from "../AccountPage.module.css";
import type { ResetPasswordResult } from "./passwordRoutes";

// 메일의 링크는 /reset-password#token=<토큰>이다(결정 0019). 해시는 서버로 가지 않는다.
function readToken(): string {
  return new URLSearchParams(window.location.hash.slice(1)).get("token") ?? "";
}

export function ResetPasswordPage() {
  usePageTitle("새 비밀번호");
  const [token] = useState(readToken);
  const result = useActionData() as ResetPasswordResult | undefined;
  const submitting = useNavigation().state === "submitting";

  // 읽은 토큰을 주소창과 방문 기록에서 지운다. 라우터 상태는 그대로 둔다.
  useEffect(() => {
    if (window.location.hash) {
      window.history.replaceState(window.history.state, "", window.location.pathname + window.location.search);
    }
  }, []);

  if (!token || result?.tokenInvalid) {
    return (
      <AccountFrame title="새 비밀번호">
        <InfoBox tone="warning" title="링크를 쓸 수 없습니다">
          링크가 만료됐거나 이미 쓴 링크입니다. 링크는 30분 동안 한 번만 쓸 수 있고, 새로 받으면 이전 링크는 쓸 수 없습니다.
        </InfoBox>
        <Link to="/forgot-password" className={`button button-primary ${styles.submit}`}>
          재설정 링크 다시 받기
        </Link>
      </AccountFrame>
    );
  }

  return (
    <AccountFrame title="새 비밀번호" description="새로 쓸 비밀번호를 입력해 주세요. 바꾸면 모든 기기에서 로그아웃됩니다.">
      <Form method="post" noValidate className={styles.form}>
        {result?.formError && <FormError>{result.formError}</FormError>}
        <input type="hidden" name="token" value={token} />
        <TextField
          label="새 비밀번호"
          name="newPassword"
          type="password"
          autoComplete="new-password"
          hint={PASSWORD_RULE_TEXT}
          error={result?.fieldErrors?.newPassword}
        />
        <TextField
          label="새 비밀번호 확인"
          name="newPasswordConfirm"
          type="password"
          autoComplete="new-password"
          error={result?.fieldErrors?.newPasswordConfirm}
        />
        <button type="submit" className={`button button-primary ${styles.submit}`} disabled={submitting}>
          비밀번호 바꾸기
        </button>
      </Form>
    </AccountFrame>
  );
}
