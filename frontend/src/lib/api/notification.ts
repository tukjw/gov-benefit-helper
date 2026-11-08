import { authFetch } from "@/lib/auth/authFetch";
import type { NotificationSettingRequest, NotificationSettingResponse } from "./types";

// 새 혜택 알림 켜기·끄기(REQ-NOTIFY-03, DEC-NOTIFY). 바꾸면 204라 본문이 없다.

export function getNotificationSettings(signal?: AbortSignal): Promise<NotificationSettingResponse> {
  return authFetch("GET", "/api/me/notification-settings", { signal });
}

export function updateNotificationSettings(setting: NotificationSettingRequest): Promise<void> {
  return authFetch("PATCH", "/api/me/notification-settings", { body: setting });
}
