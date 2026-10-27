import { redirect, type ActionFunctionArgs } from "react-router";
import { requestPasswordReset, resetPassword } from "@/lib/api/auth";
import { checkPassword } from "@/lib/auth/password";
import { signOut } from "@/lib/auth/session";
import { showToast } from "@/lib/toast";
import { codeOf, fieldErrorsOf, formText, GENERIC_ERROR, type FieldErrors } from "../formErrors";

export type ForgotPasswordResult = { sentTo?: string; fieldErrors?: FieldErrors; formError?: string };

// 가입 여부와 발송 제한을 드러내지 않도록 API는 늘 204이고, 화면도 같은 안내를 보인다(결정 0019).
export async function forgotPasswordAction({ request }: ActionFunctionArgs): Promise<ForgotPasswordResult> {
  const form = await request.formData();
  const email = formText(form, "email").trim();
  if (!email) {
    return { fieldErrors: { email: "이메일을 입력해 주세요." } };
  }
  try {
    await requestPasswordReset(email);
  } catch (error) {
    const invalid = fieldErrorsOf(error);
    return invalid ? { fieldErrors: invalid } : { formError: GENERIC_ERROR };
  }
  return { sentTo: email };
}

export type ResetPasswordResult = { tokenInvalid?: boolean; fieldErrors?: FieldErrors; formError?: string };

// 재설정하면 그 계정의 리프레시 토큰이 모두 폐기되므로 이 탭도 로그아웃 상태로 두고 로그인 화면으로 보낸다.
export async function resetPasswordAction({ request }: ActionFunctionArgs): Promise<ResetPasswordResult | Response> {
  const form = await request.formData();
  const token = formText(form, "token");
  const newPassword = formText(form, "newPassword");
  const passwordError = checkPassword(newPassword);
  if (passwordError) {
    return { fieldErrors: { newPassword: passwordError } };
  }
  if (newPassword !== formText(form, "newPasswordConfirm")) {
    return { fieldErrors: { newPasswordConfirm: "비밀번호가 서로 다릅니다." } };
  }
  try {
    await resetPassword(token, newPassword);
  } catch (error) {
    if (codeOf(error) === "INVALID_RESET_TOKEN") {
      return { tokenInvalid: true };
    }
    const invalid = fieldErrorsOf(error);
    if (invalid?.token) {
      return { tokenInvalid: true };
    }
    return invalid ? { fieldErrors: invalid } : { formError: GENERIC_ERROR };
  }
  signOut();
  showToast("비밀번호를 바꿨습니다. 새 비밀번호로 로그인해 주세요.");
  return redirect("/login");
}
