import { Form, Link, useActionData, useNavigation, useSearchParams } from "react-router";
import { FormError } from "@/components/form/FormError";
import { TextField } from "@/components/form/TextField";
import { usePageTitle } from "@/components/usePageTitle";
import { AccountFrame } from "../AccountFrame";
import styles from "../AccountPage.module.css";
import type { LoginResult } from "./loginRoute";

export function LoginPage() {
  usePageTitle("로그인");
  const result = useActionData() as LoginResult | undefined;
  const submitting = useNavigation().state === "submitting";
  const [searchParams] = useSearchParams();
  const returnTo = searchParams.get("returnTo") ?? "";
  const signupPath = returnTo ? `/signup?returnTo=${encodeURIComponent(returnTo)}` : "/signup";
  return (
    <AccountFrame title="로그인" description="가입한 이메일과 비밀번호를 입력해 주세요.">
      <Form method="post" noValidate className={styles.form}>
        {result?.formError && <FormError>{result.formError}</FormError>}
        <input type="hidden" name="returnTo" value={returnTo} />
        <TextField
          label="이메일"
          name="email"
          type="email"
          autoComplete="email"
          error={result?.fieldErrors?.email}
        />
        <TextField
          label="비밀번호"
          name="password"
          type="password"
          autoComplete="current-password"
          error={result?.fieldErrors?.password}
        />
        <button type="submit" className={`button button-primary ${styles.submit}`} disabled={submitting}>
          로그인
        </button>
      </Form>
      <p className={styles.links}>
        <Link to="/forgot-password">비밀번호 재설정</Link>
        <Link to={signupPath}>회원가입</Link>
      </p>
    </AccountFrame>
  );
}
