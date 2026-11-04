package kr.govbenefit.account.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 가입 전 이메일 인증번호 요청(결정 0041). 만 14세 미만의 이메일로는 메일을 보내지 않도록 이 단계에서도 만 14세 이상인지
 * 확인받는다(#111, 결정 0044).
 */
@Schema(description = "인증번호 요청")
public record EmailVerificationRequest(
        @Schema(description = "가입할 이메일. 대소문자를 구분하지 않는다. 254자까지.",
                example = "me@example.com", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "이메일을 입력해야 합니다.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        @Size(max = SignUpRequest.MAX_EMAIL_LENGTH, message = "254자까지 입력할 수 있습니다.")
        String email,
        @Schema(description = SignUpRequest.AGE_CONFIRMED_DESCRIPTION, example = "true",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = SignUpRequest.AGE_CONFIRMATION_MESSAGE)
        @AssertTrue(message = SignUpRequest.AGE_CONFIRMATION_MESSAGE)
        Boolean ageConfirmed) {
}
