import { Link } from "react-router";
import { StatusPage } from "@/components/StatusPage";
import { usePageTitle } from "@/components/usePageTitle";

export function BenefitNotFound() {
  usePageTitle("혜택을 찾을 수 없음");
  return (
    <StatusPage
      title="혜택을 찾을 수 없어요"
      description="주소가 잘못되었거나 더 이상 제공되지 않는 혜택입니다."
      actions={
        <Link to="/benefits" className="button button-primary">
          전체 혜택 보기
        </Link>
      }
    />
  );
}
