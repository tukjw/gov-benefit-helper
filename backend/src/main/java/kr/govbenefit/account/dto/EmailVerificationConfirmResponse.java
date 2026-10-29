package kr.govbenefit.account.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import kr.govbenefit.account.service.ConfirmOutcome.Confirmed;
import kr.govbenefit.common.api.ApiTimes;

/**
 * 인증번호 확인 결과. 가입 요청에 티켓을 넣는다(결정 0019).
 */
@Schema(description = "인증번호 확인 결과")
public record EmailVerificationConfirmResponse(
        @Schema(description = "가입 티켓. 가입 요청의 `verificationToken`에 넣는다. 확인한 이메일로만, 한 번만 쓸 수 있다.",
                example = "Hq3kS0b5rZ1yJ6mVwQ2cXf8nLtA4pE7uGdR9iKoBhYs") String verificationToken,
        @Schema(description = "가입 티켓 만료 시각. 확인 후 30분이다.", example = ApiTimes.EXAMPLE) OffsetDateTime expiresAt) {

    public static EmailVerificationConfirmResponse from(Confirmed confirmed) {
        return new EmailVerificationConfirmResponse(confirmed.verificationToken(),
                ApiTimes.toOffset(confirmed.expiresAt()));
    }
}
