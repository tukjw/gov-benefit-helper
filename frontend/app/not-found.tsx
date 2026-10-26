import Link from "next/link";
import { StatusPage } from "@/components/StatusPage";

export default function NotFound() {
  return (
    <StatusPage
      title="페이지를 찾을 수 없어요"
      description="주소가 잘못되었거나 없어진 페이지입니다."
      actions={
        <>
          <Link href="/" className="button button-primary">
            홈으로
          </Link>
          <Link href="/benefits" className="button">
            전체 혜택 보기
          </Link>
        </>
      }
    />
  );
}
