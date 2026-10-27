import { createBrowserRouter } from "react-router";
import { RootLayout } from "@/components/layout/RootLayout";
import { AccountFallback } from "@/pages/account/AccountFrame";
import { LoginPage } from "@/pages/account/login/LoginPage";
import { loginAction, loginLoader } from "@/pages/account/login/loginRoute";
import { logoutAction, logoutLoader } from "@/pages/account/logoutRoute";
import { ForgotPasswordPage } from "@/pages/account/password/ForgotPasswordPage";
import { forgotPasswordAction, resetPasswordAction } from "@/pages/account/password/passwordRoutes";
import { ResetPasswordPage } from "@/pages/account/password/ResetPasswordPage";
import { SignupPage } from "@/pages/account/signup/SignupPage";
import { signupAction } from "@/pages/account/signup/signupRoute";
import { WithdrawalPage } from "@/pages/account/withdrawal/WithdrawalPage";
import { skipBookmarkRevalidation } from "@/lib/bookmarks/revalidation";
import { bookmarkAction } from "@/pages/bookmarks/bookmarkRoute";
import { BookmarksFallback, BookmarksPage } from "@/pages/bookmarks/BookmarksPage";
import { bookmarksLoader } from "@/pages/bookmarks/bookmarksRoute";
import { withdrawalAction, withdrawalLoader } from "@/pages/account/withdrawal/withdrawalRoute";
import { ErrorPage } from "@/pages/ErrorPage";
import { HomePage } from "@/pages/HomePage";
import { NotFoundPage } from "@/pages/NotFoundPage";
import { BenefitsFallback, BenefitsPage } from "@/pages/benefits/BenefitsPage";
import { benefitsLoader } from "@/pages/benefits/benefitsLoader";
import { BenefitDetailError } from "@/pages/benefits/detail/BenefitDetailError";
import { BenefitDetailFallback, BenefitDetailPage } from "@/pages/benefits/detail/BenefitDetailPage";
import { benefitDetailLoader } from "@/pages/benefits/detail/benefitDetailLoader";
import { MatchFallback, MatchPage } from "@/pages/match/MatchPage";
import { matchAction, matchLoader } from "@/pages/match/matchRoute";
import { ProfileFallback, ProfilePage } from "@/pages/profile/ProfilePage";
import { profileAction, profileLoader } from "@/pages/profile/profileRoute";

// 화면 데이터는 경로의 loader가 받고, 폼 제출은 action이 처리한다(결정 0029). 오류 화면도 헤더·푸터 안에
// 그리도록 errorElement를 레이아웃 아래의 경로 없는 자식에 둔다.
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
            shouldRevalidate: skipBookmarkRevalidation,
            element: <BenefitsPage />,
            hydrateFallbackElement: <BenefitsFallback />,
          },
          {
            path: "/benefits/:serviceId",
            loader: benefitDetailLoader,
            shouldRevalidate: skipBookmarkRevalidation,
            element: <BenefitDetailPage />,
            errorElement: <BenefitDetailError />,
            hydrateFallbackElement: <BenefitDetailFallback />,
          },
          {
            path: "/match",
            loader: matchLoader,
            action: matchAction,
            shouldRevalidate: skipBookmarkRevalidation,
            element: <MatchPage />,
            hydrateFallbackElement: <MatchFallback />,
          },
          {
            path: "/profile",
            loader: profileLoader,
            action: profileAction,
            element: <ProfilePage />,
            hydrateFallbackElement: <ProfileFallback />,
          },
          {
            path: "/bookmarks",
            loader: bookmarksLoader,
            shouldRevalidate: skipBookmarkRevalidation,
            element: <BookmarksPage />,
            hydrateFallbackElement: <BookmarksFallback />,
          },
          // 관심 저장·해제(action만). 별 버튼의 fetcher가 보낸다.
          { path: "/bookmarks/:serviceId", action: bookmarkAction },
          {
            path: "/login",
            loader: loginLoader,
            action: loginAction,
            element: <LoginPage />,
            hydrateFallbackElement: <AccountFallback title="로그인" />,
          },
          { path: "/signup", action: signupAction, element: <SignupPage /> },
          { path: "/forgot-password", action: forgotPasswordAction, element: <ForgotPasswordPage /> },
          { path: "/reset-password", action: resetPasswordAction, element: <ResetPasswordPage /> },
          {
            path: "/account/withdrawal",
            loader: withdrawalLoader,
            action: withdrawalAction,
            element: <WithdrawalPage />,
            hydrateFallbackElement: <AccountFallback title="회원 탈퇴" />,
          },
          { path: "/logout", loader: logoutLoader, action: logoutAction },
          { path: "*", element: <NotFoundPage /> },
        ],
      },
    ],
  },
]);
