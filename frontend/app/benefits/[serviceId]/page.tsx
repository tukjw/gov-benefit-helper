import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { Suspense } from "react";
import { getBenefitDetail } from "@/lib/api/benefits";
import { BenefitDetailView } from "./_components/BenefitDetailView";
import styles from "./page.module.css";

export async function generateMetadata({ params }: PageProps<"/benefits/[serviceId]">): Promise<Metadata> {
  const benefit = await getBenefitDetail((await params).serviceId);
  return { title: benefit?.name ?? "혜택을 찾을 수 없음" };
}

// params를 읽는 부분만 <Suspense> 안에서 요청마다 렌더링한다(#72 설계 2).
// 스트리밍이 시작된 뒤 notFound()를 부르므로 찾을 수 없음 화면의 상태 코드는 200이다(플랜 '확인된 사실과 제약').
export default function BenefitDetailPage({ params }: PageProps<"/benefits/[serviceId]">) {
  return (
    <div className="container">
      <Suspense fallback={<p className={styles.loading}>혜택 정보를 불러오는 중입니다.</p>}>
        <BenefitDetail params={params} />
      </Suspense>
    </div>
  );
}

async function BenefitDetail({ params }: { params: PageProps<"/benefits/[serviceId]">["params"] }) {
  const benefit = await getBenefitDetail((await params).serviceId);
  if (!benefit) {
    notFound();
  }
  return <BenefitDetailView benefit={benefit} />;
}
