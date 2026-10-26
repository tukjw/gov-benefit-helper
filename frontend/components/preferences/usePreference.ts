import { useSyncExternalStore } from "react";
import {
  defaultPreference,
  readPreference,
  subscribePreference,
  type PreferenceName,
} from "@/lib/preferences";

// 서버 렌더링과 하이드레이션 중에는 기본값을 쓰고, 그 뒤 <html> 속성의 실제 값으로 바뀐다.
export function usePreference<K extends PreferenceName>(name: K) {
  return useSyncExternalStore(
    subscribePreference,
    () => readPreference(name),
    () => defaultPreference(name),
  );
}
