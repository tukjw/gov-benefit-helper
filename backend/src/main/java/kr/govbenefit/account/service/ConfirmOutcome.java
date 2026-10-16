package kr.govbenefit.account.service;

import java.time.LocalDateTime;
import kr.govbenefit.common.api.ErrorCode;

/**
 * 인증번호 확인 결과. 거절도 예외가 아니라 값으로 돌려준다. 예약한 시도가 거절 응답과 함께 롤백되면 안 되기 때문이다(결정 0019).
 */
public sealed interface ConfirmOutcome {

    /**
     * @param verificationToken 가입 티켓 원문. 응답 본문으로만 보낸다.
     * @param expiresAt         티켓 만료 시각
     */
    record Confirmed(String verificationToken, LocalDateTime expiresAt) implements ConfirmOutcome {
    }

    record Rejected(ErrorCode code) implements ConfirmOutcome {
    }
}
