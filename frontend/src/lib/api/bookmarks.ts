import { authFetch } from "@/lib/auth/authFetch";
import type { BookmarkListResponse, BookmarkSavedResponse } from "./types";

// 관심 혜택(REQ-SAVE-01). API 이름은 북마크다. 저장·해제는 멱등이라 같은 요청을 다시 보내도 결과가 같다.

export const BOOKMARK_PAGE_SIZE = 20;

export function listBookmarks(page: number, signal?: AbortSignal): Promise<BookmarkListResponse> {
  const query = new URLSearchParams({ page: String(page), size: String(BOOKMARK_PAGE_SIZE) });
  return authFetch("GET", "/api/me/bookmarks", { query, signal });
}

// 한 번에 100개까지 받는다. 화면 한 쪽의 항목 수가 그보다 적다.
export async function savedServiceIds(serviceIds: string[], signal?: AbortSignal): Promise<string[]> {
  const query = new URLSearchParams();
  serviceIds.forEach((id) => query.append("serviceId", id));
  const response = await authFetch<BookmarkSavedResponse>("GET", "/api/me/bookmarks/saved", { query, signal });
  return response.serviceIds;
}

export function saveBookmark(serviceId: string): Promise<void> {
  return authFetch("PUT", `/api/me/bookmarks/${encodeURIComponent(serviceId)}`);
}

export function removeBookmark(serviceId: string): Promise<void> {
  return authFetch("DELETE", `/api/me/bookmarks/${encodeURIComponent(serviceId)}`);
}
