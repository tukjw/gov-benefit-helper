package kr.govbenefit.account.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 가입 전 이메일 인증번호 요청(결정 0019).
 */
@Schema(description = "인증번호 요청")
public record EmailVerificationRequest(
        @Schema(description = "가입할 이메일. 대소문자를 구분하지 않는다. 254자까지.",
                example = "me@example.com", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "이메일을 입력해야 합니다.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        @Size(max = SignUpRequest.MAX_EMAIL_LENGTH, message = "254자까지 입력할 수 있습니다.")
        String email) {
}
