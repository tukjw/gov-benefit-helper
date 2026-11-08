import type { LoaderFunctionArgs } from "react-router";
import { getMe } from "@/lib/api/account";
import { memberOnly } from "@/lib/auth/guard";

// 다른 탭에서 탈퇴했으면 getMe가 401이라 로그인 화면으로 간다.
export async function accountLoader({ request }: LoaderFunctionArgs) {
  return memberOnly(request, async () => ({ account: await getMe(request.signal) }));
}
