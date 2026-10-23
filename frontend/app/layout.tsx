import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "정부 혜택 도우미",
  description: "내 상황과 관련 있는 정부 혜택을 찾고 신청 조건을 확인합니다.",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="ko">
      <body>{children}</body>
    </html>
  );
}
