package kr.govbenefit.account.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;

/**
 * 탈퇴 요청(DEC-AUTH). 비밀번호 규칙은 검사하지 않는다. 맞지 않으면 비밀번호 불일치와 같은 응답이다.
 */
@Schema(description = "탈퇴 요청")
public record WithdrawalRequest(
        @Schema(description = "지금 비밀번호. 되돌릴 수 없는 삭제라 다시 확인한다.", example = "correct-horse",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty(message = "비밀번호를 입력해야 합니다.")
        String password) {

    /**
     * 로그로 남겨도 비밀번호가 드러나지 않게 한다.
     */
    @Override
    public String toString() {
        return "WithdrawalRequest[password=****]";
    }
}
