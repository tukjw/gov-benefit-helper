// 화면 설정(결과 보기 방식)은 브라우저에만 저장하고 서버는 읽지 않는다(결정 0026).
// <html>의 data-* 속성이 현재 값이며, CSS가 이 속성으로 화면을 바꾼다.

export type PreferenceName = "view";

export type ViewMode = "card" | "list";

type PreferenceValue = { view: ViewMode };

export const VIEW_MODES: { value: ViewMode; label: string }[] = [
  { value: "card", label: "카드형" },
  { value: "list", label: "목록형" },
];

const DEFAULTS: PreferenceValue = { view: "card" };

const ALLOWED: { [K in PreferenceName]: readonly PreferenceValue[K][] } = {
  view: ["card", "list"],
};

const STORAGE_KEYS: Record<PreferenceName, string> = {
  view: "gbh:view",
};

const CHANGE_EVENT = "gbh:preferencechange";

// <head>에서 첫 페인트 전에 실행된다. 기본값이면 속성을 두지 않는다.
// 저장소 접근이 막힌 환경(사생활 보호 모드 등)에서는 기본값으로 둔다.
export const PREFERENCES_SCRIPT = `(function(){try{var v=localStorage.getItem(${JSON.stringify(
  STORAGE_KEYS.view,
)});if(v==="list")document.documentElement.dataset.view=v;}catch(e){}})();`;

export function defaultPreference<K extends PreferenceName>(name: K): PreferenceValue[K] {
  return DEFAULTS[name];
}

export function readPreference<K extends PreferenceName>(name: K): PreferenceValue[K] {
  const value = document.documentElement.dataset[name];
  return (ALLOWED[name] as readonly string[]).includes(value ?? "")
    ? (value as PreferenceValue[K])
    : DEFAULTS[name];
}

export function writePreference<K extends PreferenceName>(name: K, value: PreferenceValue[K]): void {
  const root = document.documentElement;
  if (value === DEFAULTS[name]) {
    delete root.dataset[name];
  } else {
    root.dataset[name] = value;
  }
  try {
    localStorage.setItem(STORAGE_KEYS[name], value);
  } catch {
    // 저장하지 못해도 지금 화면에는 적용된다.
  }
  window.dispatchEvent(new Event(CHANGE_EVENT));
}

export function subscribePreference(onChange: () => void): () => void {
  window.addEventListener(CHANGE_EVENT, onChange);
  return () => window.removeEventListener(CHANGE_EVENT, onChange);
}
