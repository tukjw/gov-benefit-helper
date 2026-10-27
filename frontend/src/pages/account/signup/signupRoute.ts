import { redirect, type ActionFunctionArgs } from "react-router";
import { confirmSignupCode, login, requestSignupCode, signUp } from "@/lib/api/auth";
import { loginPath, safeReturnTo } from "@/lib/auth/guard";
import { checkPassword } from "@/lib/auth/password";
import { signIn } from "@/lib/auth/session";
import { showToast } from "@/lib/toast";
import { codeOf, fieldErrorsOf, formText, GENERIC_ERROR, retryAtOf, type FieldErrors } from "../formErrors";

export type SignupStep = "email" | "code" | "password";

// 가입 화면의 상태 전체. action이 다음 단계의 상태를 돌려주고 화면은 그대로 그린다.
// 가입 티켓은 이 결과와 숨은 입력에만 있고 URL·저장소에 남기지 않는다(#73 설계 7).
export type SignupState = {
  step: SignupStep;
  email: string;
  ticket?: string;
  notice?: string;
  fieldErrors?: FieldErrors;
  formError?: string;
  retryAt?: number;
  retryText?: string;
  duplicate?: boolean;
};

export async function signupAction({ request }: ActionFunctionArgs): Promise<SignupState | Response> {
  const form = await request.formData();
  const email = formText(form, "email").trim();
  switch (formText(form, "intent")) {
    case "send-code":
      return sendCode(email, formText(form, "step") === "code" ? "code" : "email");
    case "edit-email":
      return { step: "email", email };
    case "confirm-code":
      return confirmCode(email, formText(form, "code").trim());
    case "signup":
      return signup(email, form);
    default:
      return { step: "email", email };
  }
}

async function sendCode(email: string, from: "email" | "code"): Promise<SignupState> {
  if (!email) {
    return { step: "email", email, fieldErrors: { email: "이메일을 입력해 주세요." } };
  }
  try {
    await requestSignupCode(email);
  } catch (error) {
    const limited = retryAtOf(error);
    if (limited) {
      return from === "email"
        ? { step: "email", email, ...limited, fieldErrors: { email: limited.retryText } }
        : { step: "code", email, ...limited };
    }
    const invalid = fieldErrorsOf(error);
    if (invalid) {
      return { step: "email", email, fieldErrors: invalid };
    }
    return { step: from, email, formError: GENERIC_ERROR };
  }
  const notice = from === "code" ? "인증번호를 다시 보냈습니다. 이전 번호는 쓸 수 없습니다." : "인증번호를 보냈습니다.";
  return { step: "code", email, notice };
}

const CODE_ERRORS: Record<string, string> = {
  VERIFICATION_CODE_MISMATCH: "인증번호가 맞지 않습니다. 메일의 번호를 다시 확인해 주세요.",
  VERIFICATION_CODE_EXPIRED: "인증번호가 만료되었습니다. 인증번호를 다시 받아 주세요.",
  VERIFICATION_ATTEMPTS_EXCEEDED: "인증번호를 5번 틀렸습니다. 인증번호를 다시 받아 주세요.",
};

async function confirmCode(email: string, code: string): Promise<SignupState> {
  if (!/^\d{6}$/.test(code)) {
    return { step: "code", email, fieldErrors: { code: "메일로 받은 6자리 숫자를 입력해 주세요." } };
  }
  try {
    const { verificationToken } = await confirmSignupCode(email, code);
    return { step: "password", email, ticket: verificationToken };
  } catch (error) {
    const message = CODE_ERRORS[codeOf(error) ?? ""];
    if (message) {
      return { step: "code", email, fieldErrors: { code: message } };
    }
    const invalid = fieldErrorsOf(error);
    return invalid ? { step: "code", email, fieldErrors: invalid } : { step: "code", email, formError: GENERIC_ERROR };
  }
}

async function signup(email: string, form: FormData): Promise<SignupState | Response> {
  const ticket = formText(form, "ticket");
  const password = formText(form, "password");
  const state: SignupState = { step: "password", email, ticket };
  const passwordError = checkPassword(password);
  if (passwordError) {
    return { ...state, fieldErrors: { password: passwordError } };
  }
  if (password !== formText(form, "passwordConfirm")) {
    return { ...state, fieldErrors: { passwordConfirm: "비밀번호가 서로 다릅니다." } };
  }
  try {
    await signUp(email, password, ticket);
  } catch (error) {
    switch (codeOf(error)) {
      case "DUPLICATE_EMAIL":
        return { ...state, duplicate: true };
      case "EMAIL_NOT_VERIFIED":
        return { step: "email", email, formError: "인증한 지 30분이 지났습니다. 인증번호를 다시 받아 주세요." };
    }
    const invalid = fieldErrorsOf(error);
    return invalid ? { ...state, fieldErrors: invalid } : { ...state, formError: GENERIC_ERROR };
  }
  const returnTo = safeReturnTo(form.get("returnTo"));
  try {
    await signIn(await login(email, password));
  } catch {
    showToast("가입되었습니다. 로그인해 주세요.");
    return redirect(loginPath(returnTo));
  }
  showToast("가입되었습니다.");
  return redirect(returnTo);
}
