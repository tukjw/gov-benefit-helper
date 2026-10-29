import { isRouteErrorResponse, useRouteError } from "react-router";
import { ErrorPage } from "@/pages/ErrorPage";
import { BenefitNotFound } from "./BenefitNotFound";

// 없는 혜택은 loader가 404 응답을 던진다. 그 밖의 실패는 공통 오류 화면이다.
export function BenefitDetailError() {
  const error = useRouteError();
  return isRouteErrorResponse(error) && error.status === 404 ? <BenefitNotFound /> : <ErrorPage />;
}
