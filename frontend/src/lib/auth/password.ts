// 서버의 비밀번호 규칙(PasswordRule)과 같다. 서버 검증이 기준이고, 이 확인은 보내기 전에 알려주는 용도다.
export const PASSWORD_RULE_TEXT = "8자 이상 입력해 주세요. 한글만 쓰면 24자까지 입력할 수 있습니다.";

const MIN_LENGTH = 8;
const MAX_BYTES = 72;

export function checkPassword(password: string): string | null {
  if ([...password].length < MIN_LENGTH) {
    return "8자 이상이어야 합니다.";
  }
  if (new TextEncoder().encode(password).length > MAX_BYTES) {
    return "너무 깁니다. 영문·숫자는 72자, 한글은 24자까지 입력할 수 있습니다.";
  }
  return null;
}
