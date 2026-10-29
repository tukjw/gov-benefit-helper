import { redirect } from "react-router";
import { logout } from "@/lib/api/auth";
import { signOut } from "@/lib/auth/session";
import { showToast } from "@/lib/toast";

// 헤더의 로그아웃 버튼이 보낸다. API에 닿지 못해도 이 탭의 토큰은 지운다.
export async function logoutAction() {
  try {
    await logout();
  } catch {
    // 쿠키가 남아 새로고침하면 다시 로그인될 수 있다. 그래도 이 탭에서는 로그아웃한 것으로 둔다.
  }
  signOut();
  showToast("로그아웃되었습니다.");
  return redirect("/");
}

// 주소로 직접 열면 홈으로 보낸다.
export function logoutLoader() {
  return redirect("/");
}
