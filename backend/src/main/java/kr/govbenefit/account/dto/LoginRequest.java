package kr.govbenefit.account.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/**
 * 로그인 요청. 이메일 형식과 비밀번호 규칙은 검사하지 않는다. 맞지 않으면 로그인 실패와 같은 응답이다.
 */
@Schema(description = "로그인 요청")
public record LoginRequest(
        @Schema(description = "가입한 이메일. 대소문자를 구분하지 않는다.", example = "me@example.com",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "이메일을 입력해야 합니다.")
        String email,
        @Schema(description = "비밀번호", example = "correct-horse", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty(message = "비밀번호를 입력해야 합니다.")
        String password) {

    /**
     * 로그로 남겨도 비밀번호가 드러나지 않게 한다.
     */
    @Override
    public String toString() {
        return "LoginRequest[email=" + email + ", password=****]";
    }
}
