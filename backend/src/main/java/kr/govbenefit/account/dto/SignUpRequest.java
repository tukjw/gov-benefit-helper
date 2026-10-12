package kr.govbenefit.account.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 가입 요청. 이메일은 대소문자를 구분하지 않는다.
 */
@Schema(description = "가입 요청")
public record SignUpRequest(
        @Schema(description = "이메일. 대소문자를 구분하지 않으며 소문자로 저장한다. 254자까지.",
                example = "me@example.com", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "이메일을 입력해야 합니다.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        @Size(max = MAX_EMAIL_LENGTH, message = "254자까지 입력할 수 있습니다.")
        String email,
        @Schema(description = "비밀번호. 8자 이상, UTF-8 72바이트 이하(한글만 쓰면 24자). 문자 종류 조합 규칙은 없다.",
                example = "correct-horse", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "비밀번호를 입력해야 합니다.")
        @Password
        String password) {

    public static final int MAX_EMAIL_LENGTH = 254;

    /**
     * 로그로 남겨도 비밀번호가 드러나지 않게 한다.
     */
    @Override
    public String toString() {
        return "SignUpRequest[email=" + email + ", password=****]";
    }
}
