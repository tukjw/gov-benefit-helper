import type { ProfileRequest } from "@/lib/api/types";

// 비회원이 입력한 프로필 값(결정 0031). 같은 탭에서만 남도록 sessionStorage에 둔다. 서버에는 조건 비교 요청
// 본문으로만 보내고 저장하지 않는다. 로그인하면 지우고 저장된 프로필을 쓴다.
// 저장소 접근이 막혔거나 값이 깨졌으면 입력하지 않은 것으로 본다. 동의 여부(#110)가 없는 값도 그렇다. 배포 전에 남은 값은
// 건강 관련 속성을 동의 없이 담았을 수 있어, 그대로 보내면 조건 비교가 거절된다.

const STORAGE_KEY = "gbh:guest-profile";

// 비회원에게만 보이는 안내(docs/design/README.md F3). 값은 이 탭에 남으므로 "서버에"를 뺄 수 없다.
export const GUEST_NOTICE = "입력한 정보는 서버에 저장되지 않아요.";

const TEXT_FIELDS = ["birthDate", "gender", "sidoCode", "sigunguCode"] as const;
const LIST_FIELDS = ["lifeAttributes", "householdAttributes"] as const;

export function readGuestProfile(): ProfileRequest | null {
  let raw: string | null;
  try {
    raw = sessionStorage.getItem(STORAGE_KEY);
  } catch {
    return null;
  }
  if (!raw) return null;
  try {
    const value: unknown = JSON.parse(raw);
    return isProfileRequest(value) ? value : null;
  } catch {
    return null;
  }
}

export function writeGuestProfile(profile: ProfileRequest): void {
  try {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(profile));
  } catch {
    // 저장하지 못하면 다음 화면에서 다시 입력하게 된다.
  }
}

export function clearGuestProfile(): void {
  try {
    sessionStorage.removeItem(STORAGE_KEY);
  } catch {
    // 지울 값을 읽을 수도 없으므로 그대로 둔다.
  }
}

function isProfileRequest(value: unknown): value is ProfileRequest {
  if (typeof value !== "object" || value === null) return false;
  const record = value as Record<string, unknown>;
  return (
    typeof record.sensitiveAttributeConsent === "boolean" &&
    TEXT_FIELDS.every((field) => record[field] === null || typeof record[field] === "string") &&
    LIST_FIELDS.every(
      (field) =>
        record[field] === null ||
        (Array.isArray(record[field]) && (record[field] as unknown[]).every((item) => typeof item === "string")),
    )
  );
}
