package kr.govbenefit.account.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import kr.govbenefit.account.domain.UserAccount;
import kr.govbenefit.common.api.ApiTimes;

/**
 * 계정 정보. 사용자 ID는 넣지 않는다(결정 0015).
 */
@Schema(description = "계정 정보")
public record AccountResponse(
        @Schema(description = "이메일. 소문자로 저장된 값이다.", example = "me@example.com") String email,
        @Schema(description = "가입 시각", example = ApiTimes.EXAMPLE) OffsetDateTime createdAt) {

    public static AccountResponse from(UserAccount account) {
        return new AccountResponse(account.getEmail(), ApiTimes.toOffset(account.getCreatedAt()));
    }
}
