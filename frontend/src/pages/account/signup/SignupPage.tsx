import { Form, Link, useActionData, useNavigation, useSearchParams } from "react-router";
import { FormError } from "@/components/form/FormError";
import { TextField } from "@/components/form/TextField";
import { usePageTitle } from "@/components/usePageTitle";
import { PASSWORD_RULE_TEXT } from "@/lib/auth/password";
import { AccountFrame } from "../AccountFrame";
import styles from "../AccountPage.module.css";
import type { SignupState, SignupStep } from "./signupRoute";
import { useWaiting } from "./useWaiting";

const STEPS: { step: SignupStep; label: string }[] = [
  { step: "email", label: "이메일" },
  { step: "code", label: "인증번호 확인" },
  { step: "password", label: "비밀번호" },
];

const INITIAL: SignupState = { step: "email", email: "" };

// 이메일 → 인증번호 확인 → 비밀번호(확인 후 가입, 결정 0019). 새로고침하면 처음부터 다시 한다.
export function SignupPage() {
  usePageTitle("회원가입");
  const state = (useActionData() as SignupState | undefined) ?? INITIAL;
  const submitting = useNavigation().state === "submitting";
  const waiting = useWaiting(state.retryAt);
  const [searchParams] = useSearchParams();
  const returnTo = searchParams.get("returnTo") ?? "";
  const loginPath = returnTo ? `/login?returnTo=${encodeURIComponent(returnTo)}` : "/login";

  return (
    <AccountFrame title="회원가입" description="가입할 이메일로 받은 인증번호를 확인한 뒤 비밀번호를 정합니다.">
      <Steps current={state.step} />
      {state.formError && <FormError>{state.formError}</FormError>}

      {state.step === "email" && (
        <Form key="email" method="post" noValidate className={styles.form}>
          <input type="hidden" name="step" value="email" />
          <TextField
            label="이메일"
            name="email"
            type="email"
            autoComplete="email"
            defaultValue={state.email}
            error={state.fieldErrors?.email}
          />
          <button
            type="submit"
            name="intent"
            value="send-code"
            className={`button button-primary ${styles.submit}`}
            disabled={submitting}
          >
            인증번호 받기
          </button>
        </Form>
      )}

      {state.step === "code" && (
        <>
          <EmailSummary email={state.email} />
          {state.notice && (
            <p role="status" className={styles.note}>
              {state.notice} 인증번호는 10분 동안 쓸 수 있습니다.
            </p>
          )}
          <Form key="code" method="post" noValidate className={styles.form}>
            <input type="hidden" name="email" value={state.email} />
            <TextField
              label="인증번호"
              name="code"
              inputMode="numeric"
              autoComplete="one-time-code"
              maxLength={6}
              hint="메일로 받은 6자리 숫자"
              error={state.fieldErrors?.code}
            />
            <button
              type="submit"
              name="intent"
              value="confirm-code"
              className={`button button-primary ${styles.submit}`}
              disabled={submitting}
            >
              확인
            </button>
          </Form>
          <div className={styles.inline}>
            <Form method="post">
              <input type="hidden" name="email" value={state.email} />
              <input type="hidden" name="step" value="code" />
              <button
                type="submit"
                name="intent"
                value="send-code"
                className={styles.textButton}
                disabled={submitting || waiting}
              >
                인증번호 다시 받기
              </button>
            </Form>
            <Form method="post">
              <input type="hidden" name="email" value={state.email} />
              <button type="submit" name="intent" value="edit-email" className={styles.textButton}>
                이메일 바꾸기
              </button>
            </Form>
          </div>
          {state.retryText && waiting && (
            <p role="alert" className={styles.note}>
              {state.retryText}
            </p>
          )}
        </>
      )}

      {state.step === "password" && (
        <>
          <EmailSummary email={state.email} />
          {state.duplicate && (
            <FormError>
              이미 가입된 이메일입니다. <Link to={loginPath}>로그인</Link>하거나{" "}
              <Link to="/forgot-password">비밀번호를 재설정</Link>해 주세요.
            </FormError>
          )}
          <Form key="password" method="post" noValidate className={styles.form}>
            <input type="hidden" name="email" value={state.email} />
            <input type="hidden" name="ticket" value={state.ticket ?? ""} />
            <input type="hidden" name="returnTo" value={returnTo} />
            {/* 비밀번호 관리자가 이 이메일의 비밀번호로 저장하게 한다. */}
            <input type="email" name="username" value={state.email} autoComplete="username" readOnly hidden />
            <TextField
              label="비밀번호"
              name="password"
              type="password"
              autoComplete="new-password"
              hint={PASSWORD_RULE_TEXT}
              error={state.fieldErrors?.password}
            />
            <TextField
              label="비밀번호 확인"
              name="passwordConfirm"
              type="password"
              autoComplete="new-password"
              error={state.fieldErrors?.passwordConfirm}
            />
            <button
              type="submit"
              name="intent"
              value="signup"
              className={`button button-primary ${styles.submit}`}
              disabled={submitting}
            >
              가입하기
            </button>
          </Form>
        </>
      )}

      <p className={styles.links}>
        <span>
          이미 가입했나요? <Link to={loginPath}>로그인</Link>
        </span>
      </p>
    </AccountFrame>
  );
}

function Steps({ current }: { current: SignupStep }) {
  const currentIndex = STEPS.findIndex((item) => item.step === current);
  return (
    <ol className={styles.steps}>
      {STEPS.map((item, index) => (
        <li
          key={item.step}
          className={`${styles.step} ${index < currentIndex ? styles.stepDone : ""}`}
          aria-current={index === currentIndex ? "step" : undefined}
        >
          <span>{index + 1}단계</span>
          {item.label}
        </li>
      ))}
    </ol>
  );
}

function EmailSummary({ email }: { email: string }) {
  return (
    <dl className={styles.summary}>
      <dt>이메일</dt>
      <dd>{email}</dd>
    </dl>
  );
}
