import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { RouterProvider } from "react-router";
// 글꼴은 CDN 없이 빌드 결과에 함께 내보낸다. 동적 서브셋이라 화면에 쓰인 글자의 파일만 받는다(결정 0028).
import "pretendard-gov/dist/web/variable/pretendardvariable-gov-dynamic-subset.css";
import "./globals.css";
import { router } from "./router";

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <RouterProvider router={router} />
  </StrictMode>,
);
