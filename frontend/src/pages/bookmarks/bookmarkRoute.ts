import { redirect, type ActionFunctionArgs } from "react-router";
import { AuthRequiredError } from "@/lib/auth/authFetch";
import { loginPath, safeReturnTo } from "@/lib/auth/guard";
import { ensureSession, getSession } from "@/lib/auth/session";
import { flushBookmark, markBookmark } from "@/lib/bookmarks/bookmarkStore";
import { showToast } from "@/lib/toast";

export type BookmarkResult = { ok: boolean };

// 관심 저장·해제(#74 설계 6). 실제 요청은 저장 상태 모듈이 혜택마다 차례로 보낸다.
// 로그인이 필요하면 누른 화면으로 돌아오도록 그 화면의 주소(returnTo)로 로그인 화면에 보낸다. 이 action의
// request.url은 화면 주소가 아니어서 memberOnly를 쓰지 않는다.
export async function bookmarkAction({ request, params }: ActionFunctionArgs): Promise<BookmarkResult | Response> {
  const form = await request.formData();
  const serviceId = params.serviceId ?? "";
  const returnTo = safeReturnTo(form.get("returnTo"));
  await ensureSession();
  if (getSession().status !== "member") {
    showToast("로그인하면 관심 혜택을 저장할 수 있어요.");
    return redirect(loginPath(returnTo));
  }
  // 누를 때 로그인 상태를 몰라 화면에 반영하지 못했으면 여기서 반영한다.
  if (form.get("marked") !== "true") {
    markBookmark(serviceId, form.get("saved") === "true");
  }
  try {
    await flushBookmark(serviceId);
  } catch (error) {
    if (error instanceof AuthRequiredError) {
      return redirect(loginPath(returnTo));
    }
    showToast("관심 혜택을 바꾸지 못했어요. 잠시 뒤 다시 시도해 주세요.");
    return { ok: false };
  }
  return { ok: true };
}
