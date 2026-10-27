import { redirect, type ActionFunctionArgs, type LoaderFunctionArgs } from "react-router";
import { login } from "@/lib/api/auth";
import { safeReturnTo } from "@/lib/auth/guard";
import { ensureSession, getSession, signIn } from "@/lib/auth/session";
import { showToast } from "@/lib/toast";
import { codeOf, fieldErrorsOf, formText, GENERIC_ERROR, type FieldErrors } from "../formErrors";

export type LoginResult = { fieldErrors?: FieldErrors; formError?: string };

// 이미 로그인했으면 돌아갈 화면으로 보낸다.
export async function loginLoader({ request }: LoaderFunctionArgs) {
  await ensureSession();
  if (getSession().status === "member") {
    throw redirect(safeReturnTo(new URL(request.url).searchParams.get("returnTo")));
  }
  return null;
}

export async function loginAction({ request }: ActionFunctionArgs): Promise<LoginResult | Response> {
  const form = await request.formData();
  const email = formText(form, "email").trim();
  const password = formText(form, "password");
  const fieldErrors: FieldErrors = {};
  if (!email) fieldErrors.email = "이메일을 입력해 주세요.";
  if (!password) fieldErrors.password = "비밀번호를 입력해 주세요.";
  if (Object.keys(fieldErrors).length > 0) {
    return { fieldErrors };
  }
  try {
    await signIn(await login(email, password));
  } catch (error) {
    if (codeOf(error) === "INVALID_CREDENTIALS") {
      return { formError: "이메일 또는 비밀번호가 올바르지 않습니다." };
    }
    const invalid = fieldErrorsOf(error);
    return invalid ? { fieldErrors: invalid } : { formError: GENERIC_ERROR };
  }
  showToast("로그인되었습니다.");
  return redirect(safeReturnTo(form.get("returnTo")));
}
