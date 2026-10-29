package kr.govbenefit.account.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 인증번호 확인(결정 0019). 형식이 틀린 번호는 입력 오류이고 시도로 세지 않는다.
 */
@Schema(description = "인증번호 확인")
public record EmailVerificationConfirmRequest(
        @Schema(description = "인증번호를 요청한 이메일. 대소문자를 구분하지 않는다.",
                example = "me@example.com", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "이메일을 입력해야 합니다.")
        String email,
        @Schema(description = "메일로 받은 6자리 숫자", example = "042917", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "인증번호를 입력해야 합니다.")
        @Pattern(regexp = "\\d{6}", message = "6자리 숫자여야 합니다.")
        String code) {
}
