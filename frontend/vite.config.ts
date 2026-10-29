import { fileURLToPath } from "node:url";
import react from "@vitejs/plugin-react";
import { defineConfig, loadEnv, type Plugin } from "vite";
import { PREFERENCES_SCRIPT } from "./src/lib/preferences";

// 화면 설정을 첫 페인트 전에 반영하는 스크립트를 <head>에 넣는다(결정 0028). 저장 키는 lib/preferences.ts 한 곳에 둔다.
function preferencesScript(): Plugin {
  return {
    name: "preferences-script",
    transformIndexHtml: () => [{ tag: "script", children: PREFERENCES_SCRIPT, injectTo: "head" }],
  };
}

export default defineConfig(({ command, mode }) => {
  // API 주소는 번들에 고정되므로, 값 없이 만든 빌드가 배포되지 않게 빌드를 멈춘다(결정 0029).
  if (command === "build" && !loadEnv(mode, process.cwd(), "VITE_").VITE_API_BASE_URL) {
    throw new Error("VITE_API_BASE_URL이 설정되지 않았습니다.");
  }

  return {
    plugins: [react(), preferencesScript()],
    resolve: {
      alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) },
    },
    // 로컬 API의 허용 출처(application-local.yaml)와 같은 포트를 쓴다.
    server: { port: 3000, strictPort: true },
    preview: { port: 3000, strictPort: true },
  };
});
