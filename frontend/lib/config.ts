// NEXT_PUBLIC_* 값은 빌드할 때 번들에 고정되므로 환경마다 다시 빌드해야 한다.
// 값이 없으면 로컬 주소로 대체하지 않는다. 운영 빌드에 로컬 주소가 들어가지 않게 한다.
export function apiBaseUrl(): string {
  const value = process.env.NEXT_PUBLIC_API_BASE_URL;
  if (!value) {
    throw new Error("NEXT_PUBLIC_API_BASE_URL이 설정되지 않았습니다.");
  }
  return value.replace(/\/+$/, "");
}
