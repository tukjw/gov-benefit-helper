import { useSyncExternalStore } from "react";
import { removeBookmark, saveBookmark, savedServiceIds } from "@/lib/api/bookmarks";
import { ensureSession, getSession } from "@/lib/auth/session";

// 관심 저장 상태(#74 설계 6). 같은 혜택이 여러 곳에 보여도 같은 값을 쓰도록 모듈에 둔다.
// 화면은 누른 즉시 원하는 상태(desired)를 보여주고, 서버에는 혜택마다 요청을 하나씩 차례로 보낸다.
// 응답이 오면 원하는 상태와 서버에 반영한 상태(saved)를 비교해 다르면 한 번 더 보내므로, 연타해도 마지막 의도가
// 마지막 요청이 된다. 저장·해제가 멱등이라 같은 값을 다시 보내도 된다.

type Entry = { saved: boolean; desired: boolean; sending: Promise<void> | null };

const entries = new Map<string, Entry>();
const listeners = new Set<() => void>();

function emit() {
  listeners.forEach((listener) => listener());
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

// 아직 모르면 undefined다.
export function useBookmark(serviceId: string): boolean | undefined {
  return useSyncExternalStore(subscribe, () => entries.get(serviceId)?.desired);
}

// 서버에서 받은 저장 여부로 채운다. 보내는 중이거나 아직 보내지 않은 변경이 있는 혜택은 덮어쓰지 않는다.
export function seedBookmarks(serviceIds: string[], saved: Iterable<string>): void {
  const savedSet = new Set(saved);
  for (const serviceId of serviceIds) {
    const entry = entries.get(serviceId);
    if (entry && (entry.sending || entry.desired !== entry.saved)) continue;
    const value = savedSet.has(serviceId);
    entries.set(serviceId, { saved: value, desired: value, sending: null });
  }
  emit();
}

// 화면에 보인 혜택의 저장 여부를 받아 채운다. 비회원이거나 받지 못하면 채우지 않아 버튼이 저장 여부를 모르는 상태로 남는다.
// loader가 기다리지 않도록 결과를 돌려주지 않는다(설계 7).
export function loadBookmarks(serviceIds: string[]): void {
  if (serviceIds.length === 0) return;
  void ensureSession()
    .then(async () => {
      if (getSession().status !== "member") return;
      seedBookmarks(serviceIds, await savedServiceIds(serviceIds));
    })
    .catch(() => undefined);
}

// 누른 순간 화면에 보일 상태
export function markBookmark(serviceId: string, desired: boolean): void {
  const entry = entries.get(serviceId) ?? { saved: !desired, desired, sending: null };
  entries.set(serviceId, { ...entry, desired });
  emit();
}

// 원하는 상태가 서버에 반영될 때까지 보낸다. 이미 보내는 중이면 그 흐름을 함께 기다린다.
// 실패하면 서버에 반영된 마지막 상태로 되돌리고 오류를 던진다.
export function flushBookmark(serviceId: string): Promise<void> {
  const entry = entries.get(serviceId);
  if (!entry) return Promise.resolve();
  if (entry.sending) return entry.sending;
  const sending = send(serviceId).finally(() => {
    const current = entries.get(serviceId);
    if (current) entries.set(serviceId, { ...current, sending: null });
  });
  entries.set(serviceId, { ...entry, sending });
  return sending;
}

async function send(serviceId: string): Promise<void> {
  for (;;) {
    const entry = entries.get(serviceId);
    if (!entry || entry.desired === entry.saved) return;
    const target = entry.desired;
    try {
      await (target ? saveBookmark(serviceId) : removeBookmark(serviceId));
    } catch (error) {
      const current = entries.get(serviceId);
      if (current) entries.set(serviceId, { ...current, desired: current.saved });
      emit();
      throw error;
    }
    const current = entries.get(serviceId);
    if (current) entries.set(serviceId, { ...current, saved: target });
  }
}
