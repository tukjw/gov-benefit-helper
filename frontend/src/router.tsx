import { createBrowserRouter } from "react-router";
import { RootLayout } from "@/components/layout/RootLayout";
import { ErrorPage } from "@/pages/ErrorPage";
import { HomePage } from "@/pages/HomePage";
import { NotFoundPage } from "@/pages/NotFoundPage";
import { BenefitsFallback, BenefitsPage } from "@/pages/benefits/BenefitsPage";
import { benefitsLoader } from "@/pages/benefits/benefitsLoader";
import { BenefitDetailError } from "@/pages/benefits/detail/BenefitDetailError";
import { BenefitDetailFallback, BenefitDetailPage } from "@/pages/benefits/detail/BenefitDetailPage";
import { benefitDetailLoader } from "@/pages/benefits/detail/benefitDetailLoader";

// 화면 데이터는 경로의 loader가 받는다(결정 0027). 오류 화면도 헤더·푸터 안에 그리도록
// errorElement를 레이아웃 아래의 경로 없는 자식에 둔다.
export const router = createBrowserRouter([
  {
    element: <RootLayout />,
    children: [
      {
        errorElement: <ErrorPage />,
        children: [
          { path: "/", element: <HomePage /> },
          {
            path: "/benefits",
            loader: benefitsLoader,
            element: <BenefitsPage />,
            hydrateFallbackElement: <BenefitsFallback />,
          },
          {
            path: "/benefits/:serviceId",
            loader: benefitDetailLoader,
            element: <BenefitDetailPage />,
            errorElement: <BenefitDetailError />,
            hydrateFallbackElement: <BenefitDetailFallback />,
          },
          { path: "*", element: <NotFoundPage /> },
        ],
      },
    ],
  },
]);
