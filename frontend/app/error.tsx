"use client";

import Link from "next/link";
import { StatusPage } from "@/components/StatusPage";

// API 장애나 연결 실패. 입력이 잘못된 경우(400)는 화면 안에서 안내하므로 여기로 오지 않는다(#72 설계 4).
export default function ErrorPage({ retry }: { retry: () => void }) {
  return (
    <StatusPage
      title="정보를 불러오지 못했어요"
      description="잠시 후 다시 시도해 주세요."
      actions={
        <>
          <button type="button" className="button button-primary" onClick={() => retry()}>
            다시 시도
          </button>
          <Link href="/" className="button">
            홈으로
          </Link>
        </>
      }
    />
  );
}
