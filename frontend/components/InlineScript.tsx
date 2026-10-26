// 서버가 보낸 HTML을 파싱하는 동안 실행되는 스크립트. 브라우저에서 다시 렌더링될 때는
// type을 text/plain으로 바꿔 실행하지 않는다(Next 가이드 'Preventing Flash').
export function InlineScript({ html }: { html: string }) {
  return (
    <script
      type={typeof window === "undefined" ? "text/javascript" : "text/plain"}
      suppressHydrationWarning
      dangerouslySetInnerHTML={{ __html: html }}
    />
  );
}
