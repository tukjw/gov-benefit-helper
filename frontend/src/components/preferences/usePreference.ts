import { useSyncExternalStore } from "react";
import { readPreference, subscribePreference, type PreferenceName } from "@/lib/preferences";

// <html> 속성이 현재 값이다. 첫 페인트 전 스크립트가 저장값을 이미 반영해 두었다.
export function usePreference<K extends PreferenceName>(name: K) {
  return useSyncExternalStore(subscribePreference, () => readPreference(name));
}
