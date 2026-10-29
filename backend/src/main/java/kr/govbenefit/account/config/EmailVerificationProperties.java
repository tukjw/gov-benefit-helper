package kr.govbenefit.account.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 가입 전 이메일 인증 설정(결정 0019).
 *
 * @param codeTtl     인증번호 유효 기간
 * @param maxAttempts 인증번호 하나를 비교할 수 있는 횟수. 이만큼 틀리면 맞는 번호도 거절한다.
 * @param ticketTtl   번호를 맞힌 뒤 가입 티켓의 유효 기간
 */
@ConfigurationProperties("auth.email-verification")
public record EmailVerificationProperties(Duration codeTtl, int maxAttempts, Duration ticketTtl) {
}
