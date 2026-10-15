package kr.govbenefit.account.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 리프레시 토큰 설정(결정 0017).
 *
 * @param ttl        유효 기간. 재발급할 때마다 새 토큰이 이 기간을 새로 받는다(연장 만료).
 * @param reuseGrace 교체 직후 이전 토큰이 다시 와도 재사용으로 보지 않는 시간
 */
@ConfigurationProperties("auth.refresh-token")
public record RefreshTokenProperties(Duration ttl, Duration reuseGrace) {
}
