package kr.govbenefit.account.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 로그인·탈퇴의 비밀번호 확인 시도 제한(결정 0035). 최근 {@code window} 안의 실패를 (이메일+IP)별, IP별로 센다.
 *
 * @param window                    실패 횟수를 세는 기간
 * @param maxFailuresPerEmailAndIp  같은 이메일·IP의 기간 안 최대 실패 횟수
 * @param maxFailuresPerIp          같은 IP의 기간 안 최대 실패 횟수
 */
@ConfigurationProperties("auth.login-attempt")
public record LoginAttemptProperties(Duration window, int maxFailuresPerEmailAndIp, int maxFailuresPerIp) {
}
