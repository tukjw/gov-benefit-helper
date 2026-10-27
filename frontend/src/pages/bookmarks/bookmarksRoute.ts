import type { LoaderFunctionArgs } from "react-router";
import { listBookmarks } from "@/lib/api/bookmarks";
import type { BookmarkListResponse } from "@/lib/api/types";
import { memberOnly } from "@/lib/auth/guard";
import { ensureSession, getSession } from "@/lib/auth/session";
import { seedBookmarks } from "@/lib/bookmarks/bookmarkStore";

export type BookmarksData = { kind: "guest" } | { kind: "member"; page: number; response: BookmarkListResponse };

// API의 page는 int라 그보다 큰 값은 400이 된다.
const MAX_PAGE = 2_147_483_647;

// 비회원이면 로그인 화면으로 보내지 않고 목록 자리에 로그인 안내를 그린다(#74 설계 4).
export async function bookmarksLoader({ request }: LoaderFunctionArgs): Promise<BookmarksData> {
  await ensureSession();
  if (getSession().status !== "member") {
    return { kind: "guest" };
  }
  const raw = new URL(request.url).searchParams.get("page") ?? "";
  const number = /^\d+$/.test(raw) ? Number(raw) : 1;
  const page = number >= 1 && number <= MAX_PAGE ? number : 1;
  return memberOnly(request, async () => {
    const response = await listBookmarks(page, request.signal);
    const ids = response.items.map((item) => item.benefit.serviceId);
    seedBookmarks(ids, ids);
    return { kind: "member", page, response };
  });
}

export function bookmarksHref(page: number): string {
  return page > 1 ? `/bookmarks?page=${page}` : "/bookmarks";
}
