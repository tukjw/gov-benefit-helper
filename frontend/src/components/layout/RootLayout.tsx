import { Outlet, ScrollRestoration, useNavigation } from "react-router";
import { SiteFooter } from "./SiteFooter";
import { SiteHeader } from "./SiteHeader";
import { SkipLink } from "./SkipLink";

// 다른 화면으로 이동하는 동안에는 다음 화면의 데이터를 받을 때까지 이전 화면을 두고 본문을 불러오는 중으로 표시한다.
export function RootLayout() {
  const loading = useNavigation().state === "loading";
  return (
    <>
      <SkipLink />
      <SiteHeader />
      <main id="main" tabIndex={-1} aria-busy={loading || undefined}>
        <Outlet />
      </main>
      <SiteFooter />
      <ScrollRestoration />
    </>
  );
}
