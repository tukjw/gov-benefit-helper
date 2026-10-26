"use client";

import { usePathname } from "next/navigation";
import { type ComponentProps } from "react";
import { NavLinks } from "./NavLinks";

// usePathname은 동적 경로에서 프리렌더링 중에 멈추므로, 쓰는 곳에서 <Suspense>로 감싸고
// fallback으로 현재 메뉴 표시가 없는 NavLinks를 둔다.
export function ActiveNavLinks(props: Omit<ComponentProps<typeof NavLinks>, "pathname">) {
  const pathname = usePathname();
  return <NavLinks {...props} pathname={pathname} />;
}
