import { Link, useRevalidator } from "react-router";
import { StatusPage } from "@/components/StatusPage";
import { usePageTitle } from "@/components/usePageTitle";

// API 장애나 연결 실패. 입력이 잘못된 경우(400)는 화면 안에서 안내하므로 여기로 오지 않는다(#72 설계 4).
// 다시 시도하면 지금 주소의 loader를 다시 실행하고, 성공하면 원래 화면으로 돌아간다.
export function ErrorPage() {
  usePageTitle("오류");
  const revalidator = useRevalidator();
  return (
    <StatusPage
      title="정보를 불러오지 못했어요"
      description="잠시 후 다시 시도해 주세요."
      actions={
        <>
          <button
            type="button"
            className="button button-primary"
            disabled={revalidator.state === "loading"}
            onClick={() => revalidator.revalidate()}
          >
            다시 시도
          </button>
          <Link to="/" className="button">
            홈으로
          </Link>
        </>
      }
    />
  );
}
