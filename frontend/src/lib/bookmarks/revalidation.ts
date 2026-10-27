import type { ShouldRevalidateFunction } from "react-router";

export const BOOKMARK_ACTION_PREFIX = "/bookmarks/";

// 관심 저장 토글은 화면의 목록·상세를 바꾸지 않으므로 loader를 다시 부르지 않는다(#74 설계 6).
// 다시 부르면 검색 결과를 다시 받고, 관심 목록에서 해제한 카드가 바로 사라진다(설계 8).
export const skipBookmarkRevalidation: ShouldRevalidateFunction = ({ formAction, defaultShouldRevalidate }) =>
  formAction?.startsWith(BOOKMARK_ACTION_PREFIX) ? false : defaultShouldRevalidate;
