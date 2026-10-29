import { redirect, type ActionFunctionArgs, type LoaderFunctionArgs } from "react-router";
import { getMe, withdraw } from "@/lib/api/account";
import { AuthRequiredError } from "@/lib/auth/authFetch";
import { memberOnly } from "@/lib/auth/guard";
import { signOut } from "@/lib/auth/session";
import { showToast } from "@/lib/toast";
import { codeOf, fieldErrorsOf, formText, GENERIC_ERROR, type FieldErrors } from "../formErrors";

export async function withdrawalLoader({ request }: LoaderFunctionArgs) {
  return memberOnly(request, async () => ({ account: await getMe(request.signal) }));
}

export type WithdrawalResult = { fieldErrors?: FieldErrors; formError?: string };

export async function withdrawalAction({ request }: ActionFunctionArgs): Promise<WithdrawalResult | Response> {
  const form = await request.formData();
  const password = formText(form, "password");
  if (!password) {
    return { fieldErrors: { password: "비밀번호를 입력해 주세요." } };
  }
  return memberOnly(request, async () => {
    try {
      await withdraw(password);
    } catch (error) {
      // 로그인이 풀렸으면 memberOnly가 로그인 화면으로 보낸다.
      if (error instanceof AuthRequiredError) throw error;
      if (codeOf(error) === "PASSWORD_MISMATCH") {
        return { fieldErrors: { password: "비밀번호가 맞지 않습니다." } };
      }
      const invalid = fieldErrorsOf(error);
      return invalid ? { fieldErrors: invalid } : { formError: GENERIC_ERROR };
    }
    signOut();
    showToast("탈퇴했습니다. 그동안 이용해 주셔서 감사합니다.");
    return redirect("/");
  });
}
