import { Star } from "lucide-react";
import { useFetcher, useLocation } from "react-router";
import { useSession } from "@/lib/auth/session";
import { markBookmark, useBookmark } from "@/lib/bookmarks/bookmarkStore";
import { BOOKMARK_ACTION_PREFIX } from "@/lib/bookmarks/revalidation";
import styles from "./BookmarkButton.module.css";

// 관심 저장 별 버튼(시안 '관심 저장 토글'). 회원이면 누른 즉시 화면을 바꾸고, 비회원이면 action이 로그인 화면으로 보낸다.
// 회원의 저장 여부를 아직 받지 못했으면 누를 수 없다.
export function BookmarkButton({
  serviceId,
  name,
  size = "md",
}: {
  serviceId: string;
  name: string;
  size?: "md" | "lg";
}) {
  const fetcher = useFetcher();
  const { pathname, search } = useLocation();
  const saved = useBookmark(serviceId);
  const member = useSession().status === "member";
  const known = saved !== undefined;
  const pressed = member && saved === true;

  function toggle() {
    const next = !pressed;
    if (member) markBookmark(serviceId, next);
    void fetcher.submit(
      { saved: String(next), marked: String(member), returnTo: `${pathname}${search}` },
      { method: "post", action: `${BOOKMARK_ACTION_PREFIX}${encodeURIComponent(serviceId)}` },
    );
  }

  return (
    <button
      type="button"
      className={`${styles.button} ${size === "lg" ? styles.lg : ""}`}
      aria-pressed={member ? pressed : undefined}
      aria-label={pressed ? `${name} 관심 혜택에서 빼기` : `${name} 관심 혜택에 저장`}
      title={pressed ? "관심 혜택에서 빼기" : "관심 혜택에 저장"}
      disabled={member && !known}
      onClick={toggle}
    >
      <Star size={size === "lg" ? 22 : 20} aria-hidden="true" fill={pressed ? "currentColor" : "none"} />
      {size === "lg" && <span>{pressed ? "관심 혜택에 저장됨" : "관심 혜택 저장"}</span>}
    </button>
  );
}
