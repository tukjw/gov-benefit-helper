import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  cacheComponents: true,
  partialPrefetching: true,
  // 저장소 지침은 루트 AGENTS.md 하나로 둔다. 켜 두면 AI 에이전트가 실행한 next dev가 frontend/AGENTS.md를 만든다.
  agentRules: false,
};

export default nextConfig;
