import { useSyncExternalStore } from "react";

// 화면 하단의 짧은 알림(시안 '토스트'). 로그인 뒤 리다이렉트처럼 화면이 바뀐 뒤에도 보이도록
// action 같은 컴포넌트 밖에서 띄울 수 있게 모듈에 둔다.

export type Toast = { id: number; message: string };

const DURATION_MS = 2800;

let toast: Toast | null = null;
let nextId = 1;
let timer: ReturnType<typeof setTimeout> | undefined;
const listeners = new Set<() => void>();

function emit() {
  listeners.forEach((listener) => listener());
}

export function showToast(message: string): void {
  clearTimeout(timer);
  toast = { id: nextId++, message };
  emit();
  timer = setTimeout(() => {
    toast = null;
    emit();
  }, DURATION_MS);
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function useToast(): Toast | null {
  return useSyncExternalStore(subscribe, () => toast);
}
