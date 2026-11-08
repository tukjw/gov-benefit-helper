import type { ActionFunctionArgs, LoaderFunctionArgs, ShouldRevalidateFunction } from "react-router";
import { getMe } from "@/lib/api/account";
import { getNotificationSettings, updateNotificationSettings } from "@/lib/api/notification";
import { AuthRequiredError } from "@/lib/auth/authFetch";
import { memberOnly } from "@/lib/auth/guard";
import { showToast } from "@/lib/toast";
import { formText } from "../formErrors";

const SAVE_ERROR = "알림 설정을 저장하지 못했습니다. 잠시 뒤 다시 시도해 주세요.";

// 화면에 보이는 설정은 이 loader가 받은 서버 값뿐이다(#134 설계 3).
export async function notificationLoader({ request }: LoaderFunctionArgs) {
  return memberOnly(request, async () => {
    const [account, setting] = await Promise.all([getMe(request.signal), getNotificationSettings(request.signal)]);
    return { email: account.email, newBenefit: setting.newBenefit };
  });
}

export type NotificationResult = { saved?: true; formError?: string };

// 버튼의 값이 바꿀 값이다. 성공하면 기본 재검증으로 loader가 바뀐 값을 다시 받는다.
export async function notificationAction({ request }: ActionFunctionArgs): Promise<NotificationResult> {
  const value = formText(await request.formData(), "newBenefit");
  if (value !== "true" && value !== "false") {
    return { formError: SAVE_ERROR };
  }
  const newBenefit = value === "true";
  return memberOnly(request, async () => {
    try {
      await updateNotificationSettings({ newBenefit });
    } catch (error) {
      // 로그인이 풀렸으면 memberOnly가 로그인 화면으로 보낸다.
      if (error instanceof AuthRequiredError) throw error;
      return { formError: SAVE_ERROR };
    }
    showToast(newBenefit ? "새 혜택 알림을 켰습니다." : "새 혜택 알림을 껐습니다.");
    return { saved: true };
  });
}

// 저장에 실패하면 loader를 다시 부르지 않고 직전 값과 실패 안내를 둔다. 서버가 내려가 있으면 다시 받는 것도
// 실패해 오류 화면이 실패 안내를 가린다.
export const notificationShouldRevalidate: ShouldRevalidateFunction = ({ actionResult, defaultShouldRevalidate }) =>
  (actionResult as NotificationResult | undefined)?.formError ? false : defaultShouldRevalidate;
