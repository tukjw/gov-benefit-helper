import { redirect, type ActionFunctionArgs, type LoaderFunctionArgs } from "react-router";
import { listGuestMatches } from "@/lib/api/match";
import { getMyProfile, getProfileOptions, saveMyProfile } from "@/lib/api/profile";
import type { ProfileOptionsResponse, ProfileRequest } from "@/lib/api/types";
import { AuthRequiredError } from "@/lib/auth/authFetch";
import { memberOnly } from "@/lib/auth/guard";
import { ensureSession, getSession } from "@/lib/auth/session";
import { readGuestProfile, writeGuestProfile } from "@/lib/profile/guestProfile";
import { profileFromForm, requestFromResponse } from "@/lib/profile/profileForm";
import { showToast } from "@/lib/toast";
import { fieldErrorsOf, GENERIC_ERROR, type FieldErrors } from "@/pages/account/formErrors";

export type ProfileData = { member: boolean; options: ProfileOptionsResponse; profile: ProfileRequest | null };

export type ProfileResult = { fieldErrors?: FieldErrors; formError?: string };

// 회원과 비회원이 같은 폼을 쓴다(#74 설계 4). 회원은 저장된 값을, 비회원은 이 탭에 둔 값을 채운다.
export async function profileLoader({ request }: LoaderFunctionArgs): Promise<ProfileData> {
  await ensureSession();
  const options = await getProfileOptions();
  if (getSession().status === "member") {
    return memberOnly(request, async () => {
      const profile = await getMyProfile(request.signal);
      return { member: true, options, profile: profile.updatedAt ? requestFromResponse(profile) : null };
    });
  }
  return { member: false, options, profile: readGuestProfile() };
}

// 회원은 저장한다. 비회원은 같은 검증 기준인 조건 비교 API로 값을 검사한 뒤 이 탭에만 둔다(설계 5).
export async function profileAction({ request }: ActionFunctionArgs): Promise<ProfileResult | Response> {
  const profile = profileFromForm(await request.formData());
  await ensureSession();
  if (getSession().status === "member") {
    return memberOnly(request, async () => {
      try {
        await saveMyProfile(profile);
      } catch (error) {
        if (error instanceof AuthRequiredError) throw error;
        return failure(error);
      }
      showToast("프로필을 저장했어요.");
      return redirect("/match");
    });
  }
  try {
    await listGuestMatches(profile, { scope: null, page: 1, size: 1 });
  } catch (error) {
    return failure(error);
  }
  writeGuestProfile(profile);
  return redirect("/match");
}

function failure(error: unknown): ProfileResult {
  const invalid = fieldErrorsOf(error);
  return invalid ? { fieldErrors: invalid } : { formError: GENERIC_ERROR };
}
