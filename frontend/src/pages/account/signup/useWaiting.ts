import { useEffect, useState } from "react";

// at(밀리초 시각)이 지나기 전이면 true다. 지나면 다시 그려 false가 된다.
export function useWaiting(at: number | undefined): boolean {
  const [passed, setPassed] = useState<number | undefined>(undefined);
  useEffect(() => {
    if (at === undefined) return;
    const timer = setTimeout(() => setPassed(at), Math.max(0, at - Date.now()));
    return () => clearTimeout(timer);
  }, [at]);
  return at !== undefined && passed !== at;
}
