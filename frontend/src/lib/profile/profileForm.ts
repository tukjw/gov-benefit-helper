import type { Gender, ProfileOptionsResponse, ProfileRequest, ProfileResponse } from "@/lib/api/types";

// 프로필 폼의 이름은 API 필드와 같다. 서버의 항목별 오류(errors[].field)를 그대로 칸에 붙인다.

export const NONE = "NONE";

// 서버의 이름은 '해당사항없음'이다. 화면에서는 미입력과 맞세워 '해당 없음'으로 쓴다.
export function attributeLabel(option: { value: string; label: string }): string {
  return option.value === NONE ? "해당 없음" : option.label;
}

export const GENDERS: { value: Gender; label: string }[] = [
  { value: "MALE", label: "남성" },
  { value: "FEMALE", label: "여성" },
];

// 빈 칸은 미입력(null)이다. 속성은 아무것도 고르지 않으면 미입력이고 '해당 없음'은 ["NONE"]이다(결정 0022).
export function profileFromForm(form: FormData): ProfileRequest {
  const text = (name: string) => {
    const value = form.get(name);
    return typeof value === "string" && value.trim() !== "" ? value.trim() : null;
  };
  const list = (name: string) => {
    const values = form.getAll(name).filter((value): value is string => typeof value === "string" && value !== "");
    return values.length > 0 ? values : null;
  };
  return {
    birthDate: text("birthDate"),
    gender: text("gender") as Gender | null,
    sidoCode: text("sidoCode"),
    // 시도 없이 시군구만 남지 않게 한다. 서버도 거절한다.
    sigunguCode: text("sidoCode") ? text("sigunguCode") : null,
    lifeAttributes: list("lifeAttributes"),
    householdAttributes: list("householdAttributes"),
  };
}

export function requestFromResponse(profile: ProfileResponse): ProfileRequest {
  return {
    birthDate: profile.birthDate,
    gender: profile.gender,
    sidoCode: profile.sido?.code ?? null,
    sigunguCode: profile.sigungu?.code ?? null,
    lifeAttributes: profile.lifeAttributes,
    householdAttributes: profile.householdAttributes,
  };
}

export function isEmptyProfile(profile: ProfileRequest): boolean {
  return Object.values(profile).every((value) => value === null);
}

export function hasResidence(profile: ProfileRequest): boolean {
  return profile.sidoCode !== null;
}

// 입력 조건 요약 박스의 한 줄씩. 미입력은 "입력 안 함"으로 보여 '해당 없음'과 구분한다.
export function summarizeProfile(
  profile: ProfileRequest,
  options: ProfileOptionsResponse,
): { label: string; value: string }[] {
  const sido = options.sidos.find((option) => option.code === profile.sidoCode);
  const sigungu = sido?.sigungus.find((option) => option.code === profile.sigunguCode);
  const attributes = (values: string[] | null, choices: { value: string; label: string }[]) =>
    values === null
      ? NOT_ENTERED
      : values
          .map((value) => {
            const choice = choices.find((item) => item.value === value);
            return choice ? attributeLabel(choice) : value;
          })
          .join(", ");
  return [
    { label: "생년월일", value: profile.birthDate ? formatBirthDate(profile.birthDate) : NOT_ENTERED },
    { label: "성별", value: GENDERS.find((gender) => gender.value === profile.gender)?.label ?? NOT_ENTERED },
    { label: "거주지", value: sido ? [sido.name, sigungu?.name].filter(Boolean).join(" ") : NOT_ENTERED },
    { label: "생애·직업·건강", value: attributes(profile.lifeAttributes, options.lifeAttributes) },
    { label: "가구", value: attributes(profile.householdAttributes, options.householdAttributes) },
  ];
}

const NOT_ENTERED = "입력 안 함";

function formatBirthDate(value: string): string {
  const [year, month, day] = value.split("-");
  return `${year}년 ${Number(month)}월 ${Number(day)}일`;
}
