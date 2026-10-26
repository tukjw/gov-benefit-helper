import type { Metadata } from "next";
// 글꼴은 CDN 없이 빌드 결과에 함께 내보낸다. 동적 서브셋이라 화면에 쓰인 글자의 파일만 받는다(결정 0026).
import "pretendard-gov/dist/web/variable/pretendardvariable-gov-dynamic-subset.css";
import "./globals.css";
import { InlineScript } from "@/components/InlineScript";
import { SiteFooter } from "@/components/layout/SiteFooter";
import { SiteHeader } from "@/components/layout/SiteHeader";
import { SkipLink } from "@/components/layout/SkipLink";
import { PREFERENCES_SCRIPT } from "@/lib/preferences";

export const metadata: Metadata = {
  title: {
    default: "정부 혜택 도우미",
    template: "%s | 정부 혜택 도우미",
  },
  description: "내 상황과 관련 있는 정부 혜택을 찾고 신청 조건을 확인합니다.",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    // 인라인 스크립트가 하이드레이션 전에 data-view를 붙이므로 속성 차이를 허용한다.
    <html lang="ko" suppressHydrationWarning>
      <head>
        <InlineScript html={PREFERENCES_SCRIPT} />
      </head>
      <body>
        <SkipLink />
        <SiteHeader />
        <main id="main" tabIndex={-1}>
          {children}
        </main>
        <SiteFooter />
      </body>
    </html>
  );
}
