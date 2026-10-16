package kr.govbenefit.account.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 비밀번호 재설정(결정 0019). 새 비밀번호는 가입과 같은 규칙이다.
 */
@Schema(description = "비밀번호 재설정")
public record PasswordResetConfirmRequest(
        @Schema(description = "재설정 토큰. 메일 링크의 `#token=` 뒤의 값이다.",
                example = "Hq3kS0b5rZ1yJ6mVwQ2cXf8nLtA4pE7uGdR9iKoBhYs", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "재설정 토큰이 필요합니다.")
        String token,
        @Schema(description = "새 비밀번호. 8자 이상, UTF-8 72바이트 이하(한글만 쓰면 24자). 문자 종류 조합 규칙은 없다.",
                example = "new-correct-horse", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "비밀번호를 입력해야 합니다.")
        @Password
        String newPassword) {

    /**
     * 로그로 남겨도 토큰과 비밀번호가 드러나지 않게 한다.
     */
    @Override
    public String toString() {
        return "PasswordResetConfirmRequest[token=****, newPassword=****]";
    }
}
