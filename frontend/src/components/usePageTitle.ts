import { useEffect } from "react";

const SITE_NAME = "정부 혜택 도우미";

// 화면 제목은 "<화면> | 정부 혜택 도우미"이고, 이름이 없으면 서비스 이름만 쓴다.
export function usePageTitle(title?: string) {
  useEffect(() => {
    document.title = title ? `${title} | ${SITE_NAME}` : SITE_NAME;
  }, [title]);
}
